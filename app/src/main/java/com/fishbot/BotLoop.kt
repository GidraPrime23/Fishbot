package com.fishbot

import android.os.SystemClock

/**
 * Цикл рыбалки: заброс -> ждём поклёвку (значок "!") -> подсечка -> мини-игра -> повтор.
 * Всё по картинке экрана (Vision), нажатия - через BotAccessibilityService.
 */
class BotLoop(
    private val grab: ScreenGrabber,
    private val prefs: Prefs,
    private val onState: (String) -> Unit,
    private val onStop: (String) -> Unit,
    private val onCatch: () -> Unit
) {
    @Volatile
    var active = false
        private set
    private var zoneAbs: IntArray? = null   // {xl, xr, top, bottom} в координатах экрана

    fun start() {
        if (active) return
        active = true
        Thread {
            try {
                loop()
            } catch (e: InterruptedException) {
                // остановлено
            } catch (e: Throwable) {
                fail("Ошибка: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        active = false
        BotAccessibilityService.instance?.setHold(false, 0f, 0f)
    }

    private fun fail(msg: String) {
        if (!active) return
        active = false
        BotAccessibilityService.instance?.setHold(false, 0f, 0f)
        onStop(msg)
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun nap(ms: Long) {
        val end = now() + ms
        while (active && now() < end) Thread.sleep(minOf(20L, maxOf(1L, end - now())))
    }

    // ---------------------------------------------------------------- зрение

    /** Есть ли мини-игра на экране (ищем зелёную/коричневую зону в правой половине). */
    private fun peekBar(W: Int, sc: Double): Boolean {
        val img = grab.latest() ?: return false
        val x0 = (W * 0.5).toInt()
        val w = img.width - x0
        val px = grab.region(img, x0, 0, w, img.height)
        val z = Vision.findZone(px, w, img.height, sc) ?: return false
        zoneAbs = intArrayOf(z[0] + x0, z[1] + x0, z[2], z[3])
        return true
    }

    /** Есть ли значок "!" на кнопке рыбалки. Лёгкая проверка небольшой области. */
    private fun peekBite(W: Int, H: Int, sc: Double, bx: Int, by: Int): Boolean {
        val img = grab.latest() ?: return false
        val x0 = maxOf(0, bx - (0.07 * W).toInt())
        val y0 = maxOf(0, by - (0.13 * H).toInt())
        val x1 = minOf(img.width, bx + (0.07 * W).toInt())
        val y1 = minOf(img.height, by + (0.10 * H).toInt())
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return false
        return Vision.hasBite(grab.region(img, x0, y0, w, h), w, h, sc)
    }

    // ------------------------------------------------------------------ цикл

    private fun loop() {
        var misses = 0
        val ctl = Vision.Controller()
        while (active) {
            val acc = BotAccessibilityService.instance
            if (acc == null) { fail("Включите спецвозможности FishBot"); return }
            if (!prefs.hasButton) { fail("Сначала калибровка кнопки (⌖)"); return }
            grab.ensure()
            val W = grab.width
            val H = grab.height
            if (W < H) { onState("поверните экран"); nap(500); continue }
            val sc = H / 1220.0
            val bx = (prefs.btnX * W).toInt()
            val by = (prefs.btnY * H).toInt()

            var bar = peekBar(W, sc)
            var bite = !bar && peekBite(W, H, sc, bx, by)

            if (!bar && !bite) {
                onState("заброс")
                acc.tap(bx.toFloat(), by.toFloat())
                nap(900)
                onState("ожидание")
                val t0 = now()
                while (active && now() - t0 < prefs.biteTimeoutSec * 1000L) {
                    if (peekBite(W, H, sc, bx, by)) { bite = true; break }
                    nap(15)
                }
                if (!active) return
                if (!bite) {
                    misses++
                    if (misses >= prefs.maxMiss) { fail("Нет поклёвок $misses раз подряд"); return }
                    onState("нет поклёвки")
                    continue
                }
            }
            misses = 0

            if (bite) {
                onState("подсечка")
                acc.tap(bx.toFloat(), by.toFloat())
                val t0 = now()
                var retried = false
                while (active && now() - t0 < 3500) {
                    nap(40)
                    if (peekBar(W, sc)) { bar = true; break }
                    if (!retried && now() - t0 > 1200 && peekBite(W, H, sc, bx, by)) {
                        acc.tap(bx.toFloat(), by.toFloat()); retried = true
                    }
                }
                if (!bar) continue
            }

            if (bar && active) {
                play(W, sc, bx, by, ctl)
                onCatch()
                onState("пауза после улова")
                nap(2500)
            }
        }
    }

    // ------------------------------------------------------------ мини-игра

    private fun play(W: Int, sc: Double, bx: Int, by: Int, ctl: Vision.Controller) {
        val acc = BotAccessibilityService.instance ?: return
        val z0 = zoneAbs ?: return
        onState("мини-игра")
        val zw = z0[1] - z0[0] + 1
        val sx0 = maxOf(0, z0[0] - zw)
        val sx1 = minOf(W - 1, z0[1] + zw)
        val sw = sx1 - sx0 + 1
        ctl.reset()
        var last: IntArray? = null
        var lastZoneT = 0L
        var lastOk = now()
        while (active) {
            val img = grab.poll()
            if (img == null) {
                Thread.sleep(3)
                if (now() - lastOk > 800) break
                continue
            }
            val h = img.height
            val px = grab.region(img, sx0, 0, sw, h)
            val t = now()
            val z = Vision.findZone(px, sw, h, sc)
            if (z != null) { last = z; lastZoneT = t }
            val lz = last
            if (lz != null && t - lastZoneT < 600) {
                val m = Vision.findMarker(px, sw, h, lz, sc)
                if (!m.isNaN()) {
                    lastOk = t
                    val hold = ctl.decide(t / 1000.0, m, lz[2].toDouble(), lz[3].toDouble())
                    acc.setHold(hold, bx.toFloat(), by.toFloat())
                }
            }
            if (t - lastOk > 800) break
        }
        acc.setHold(false, bx.toFloat(), by.toFloat())
    }
}

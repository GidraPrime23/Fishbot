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
    private val onCatch: () -> Unit,
    private val onTap: (Int, Int) -> Unit = { _, _ -> }
) {
    @Volatile
    var active = false
        private set
    private var zoneAbs: IntArray? = null   // {xl, xr, top, bottom} в координатах экрана
    private val rod = RodSwap(grab, onState) { active }

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

    /** Тап с сообщением в панель (рисует кружок в точке нажатия). */
    private fun tapAt(acc: BotAccessibilityService, x: Int, y: Int) {
        onTap(x, y)
        acc.tap(x.toFloat(), y.toFloat())
    }

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
        if (z[2] < 430 * sc || z[3] > 1010 * sc) return false
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
        val px = grab.region(img, x0, y0, w, h)
        Diag.badge(px, w, h, onState)
        return Vision.hasBite(px, w, h, sc)
    }

    // ------------------------------------------------------------------ цикл

    private fun loop() {
        var misses = 0
        var softFails = 0
        val ctl = Pilot()
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
                tapAt(acc, bx, by)
                nap(900)
                if (rod.brokenNow()) {
                    if (rod.swap(acc)) { softFails = 0; misses = 0; continue }
                    if (rod.soft && ++softFails < 3) { /* ложная тревога: ловим дальше */ }
                    else { fail("Удочку не удалось одеть: ${rod.lastWhy}"); return }
                }
                onState("ожидание")
                val t0 = now()
                while (active && now() - t0 < prefs.biteTimeoutSec * 1000L) {
                    if (peekBite(W, H, sc, bx, by)) { bite = true; break }
                    nap(15)
                }
                if (!active) return
                if (!bite) {
                    if (rod.brokenNow()) {
                        if (rod.swap(acc)) { softFails = 0; misses = 0; continue }
                        if (rod.soft && ++softFails < 3) { /* ложная тревога: ловим дальше */ }
                        else { fail("Удочку не удалось одеть: ${rod.lastWhy}"); return }
                    }
                    misses++
                    if (misses >= prefs.maxMiss) { fail("Нет поклёвок $misses раз подряд"); return }
                    onState("нет поклёвки")
                    continue
                }
            }
            misses = 0
            softFails = 0

            if (bite) {
                onState("подсечка")
                tapAt(acc, bx, by)
                val t0 = now()
                var retried = false
                while (active && now() - t0 < 3500) {
                    nap(40)
                    if (peekBar(W, sc)) { bar = true; break }
                    if (!retried && now() - t0 > 1200 && peekBite(W, H, sc, bx, by)) {
                        tapAt(acc, bx, by); retried = true
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

    private fun play(W: Int, sc: Double, bx: Int, by: Int, ctl: Pilot) {
        val acc = BotAccessibilityService.instance ?: return
        val z0 = zoneAbs ?: return
        onState("мини-игра")
        val zw = z0[1] - z0[0] + 1
        val sx0 = maxOf(0, z0[0] - zw)
        val sx1 = minOf(W - 1, z0[1] + zw)
        val sw = sx1 - sx0 + 1
        val tp = HoldPilot(); var fOff = 0.0
        val vOld = (HoldPilot.casts % 2 == 1)
        HoldPilot.casts += 1
        val vr = if (vOld) "OLD" else "NEW"
        tp.setLegacy(vOld)
        val trk = Tracker()
        FlightLog.start(vr)
        var last: IntArray? = null
        var lastZoneT = 0L
        var lastOk = now()
        val tStart = now()
        var prevT = tStart
        var endWhy = "stopped"
        try {
            while (active) {
                val img = grab.poll()
                if (img == null) {
                    Thread.sleep(3)
                    if (now() - lastOk > 800) { endWhy = "no_frames"; break }
                    continue
                }
                val tg = now()
        val h = img.height
                val px = grab.region(img, sx0, 0, sw, h)
                val t = now()
                val zRaw = Vision.findZone(px, sw, h, sc)
                val z = if (zRaw != null && (zRaw[2] < 430 * sc || zRaw[3] > 1010 * sc)) null else zRaw
                if (z != null) { last = z; lastZoneT = t }
                val lz = last
                if (lz != null && t - lastZoneT < 1000) {
                    val m = trk.find(px, sw, h, lz, sc, t, false)
                    val zk = if (lz.size > 4) lz[4] else -1
                    if (!m.isNaN()) {
                        lastOk = t
                        val fy = Vision.findFish(px, sw, h, lz, sc, m); if (!fy.isNaN()) fOff += 0.2 * ((fy - (lz[2] + lz[3]) / 2.0) - fOff); val aim = (lz[2] + lz[3]) / 2.0 + fOff; val hms = tp.decide(t / 1000.0, m, lz[2].toDouble(), lz[3].toDouble(), aim); val hold = hms > 0
                        val tDec = now() - tg
        val sent = hold && acc.pulse(bx.toFloat(), by.toFloat(), hms.toLong())
        val tSend = if (hold) now() - tStart else -1L
        if (sent) tp.pressed(t / 1000.0, hms)
                        FlightLog.row("${t - tStart},${m.toInt()},${lz[2]},${lz[3]},${tp.v.toInt()},${tp.p.toInt()},$hms,${t - prevT},${trk.state},${trk.bands},$zk,${tp.up.toInt()},${tp.streakNow()},$tDec,$tSend,$vr,${if (fy.isNaN()) -1 else fy.toInt()},${fOff.toInt()},${aim.toInt()},${tp.vm.toInt()},${lz[3] - lz[2]},${tp.needLast.toInt()},${tp.dRaw},${lz.size}")
                    } else {
                        val hold = false
                        if (hold) acc.pulse(bx.toFloat(), by.toFloat())
                        FlightLog.row("${t - tStart},NaN,${lz[2]},${lz[3]},0,0,${if (hold) 1 else 0},${t - prevT},${trk.state},${trk.bands},$zk,${tp.up.toInt()},${tp.streakNow()},-1,-1,$vr,-1,${fOff.toInt()},-1,${tp.vm.toInt()},${lz[3] - lz[2]},-1,0,${lz.size}")
                    }
                } else {
                    acc.setHold(false, bx.toFloat(), by.toFloat())
                    FlightLog.row("${t - tStart},NOZONE,0,0,0,0,0,${t - prevT},9,,0,${tp.up.toInt()},${tp.streakNow()},-1,-1,$vr,-1,${fOff.toInt()},-1,0,0,-1,0,0")
                }
                prevT = t
                if (t - lastOk > 800) { endWhy = "zone_lost"; break }
            if (t - tStart > 40000) { endWhy = "timeout"; break }
            }
        } finally {
            acc.setHold(false, bx.toFloat(), by.toFloat())
            FlightLog.save(acc, endWhy, "cast=${HoldPilot.casts},W=$W,sc=$sc,bx=$bx,by=$by,zw=$zw,sw=$sw")
        }
    }
}

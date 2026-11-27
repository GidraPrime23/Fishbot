package com.fishbot

import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore

/**
 * Автосбор ягод. ШАГ 1 (эксперимент), ТЕСТ ДЖОЙСТИКА v2 (патч p64).
 * Галочка в меню запускает тест; через 6 с два набора по четыре хода (вверх, вниз, вправо, влево, по 1,5 с, пауза 0,7 с):
 *  ЧАСТЬ 1 «hold»:  короткие штрихи, палец касается сразу в точке цели;
 *  ЧАСТЬ 2 «swipe»: короткие штрихи, палец касается в центре джойстика и сдвигается к цели.
 * Между частями пауза 6 с (запомнить X/Y). Внутри части ходы парами противоположны, персонаж должен вернуться почти на место.
 * Каждый штрих самозавершающийся, залипания пальца быть не должно. Лог: «Загрузки/fishbot_berries_*.txt».
 * Повторить тест: снять галочку и поставить снова. Ловля при включении останавливается.
 */
object BerriesFeature : Feature {
    override val id = "berries"
    override val title = "Ягоды (тест джойстика)"
    override val needs = setOf(Need.TAPS)
    override val defaultOn = false

    // Джойстик, замер в кадре 2712x1220: центр 0.160 W / 0.730 H (433; 890), внешний радиус 0.110 H (134 px).
    private const val JOY_X = 0.160f
    private const val JOY_Y = 0.730f
    private const val JOY_R = 0.110f
    private const val TRAVEL = 0.62f      // отклонение пальца от центра = 62% радиуса
    private const val START_DELAY_MS = 6000L
    private const val MOVE_MS = 1500L
    private const val PAUSE_MS = 700L
    private const val PART_GAP_MS = 6000L

    private val ui = Handler(Looper.getMainLooper())
    private var runId = 0
    private var active = false
    private val sb = StringBuilder()
    private var t0 = 0L

    private fun log(s: String) {
        sb.append(SystemClock.elapsedRealtime() - t0).append(' ').append(s).append('\n')
    }

    override fun onEnable(host: FeatureHost) {
        host.stopWork()
        val myRun = ++runId
        sb.setLength(0)
        t0 = SystemClock.elapsedRealtime()
        val acc = BotAccessibilityService.instance
        if (acc == null) {
            host.toast("Включите службу спецвозможностей FishBot")
            return
        }
        val (w, h) = host.screenSize()
        if (w < h) {
            host.toast("Поверните телефон в ландшафт (как в игре)")
            return
        }
        val ax = JOY_X * w
        val ay = JOY_Y * h
        val travel = JOY_R * h * TRAVEL
        active = true
        acc.joyReset()
        acc.joyLog = { s -> log("    " + s) }
        log("START v4 model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " screen=" + w + "x" + h +
            " anchor=" + ax.toInt() + "," + ay.toInt() + " r=" + (JOY_R * h).toInt() + " travel=" + travel.toInt() +
            " move_ms=" + MOVE_MS + " pause_ms=" + PAUSE_MS)
        host.toast("Тест джойстика через 6 с: закройте меню. Запомните X/Y сейчас. Будет 4 хода, X/Y после каждого")

        fun later(ms: Long, block: () -> Unit) {
            ui.postDelayed({ if (runId == myRun && active) block() }, ms)
        }

        // Калибровка: 4 одиночных хода режима B (цепочка), после каждого пауза 6 с, чтобы записать X/Y.
        val names = arrayOf("right", "right", "up", "down")
        val ruNames = arrayOf("вправо", "вправо", "вверх", "вниз")
        val dxs = floatArrayOf(1f, 1f, 0f, 0f)
        val dys = floatArrayOf(0f, 0f, -1f, 1f)
        val durs = longArrayOf(1500L, 3000L, 1500L, 3000L)
        val gap = 6000L
        var t = START_DELAY_MS
        for (i in 0 until 4) {
            val idx = i
            val tStart = t
            val dur = durs[idx]
            later(tStart) {
                if (idx == 0) FloatingService.panelPassThrough?.invoke(true)
                val ex = ax + dxs[idx] * travel
                val ey = ay + dys[idx] * travel
                log("MOVE" + (idx + 1) + " B_chain " + names[idx] + " dur_ms=" + dur + " anchor=" + ax.toInt() + "," + ay.toInt() +
                    " to=" + ex.toInt() + "," + ey.toInt())
                host.marker(ax.toInt(), ay.toInt(), Color.CYAN)
                acc.joyMove(ax, ay, ex, ey, 2, dur + 500L)
            }
            later(tStart + dur) {
                log("RELEASE" + (idx + 1) + " " + acc.joyStats())
                acc.joyRelease()
            }
            later(tStart + dur + 500L) {
                log("MOVE" + (idx + 1) + " END " + acc.joyStats())
                if (idx < 3) host.toast("Ход " + (idx + 1) + " из 4 закончен (" + ruNames[idx] + ", " + (dur / 1000.0) + " с). Запомните X/Y, следующий через 6 с")
            }
            t = tStart + dur + 500L + gap
        }
        later(t - gap + 100L) { finish(host, "done") }
    }

    override fun onDisable(host: FeatureHost) {
        runId++
        if (!active) return
        try { BotAccessibilityService.instance?.joyRelease() } catch (_: Throwable) {}
        finish(host, "disabled")
    }

    private fun finish(host: FeatureHost, why: String) {
        try { FloatingService.panelPassThrough?.invoke(false) } catch (_: Throwable) {}
        log("END why=" + why + " " + (BotAccessibilityService.instance?.joyStats() ?: "no_service"))
        try { BotAccessibilityService.instance?.joyLog = null } catch (_: Throwable) {}
        saveLog(host)
        active = false
        if (why == "done") host.toast("Тест джойстика закончен. Запомните X/Y. Лог в «Загрузки»: fishbot_berries_*.txt")
    }

    private fun saveLog(host: FeatureHost) {
        try {
            if (Build.VERSION.SDK_INT < 29) return
            val ctx = host.appContext()
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "fishbot_berries_" + System.currentTimeMillis() + ".txt")
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {}
    }
}

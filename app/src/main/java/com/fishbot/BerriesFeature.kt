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
        log("START v2 model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " screen=" + w + "x" + h +
            " anchor=" + ax.toInt() + "," + ay.toInt() + " r=" + (JOY_R * h).toInt() + " travel=" + travel.toInt() +
            " move_ms=" + MOVE_MS + " pause_ms=" + PAUSE_MS)
        host.toast("Тест джойстика через 6 с: закройте меню. Запомните X/Y сейчас. Будет 2 части")

        fun later(ms: Long, block: () -> Unit) {
            ui.postDelayed({ if (runId == myRun && active) block() }, ms)
        }

        val names = arrayOf("up", "down", "right", "left")
        val dxs = floatArrayOf(0f, 0f, 1f, -1f)
        val dys = floatArrayOf(-1f, 1f, 0f, 0f)
        val partMs = 4 * (MOVE_MS + PAUSE_MS) + PART_GAP_MS
        for (part in 0 until 2) {
            val swipe = part == 1
            val mode = if (swipe) "swipe" else "hold"
            val base = START_DELAY_MS + part * partMs
            for (i in 0 until 4) {
                val t = base + i * (MOVE_MS + PAUSE_MS)
                later(t) {
                    if (part == 0 && i == 0) FloatingService.panelPassThrough?.invoke(true)
                    val ex = ax + dxs[i] * travel
                    val ey = ay + dys[i] * travel
                    log("PART" + (part + 1) + " " + mode + " MOVE " + names[i] + " anchor=" + ax.toInt() + "," + ay.toInt() +
                        " to=" + ex.toInt() + "," + ey.toInt())
                    host.marker(ax.toInt(), ay.toInt(), Color.CYAN)
                    acc.joyMove(ax, ay, ex, ey, swipe, MOVE_MS + 500L)
                }
                later(t + MOVE_MS) {
                    log("PART" + (part + 1) + " RELEASE " + names[i] + " " + acc.joyStats())
                    acc.joyRelease()
                }
            }
            later(base + 4 * (MOVE_MS + PAUSE_MS)) {
                log("PART" + (part + 1) + " END " + acc.joyStats())
                if (part == 0) host.toast("Часть 1 закончена. Запомните X/Y, часть 2 через 6 с")
            }
        }
        later(START_DELAY_MS + partMs + 4 * (MOVE_MS + PAUSE_MS)) { finish(host, "done") }
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

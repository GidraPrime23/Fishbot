package com.fishbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Нажатия на экран. Тап - обычный жест. Удержание - цепочка коротких "продолжаемых" штрихов
 * (палец остаётся на экране), отпускание - завершающий штрих на ближайшей границе сегмента.
 */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
        private const val SEG_MS = 50L
        private const val JOY_SEG_MS = 250L
        private const val JOY_CANCEL_MAX = 5
    }

    private val main = Handler(Looper.getMainLooper())
    private var stroke: StrokeDescription? = null
    private var inFlight = false
    private var wantHold = false
    private var hx = 0f
    private var hy = 0f

    override fun onServiceConnected() {
        instance = this
        BotState.notifyChanged()
        CrashGuard.install(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        BotState.notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float, ms: Long = 60) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, ms)).build()
        dispatchGesture(g, null, null)
    }

    @Volatile private var pulseBusy = false
    private var pulseT = 0L
    private var pulseMs = 60L

    /** Одиночный короткий тап без удержания. false, если предыдущий ещё идёт. */
    fun pulse(x: Float, y: Float, ms: Long = 60): Boolean {
        if (pulseBusy && System.currentTimeMillis() - pulseT < pulseMs + 400) return false
        pulseBusy = true
        pulseT = System.currentTimeMillis(); pulseMs = ms
        return try {
            val p = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, ms)).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { pulseBusy = false }
                override fun onCancelled(gestureDescription: GestureDescription?) { pulseBusy = false }
            }, null)
            if (!ok) pulseBusy = false
            ok
        } catch (e: Throwable) {
            pulseBusy = false
            false
        }
    }

    /** true = держать палец на (x, y), false = отпустить. Можно вызывать из любого потока. */
    fun setHold(want: Boolean, x: Float, y: Float) {
        main.post {
            wantHold = want
            if (want) { hx = x; hy = y }
            try { pump() } catch (e: Throwable) { inFlight = false; stroke = null }
        }
    }

    private fun pump() {
        if (inFlight) return
        val p = Path().apply { moveTo(hx, hy) }
        val cur = stroke
        val next: StrokeDescription
        val cont: Boolean
        if (wantHold) {
            cont = true
            next = if (cur == null) StrokeDescription(p, 0, SEG_MS, true)
                   else cur.continueStroke(p, 0, SEG_MS, true)
        } else if (cur != null) {
            cont = false
            next = cur.continueStroke(p, 0, 1, false)
        } else {
            return
        }
        inFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    stroke = if (cont) next else null
                    if (wantHold || stroke != null) pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    inFlight = false
                    stroke = null
                    if (wantHold) pump()
                }
            },
            main
        )
        if (!ok) {
            inFlight = false
            stroke = null
        }
    }

    // ---------------------------------------------------------------- джойстик (ягоды, этап 3), версия p64
    // Каждый штрих самозавершающийся (willContinue = false): после него палец гарантированно отпущен,
    // залипнуть нечему. Пока joyWant и не наступил дедлайн, штрихи подаются подряд.
    // swipe = false: палец касается сразу в точке цели (ex, ey) и держится;
    // swipe = true: касание в якоре (ax, ay) и сдвиг к цели в пределах штриха.
    private var joyInFlight = false
    private var joyWant = false
    private var joySwipe = false
    private var joyAx = 0f
    private var joyAy = 0f
    private var joyEx = 0f
    private var joyEy = 0f
    private var joyUntil = 0L
    private var joyCancelRun = 0
    @Volatile private var joySent = 0
    @Volatile private var joyDone = 0
    @Volatile private var joyCancelled = 0
    @Volatile private var joyRejected = 0

    /** Приёмник строк лога джойстика (ставит BerriesFeature, выполняется в главном потоке). */
    @Volatile var joyLog: ((String) -> Unit)? = null

    private fun jlog(s: String) {
        try { joyLog?.invoke(s) } catch (_: Throwable) {}
    }

    fun joyStats(): String = "sent=$joySent done=$joyDone cancelled=$joyCancelled rejected=$joyRejected"

    fun joyReset() { joySent = 0; joyDone = 0; joyCancelled = 0; joyRejected = 0; joyCancelRun = 0 }

    /** Держать джойстик в сторону (ex, ey) не дольше maxHoldMs (дедлайн страхует от залипания). Повторный вызов меняет цель. */
    fun joyMove(ax: Float, ay: Float, ex: Float, ey: Float, swipe: Boolean, maxHoldMs: Long) {
        main.post {
            joyAx = ax; joyAy = ay; joyEx = ex; joyEy = ey
            joySwipe = swipe
            joyUntil = android.os.SystemClock.elapsedRealtime() + maxHoldMs
            joyWant = true
            joyCancelRun = 0
            jlog("move swipe=$swipe to=${ex.toInt()},${ey.toInt()} hold_ms=$maxHoldMs")
            try { joyPump() } catch (e: Throwable) { joyInFlight = false; jlog("pump error " + e) }
        }
    }

    /** Отпустить: новых штрихов не будет, идущий штрих закончится сам (до JOY_SEG_MS). Безопасно вызывать повторно. */
    fun joyRelease() {
        main.post {
            if (joyWant) jlog("release")
            joyWant = false
        }
    }

    private fun joyPump() {
        if (joyInFlight || !joyWant) return
        val left = joyUntil - android.os.SystemClock.elapsedRealtime()
        if (left <= 0L) {
            joyWant = false
            jlog("deadline")
            return
        }
        val dur = Math.max(40L, Math.min(JOY_SEG_MS, left))
        val sx = if (joySwipe) joyAx else joyEx
        val sy = if (joySwipe) joyAy else joyEy
        val ex = joyEx
        val ey = joyEy
        val p = Path()
        p.moveTo(sx, sy)
        if (Math.abs(ex - sx) > 1f || Math.abs(ey - sy) > 1f) p.lineTo(ex, ey)
        val n = ++joySent
        joyInFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(StrokeDescription(p, 0, dur, false)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyDone++
                    joyCancelRun = 0
                    jlog("g$n completed")
                    joyPump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyCancelled++
                    joyCancelRun++
                    jlog("g$n cancelled run=$joyCancelRun")
                    if (joyWant) {
                        if (joyCancelRun < JOY_CANCEL_MAX) {
                            main.postDelayed({ joyPump() }, 30L)
                        } else {
                            joyWant = false
                            jlog("stop: too many cancels in a row")
                        }
                    }
                }
            },
            main
        )
        if (!ok) {
            joyInFlight = false
            joyRejected++
            joyWant = false
            jlog("g$n rejected by dispatchGesture")
            return
        }
        jlog("g$n sent from=${sx.toInt()},${sy.toInt()} to=${ex.toInt()},${ey.toInt()} dur=$dur")
    }
}

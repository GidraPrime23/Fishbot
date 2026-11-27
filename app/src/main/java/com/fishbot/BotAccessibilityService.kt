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
        private const val JOY_SEG_MS = 50L
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

    // ---------------------------------------------------------------- джойстик (ягоды, этап 3)
    // Отдельная цепочка жестов, не связанная с tap/pulse/setHold: один палец держится на джойстике,
    // направление меняется продолжаемыми штрихами (идея и порядок как в setHold/pump, проверенных на ловле).
    private var joyStroke: StrokeDescription? = null
    private var joyInFlight = false
    private var joyWant = false
    private var joyAx = 0f
    private var joyAy = 0f
    private var joyEx = 0f
    private var joyEy = 0f
    private var joyCx = 0f
    private var joyCy = 0f
    @Volatile private var joySent = 0
    @Volatile private var joyDone = 0
    @Volatile private var joyCancelled = 0
    @Volatile private var joyRejected = 0

    fun joyStats(): String = "sent=$joySent done=$joyDone cancelled=$joyCancelled rejected=$joyRejected"

    fun joyReset() { joySent = 0; joyDone = 0; joyCancelled = 0; joyRejected = 0 }

    /** Касание джойстика в (ax, ay); палец уходит в (ex, ey) и остаётся там. Повторный вызов меняет направление. */
    fun joyMove(ax: Float, ay: Float, ex: Float, ey: Float) {
        main.post {
            joyAx = ax; joyAy = ay; joyEx = ex; joyEy = ey
            joyWant = true
            try { joyPump() } catch (e: Throwable) { joyInFlight = false; joyStroke = null }
        }
    }

    /** Отпустить джойстик (завершающий штрих на месте). Безопасно вызывать повторно. */
    fun joyRelease() {
        main.post {
            joyWant = false
            try { joyPump() } catch (e: Throwable) { joyInFlight = false; joyStroke = null }
        }
    }

    private fun joyPump() {
        if (joyInFlight) return
        val cur = joyStroke
        val p = Path()
        val next: StrokeDescription
        val cont: Boolean
        val endX: Float
        val endY: Float
        if (joyWant) {
            cont = true
            endX = joyEx
            endY = joyEy
            if (cur == null) {
                p.moveTo(joyAx, joyAy)
                if (Math.abs(endX - joyAx) > 1f || Math.abs(endY - joyAy) > 1f) p.lineTo(endX, endY)
                next = StrokeDescription(p, 0, JOY_SEG_MS, true)
            } else {
                p.moveTo(joyCx, joyCy)
                if (Math.abs(endX - joyCx) > 1f || Math.abs(endY - joyCy) > 1f) p.lineTo(endX, endY)
                next = cur.continueStroke(p, 0, JOY_SEG_MS, true)
            }
        } else if (cur != null) {
            cont = false
            endX = joyCx
            endY = joyCy
            p.moveTo(joyCx, joyCy)
            next = cur.continueStroke(p, 0, 1, false)
        } else {
            return
        }
        joyInFlight = true
        joySent++
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyDone++
                    if (cont) { joyStroke = next; joyCx = endX; joyCy = endY } else joyStroke = null
                    if (joyWant || joyStroke != null) joyPump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyCancelled++
                    joyStroke = null
                    if (joyWant && joyCancelled < 20) joyPump()
                }
            },
            main
        )
        if (!ok) {
            joyInFlight = false
            joyStroke = null
            joyRejected++
        }
    }
}

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
    }

    private val main = Handler(Looper.getMainLooper())
    private var stroke: StrokeDescription? = null
    private var inFlight = false
    private var wantHold = false
    private var hx = 0f
    private var hy = 0f

    override fun onServiceConnected() {
        instance = this
        CrashGuard.install(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
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

    /** Одиночный короткий тап без удержания. false, если предыдущий ещё идёт. */
    fun pulse(x: Float, y: Float, ms: Long = 60): Boolean {
        if (pulseBusy && System.currentTimeMillis() - pulseT < 500) return false
        pulseBusy = true
        pulseT = System.currentTimeMillis()
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
}

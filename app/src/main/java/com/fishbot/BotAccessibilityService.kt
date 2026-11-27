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
        private const val JOY_SEG_MS = 150L
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

    // ---------------------------------------------------------------- джойстик (ягоды, этап 3), версия p65
    // Джойстик в игре плавающий: центр основания там, где палец коснулся. Поэтому касание ВСЕГДА в якоре (ax, ay),
    // а отклонение создаётся сдвигом пальца к цели.
    // mode 1 (A): самозавершающиеся штрихи: быстрый заход к цели, потом дрожь +-JOY_JIT px у цели (палец держит отклонение).
    // mode 2 (B): цепочка: короткий первый штрих (willContinue = true), продолжения ненулевой длины, закрывающий штрих в конце.
    private val JOY_FIRST_MS = 80L
    private val JOY_JIT = 2f
    private val JOY_JIT_N = 80
    private var joyInFlight = false
    private var joyWant = false
    private var joyMode = 1
    private var joyAx = 0f
    private var joyAy = 0f
    private var joyEx = 0f
    private var joyEy = 0f
    private var joyCurX = 0f
    private var joyCurY = 0f
    private var joyUntil = 0L
    private var joyCancelRun = 0
    private var joyOpen = false
    private var joyStroke: StrokeDescription? = null
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

    fun joyReset() {
        joySent = 0; joyDone = 0; joyCancelled = 0; joyRejected = 0; joyCancelRun = 0
        joyOpen = false; joyStroke = null
    }

    /** Держать джойстик в сторону (ex, ey) не дольше maxHoldMs. mode: 1 = A (дрожь у цели), 2 = B (цепочка). Повторный вызов меняет цель. */
    fun joyMove(ax: Float, ay: Float, ex: Float, ey: Float, mode: Int, maxHoldMs: Long) {
        main.post {
            joyAx = ax; joyAy = ay; joyEx = ex; joyEy = ey
            joyMode = mode
            joyUntil = android.os.SystemClock.elapsedRealtime() + maxHoldMs
            joyWant = true
            joyCancelRun = 0
            jlog("move mode=$mode to=${ex.toInt()},${ey.toInt()} hold_ms=$maxHoldMs")
            try { joyPump() } catch (e: Throwable) { joyInFlight = false; jlog("pump error " + e) }
        }
    }

    /** Отпустить: новых штрихов не будет; открытая цепочка (режим B) закроется коротким штрихом. Безопасно вызывать повторно. */
    fun joyRelease() {
        main.post {
            if (joyWant) jlog("release")
            joyWant = false
            try { joyPump() } catch (e: Throwable) { joyInFlight = false; jlog("pump error " + e) }
        }
    }

    private fun joyDispatch(st: StrokeDescription, n: Int, willCont: Boolean, endX: Float, endY: Float, what: String) {
        joyInFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(st).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyDone++
                    joyCancelRun = 0
                    if (willCont) {
                        joyStroke = st; joyOpen = true; joyCurX = endX; joyCurY = endY
                    } else {
                        joyStroke = null; joyOpen = false
                    }
                    jlog("g$n completed")
                    joyPump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    joyInFlight = false
                    joyStroke = null
                    joyOpen = false
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
            joyStroke = null
            joyOpen = false
            joyRejected++
            joyWant = false
            jlog("g$n rejected by dispatchGesture")
            return
        }
        jlog("g$n sent $what")
    }

    private fun joyEnd(prev: StrokeDescription) {
        val n = ++joySent
        try {
            val p = Path()
            p.moveTo(joyCurX, joyCurY)
            p.lineTo(joyCurX + 1f, joyCurY)
            joyDispatch(prev.continueStroke(p, 0L, 20L, false), n, false, joyCurX + 1f, joyCurY, "END-of-chain dur=20")
        } catch (e: Throwable) {
            joyInFlight = false
            joyStroke = null
            joyOpen = false
            jlog("end error " + e)
        }
    }

    private fun joyPump() {
        if (joyInFlight) return
        val left = joyUntil - android.os.SystemClock.elapsedRealtime()
        if (joyWant && left <= 0L) {
            joyWant = false
            jlog("deadline")
        }
        if (!joyWant) {
            val open = joyStroke
            if (joyOpen && open != null) joyEnd(open)
            return
        }
        val dur = Math.max(40L, Math.min(JOY_SEG_MS, left))
        val n = ++joySent
        try {
            if (joyMode == 2) {
                val st0 = joyStroke
                val cont = joyOpen && st0 != null
                val sx = if (cont) joyCurX else joyAx
                val sy = if (cont) joyCurY else joyAy
                val far = Math.abs(sx - joyEx) > 0.5f || Math.abs(sy - joyEy) > 0.5f
                val tx = if (far) joyEx else joyEx + 1f
                val ty = if (far) joyEy else joyEy + 1f
                val segMs = if (cont) dur else Math.min(dur, JOY_FIRST_MS)
                val willCont = left > segMs
                val p = Path()
                p.moveTo(sx, sy)
                p.lineTo(tx, ty)
                val st = if (st0 != null && cont) st0.continueStroke(p, 0L, segMs, willCont) else StrokeDescription(p, 0L, segMs, willCont)
                joyDispatch(st, n, willCont, tx, ty, "B cont=$cont from=${sx.toInt()},${sy.toInt()} to=${tx.toInt()},${ty.toInt()} dur=$segMs willContinue=$willCont")
            } else {
                val p = Path()
                p.moveTo(joyAx, joyAy)
                p.lineTo(joyEx, joyEy)
                for (k in 0 until JOY_JIT_N) {
                    val s = if (k % 2 == 0) JOY_JIT else -JOY_JIT
                    p.lineTo(joyEx + s, joyEy + s)
                }
                joyDispatch(StrokeDescription(p, 0L, dur, false), n, false, joyEx, joyEy, "A from=${joyAx.toInt()},${joyAy.toInt()} to=${joyEx.toInt()},${joyEy.toInt()} dur=$dur jitter=$JOY_JIT_N")
            }
        } catch (e: Throwable) {
            joyInFlight = false
            joyStroke = null
            joyOpen = false
            joyWant = false
            jlog("pump error " + e)
        }
    }
}

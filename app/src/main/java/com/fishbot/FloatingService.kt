package com.fishbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Плавающая панель: состояние, таймер, пуск/пауза, калибровка кнопки рыбалки. Держит захват экрана. */
class FloatingService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile
        var running = false
        @Volatile
        var panelPassThrough: ((Boolean) -> Unit)? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var loop: BotLoop? = null

    private var root: LinearLayout? = null
    private var calibView: View? = null
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPlay: TextView

    private var startedAt = 0L
    private var accumulated = 0L
    private var catches = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        CrashGuard.install(applicationContext)
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        if (!Gate.isUnlocked(this) || !License.isActive(this)) { stopSelf(); return START_NOT_STICKY }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)
        projection = p
        grabber = ScreenGrabber(this, p)
        running = true
        License.startHeartbeat(this) { ui.post { stopSelf() } }
        showBubble()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { BotAccessibilityService.instance?.setHold(false, 0f, 0f) } catch (_: Throwable) {}
        try { loop?.stop() } catch (_: Throwable) {}
        panelPassThrough = null
        try { root?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { calibView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { grabber?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val ch = "fishbot"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(ch, "FishBot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("FishBot запущен")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ панель

    private fun showBubble() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE61B1F27.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        tvTime = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        tvState = TextView(this).apply { text = "готов"; setTextColor(0xFF9AA4B2.toInt()); textSize = 11f; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setLines(4) }
        tvPlay = TextView(this).apply {
            text = "▶"; setTextColor(Color.BLACK); textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6EE7B7.toInt()) }
            setOnClickListener { toggle() }
        }
        val tvTest = TextView(this).apply {
            text = "⦿ тест тапа"; setTextColor(0xFF9AA4B2.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { testTap() }
        }
        val tvCal = TextView(this).apply {
            text = "⌖ кнопка"; setTextColor(0xFF9AA4B2.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { calibrate() }
        }
        val tvClose = TextView(this).apply {
            text = "✕ выход"; setTextColor(0xFF9AA4B2.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { stopSelf() }
        }
        panel.addView(tvTime)
        panel.addView(tvState, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(tvPlay, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(6) })
        panel.addView(tvTest)
        panel.addView(tvCal)
        panel.addView(tvClose)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(90) }

        // перетаскивание за надпись времени
        tvTime.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(panel, lp)
                    }
                }
                return true
            }
        })
        panelPassThrough = { on ->
            ui.post {
                try {
                    val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    lp.flags = if (on) (lp.flags or f) else (lp.flags and f.inv())
                    wm.updateViewLayout(panel, lp)
                } catch (_: Exception) {}
            }
        }
        root = panel
        wm.addView(panel, lp)
        if (!prefs.hasButton) setState("нажмите ⌖ и коснитесь кнопки рыбалки")
    }

    private fun setState(s: String) { tvState.text = s }

    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** Кружок поверх экрана в точке (x, y): видно, куда именно нажимает бот. */
    private fun marker(x: Int, y: Int, color: Int) {
        val r = dp(28)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(4), color)
                setColor(0x33FFFFFF)
            }
        }
        val lp = WindowManager.LayoutParams(
            2 * r, 2 * r,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - r
            this.y = y - r
        }
        try {
            wm.addView(v, lp)
            ui.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, 900)
        } catch (_: Exception) {}
    }

    /** Проверка без запуска бота: один тап по сохранённой точке + зелёный кружок. */
    private fun testTap() {
        if (!prefs.hasButton) { Toast.makeText(this, "Сначала ⌖ калибровка", Toast.LENGTH_SHORT).show(); return }
        val (w, h) = realSize()
        if (w < h) { Toast.makeText(this, "Поверните телефон в ландшафт", Toast.LENGTH_SHORT).show(); return }
        val x = (prefs.btnX * w).toInt()
        val y = (prefs.btnY * h).toInt()
        marker(x, y, Color.GREEN)
        setState("тап: $x,$y из ${w}×$h")
        BotAccessibilityService.instance?.tap(x.toFloat(), y.toFloat())
    }

    private fun elapsedMs() = accumulated + (if (loop?.active == true) SystemClock.elapsedRealtime() - startedAt else 0L)

    private val tick = object : Runnable {
        override fun run() {
            val s = elapsedMs() / 1000
            tvTime.text = String.format("%d:%02d", s / 60, s % 60)
            if (loop?.active == true) ui.postDelayed(this, 1000)
        }
    }

    // ----------------------------------------------------------------- управление

    private fun toggle() {
        if (loop?.active == true) pause() else resume()
    }

    private fun resume() {
        val g = grabber ?: return
        val l = BotLoop(
            g, prefs,
            onState = { s -> ui.post { setState(s) } },
            onStop = { why -> ui.post { onBotStopped(why) } },
            onCatch = { ui.post { catches++; tvTime.contentDescription = "улов $catches" } },
            onTap = { x, y -> ui.post { marker(x, y, Color.RED) } }
        )
        loop = l
        startedAt = SystemClock.elapsedRealtime()
        l.start()
        tvPlay.text = "❚❚"
        ui.post(tick)
    }

    private fun pause() {
        accumulated = elapsedMs()
        loop?.stop()
        tvPlay.text = "▶"
        setState("пауза · мини-игр: $catches")
    }

    private fun onBotStopped(why: String) {
        accumulated = elapsedMs()
        tvPlay.text = "▶"
        setState(why)
        if (prefs.alarm) alarm()
    }

    private fun alarm() {
        try {
            val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            r.play()
            ui.postDelayed({ try { r.stop() } catch (_: Exception) {} }, 6000)
        } catch (_: Exception) {}
    }

    // ----------------------------------------------------------------- калибровка

    /** Один раз показываем, где на экране кнопка рыбалки: затемнение на весь экран, ждём касание. */
    private fun calibrate() {
        if (calibView != null) return
        val v = FrameLayout(this).apply { setBackgroundColor(0xAA000000.toInt()) }
        v.addView(
            TextView(this).apply {
                text = "Коснитесь кнопки рыбалки\n(в игре, в правом нижнем углу)"
                setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                if (dm.widthPixels < dm.heightPixels) {
                    Toast.makeText(this, "Поверните телефон в ландшафт (как в игре)", Toast.LENGTH_LONG).show()
                } else {
                    prefs.btnX = e.rawX / dm.widthPixels
                    prefs.btnY = e.rawY / dm.heightPixels
                    Toast.makeText(this, "Кнопка сохранена", Toast.LENGTH_SHORT).show()
                    setState("кнопка: ${e.rawX.toInt()},${e.rawY.toInt()} из ${dm.widthPixels}×${dm.heightPixels}")
                    marker(e.rawX.toInt(), e.rawY.toInt(), Color.CYAN)
                }
                try { wm.removeView(v) } catch (_: Exception) {}
                calibView = null
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        calibView = v
        wm.addView(v, lp)
    }
}

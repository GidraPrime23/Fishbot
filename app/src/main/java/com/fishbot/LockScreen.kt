package com.fishbot

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Экран ввода пароля. Пока он на экране, никакого меню и статусов не создаётся. */
object LockScreen {

    /** Экран ввода ключа лицензии. Показывается после пароля, пока ключ не активирован. */
    fun showKey(a: Activity) {
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#DFF3FB"))
        }
        val title = TextView(a).apply {
            text = "Введите ключ лицензии"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#123A5C"))
        }
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "Ключ"
            setSingleLine()
        }
        val btn = Button(a).apply { text = "Активировать"; isAllCaps = false }
        val msg = TextView(a).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B00020"))
        }
        col.addView(title)
        col.addView(et)
        col.addView(btn)
        col.addView(msg)
        a.setContentView(col)

        btn.setOnClickListener {
            val raw = et.text.toString().trim()
            btn.isEnabled = false
            msg.text = "Проверка..."
            Thread {
                val r = License.activate(a, raw)
                a.runOnUiThread {
                    if (r == License.Res.OK) {
                        a.recreate()
                    } else {
                        btn.isEnabled = true
                        msg.text = when (r) {
                            License.Res.BAD_KEY -> "Неверный ключ"
                            License.Res.BLOCKED -> "Ключ заблокирован"
                            License.Res.USED -> "Ключ уже привязан к другому устройству"
                            else -> "Нет связи"
                        }
                    }
                }
            }.start()
        }
    }
    fun show(a: Activity) {
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#DFF3FB"))
        }
        val title = TextView(a).apply {
            text = "Введите пароль"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#123A5C"))
        }
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Пароль"
            setSingleLine()
        }
        val btn = Button(a).apply { text = "Войти"; isAllCaps = false }
        val msg = TextView(a).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B00020"))
        }
        col.addView(title)
        col.addView(et)
        col.addView(btn)
        col.addView(msg)
        a.setContentView(col)

        val h = Handler(Looper.getMainLooper())
        lateinit var tick: Runnable
        tick = Runnable {
            val w = Gate.waitSeconds(a)
            if (w > 0) {
                btn.isEnabled = false
                msg.text = "Слишком много попыток. Подождите $w с"
                h.postDelayed(tick, 1000)
            } else {
                btn.isEnabled = true
                if (msg.text.startsWith("Слишком")) msg.text = ""
            }
        }
        btn.setOnClickListener {
            if (Gate.tryUnlock(a, et.text.toString())) {
                h.removeCallbacks(tick)
                a.recreate()
            } else {
                et.setText("")
                msg.text = "Неверный пароль"
                h.removeCallbacks(tick)
                tick.run()
            }
        }
        tick.run()
    }
}

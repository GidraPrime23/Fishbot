package com.fishbot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tvOverlay: TextView
    private lateinit var tvAcc: TextView
    private lateinit var etMiss: EditText
    private lateinit var cbAlarm: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Gate.isUnlocked(this)) { LockScreen.show(this); return }
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#DFF3FB"), Color.parseColor("#BFE6F5"))
            )
        }
        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 22f; setPadding(0, pad / 2, 0, pad / 2)
            setTextColor(Color.parseColor("#123A5C")); typeface = Typeface.DEFAULT_BOLD
        }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.DKGRAY) }
        fun footer(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(Color.parseColor("#2F4858"))
        }
        fun actionButton(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.parseColor("#1C86C8"))
            }
            setOnClickListener { onClick() }
        }

        col.addView(title("FishBot — автоматическая рыбалка"))

        tvOverlay = note("")
        col.addView(tvOverlay)
        col.addView(actionButton("1. Разрешить поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        tvAcc = note("")
        col.addView(tvAcc)
        col.addView(actionButton("2. Включить службу спецвозможностей FishBot") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        cbAlarm = CheckBox(this).apply { text = "Сирена при остановке бота"; isChecked = prefs.alarm }
        col.addView(cbAlarm)
        col.addView(note("Остановка после N забросов подряд без поклёвки (сломана удочка, нет наживки):"))
        etMiss = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.maxMiss.toString())
            gravity = Gravity.CENTER
        }
        col.addView(etMiss)

        col.addView(actionButton("3. Запустить (разрешить запись экрана)") { start() })
        col.addView(note(
            "\nПосле запуска откройте игру, на панели нажмите ⊕ и коснитесь кнопки рыбалки " +
            "(один раз), затем ▶. Игра должна быть в ландшафте, персонаж у воды с удочкой."
        ))

        col.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#331C86C8"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = pad
            }
        })
        col.addView(footer(
            "Автор: HFL - Gidra (@gidrantNeContritsia)\n\n" +
            "Плагиат и распространение без разрешения автора запрещены.\n" +
            "Ответственность за блокировку аккаунта лежит на пользователе.\n\n" +
            "Правила:\n" +
            "1. Бот работает через Специальные возможности и запись экрана — это нужно только для автоловли, никакие данные никуда не уходят.\n" +
            "2. Используете на свой риск: автоматизация может нарушать правила игры и привести к бану.\n" +
            "3. Не выдавать код за своё и не публиковать свои версии без указания автора.\n" +
            "4. О багах — автору, а не в переделки на стороне."
        ))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        if (!Gate.isUnlocked(this)) return
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "✓ Поверх окон: разрешено" else "✗ Поверх окон: не разрешено"
        val acc = BotAccessibilityService.instance != null
        tvAcc.text = if (acc) "✓ Спецвозможности: включены"
        else "✗ Спецвозможности: выключены (на Android 13+ для установленного вручную приложения " +
            "сначала разрешите «ограниченные настройки» в Настройки → Приложения → FishBot → ⋮)"
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { toast("Сначала шаг 1"); return }
        if (BotAccessibilityService.instance == null) { toast("Сначала шаг 2"); return }
        prefs.alarm = cbAlarm.isChecked
        prefs.maxMiss = etMiss.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 3
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!Gate.isUnlocked(this)) return
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, FloatingService::class.java)
                .putExtra(FloatingService.EXTRA_CODE, resultCode)
                .putExtra(FloatingService.EXTRA_DATA, data)
            startForegroundService(i)
            moveTaskToBack(true)
        } else if (requestCode == 100) {
            toast("Без записи экрана бот не видит игру")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

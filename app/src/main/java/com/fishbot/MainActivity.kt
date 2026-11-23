package com.fishbot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 22f; setPadding(0, pad / 2, 0, pad / 2) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.DKGRAY) }

        col.addView(title("FishBot — автоматическая рыбалка"))

        tvOverlay = note("")
        col.addView(tvOverlay)
        col.addView(Button(this).apply {
            text = "1. Разрешить поверх других окон"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        tvAcc = note("")
        col.addView(tvAcc)
        col.addView(Button(this).apply {
            text = "2. Включить службу спецвозможностей FishBot"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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

        col.addView(Button(this).apply {
            text = "3. Запустить (разрешить запись экрана)"
            setOnClickListener { start() }
        })
        col.addView(note(
            "\nПосле запуска откройте игру, на панели нажмите ⌖ и коснитесь кнопки рыбалки " +
            "(один раз), затем ▶. Игра должна быть в ландшафте, персонаж у воды с удочкой."
        ))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "✔ Поверх окон: разрешено" else "✘ Поверх окон: не разрешено"
        val acc = BotAccessibilityService.instance != null
        tvAcc.text = if (acc) "✔ Спецвозможности: включены"
        else "✘ Спецвозможности: выключены (на Android 13+ для установленного вручную приложения " +
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

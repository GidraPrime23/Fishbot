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
        if (!License.isActive(this)) { LockScreen.showKey(this); return }
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

        col.addView(title("FishBot — автоматическая рыбалка").apply { setOnLongClickListener { prefs.logs = !prefs.logs; toast(if (prefs.logs) "Логи: вкл" else "Логи: выкл"); true } })

        tvOverlay = note("")
        col.addView(tvOverlay)
        col.addView(actionButton("1. Разрешить поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        tvAcc = note("")
        col.addView(tvAcc)
        col.addView(note("Если переключатель службы включён, а выше написано «выключены» — служба слетела (так бывает после смахивания приложения из последних). Настройки → Спецвозможности → FishBot → выключите и включите переключатель."))
        col.addView(actionButton("2. Включить службу спецвозможностей FishBot") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(note("Если не получается включить: Специальные возможности → Скачанные приложения → FishBot, зайдите в настройки приложения (Настройки → Приложения → FishBot) и пролистайте вниз до пункта «Разрешить запрещённые действия/настройки». Это позволит включить службу, ничего лишнего при этом не происходит."))

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
        col.addView(actionButton("4. Остановить") { stopService(Intent(this, FloatingService::class.java)); toast("Бот остановлен") })
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
            "1. Бот работает через Специальные возможности и запись экрана — это нужно только для автоловли, картинка экрана обрабатывается только на телефоне; на сервер уходят лишь код устройства и время проверки лицензии.\n" +
            "2. Используете на свой риск: автоматизация может нарушать правила игры и привести к блокировке аккаунта. Автор гарантий не даёт.\n" +
            "3. Не выдавать код за своё и не публиковать свои версии без указания автора.\n" +
            "4. О багах — автору, а не в переделки на стороне."
        ))

        col.addView(TextView(this).apply { text = "ВНИМАТЕЛЬНО ЧИТАЙТЕ ИНФОРМАЦИЮ ВНИЗУ!!!"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#B00020")); setPadding(0, pad, 0, pad / 2) })
        col.addView(footer(
        "Зашли в приложение = ознакомились со всем и согласны.\n\n" +
        "ЦЕНА\n" +
        "Подписка: 300₽ или 600Г. Это официальная стоимость, только лично от автора.\n\n" +
        "ДРУЗЬЯМ\n" +
        "За каждого приведённого друга — 20% с его оплаты (в зависимости от того, чем он оплатил). Если друг купит сам, выгоды нет, хыхы.\n\n" +
        "УСЛОВИЯ\n" +
        "• Оплачивая подписку (заходя в приложение), вы соглашаетесь, что претензий к автору нет и он не отвечает за блокировку аккаунта.\n" +
        "• Возврата нет.\n" +
        "• 1 ключ = 1 устройство, навсегда. Не передавайте ключ и пароль другим.\n" +
        "• Переустановка того же APK на тот же телефон — нормально. Смена или сброс телефона: напишите автору, он сбросит привязку.\n" +
        "• Не пересобирайте, не меняйте и не передавайте APK: ключ привязан к устройству и подписи оригинального файла, в изменённой версии он не заработает.\n" +
        "• Нужен интернет: лицензия проверяется во время работы. Надпись внизу «Лицензия проверена, успешно» на пару секунд — это нормально.\n" +
        "• Не покупайте ключи, подписки и копии ни у кого, кроме автора. Только Telegram @gidrantNeContritsia. Аналогов нет.\n" +
        "• Бот не вмешивается в файлы и память игры, но гарантий от блокировки никто дать не может.\n\n" +
        "СОВЕТ\n" +
        "• Не смахивайте FishBot из последних приложений (квадрат внизу): на некоторых телефонах (Xiaomi/HyperOS) это отключает службу.\n" +
        "• Из игры выходите кружком «Домой» по центру нижней панели, а при жестах — коротким свайпом вверх от нижнего края.\n" +
        "• Если служба слетела (в Настройках «работает некорректно» или в приложении «Спецвозможности: выключены» при включённом переключателе) — выключите и включите переключатель FishBot в Спецвозможностях.\n" +
        "• Если FishBot не включается в Спецвозможностях: Настройки → Приложения → FishBot → три точки → «Разрешить ограниченные настройки», затем включите службу снова.\n" +
        "• Если бот остановился с сиреной: проверьте удочку и наживку.\n\n" +
        "Приятного пользования и времяпрепровождения в игре :3\n" +
        "Окурок, сорямба, живём как можем."
        ))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private var resumeAt = 0L

    override fun onResume() {
        super.onResume()
        if (!Gate.isUnlocked(this) || !License.isActive(this)) return
        resumeAt = System.currentTimeMillis()
        val self = java.lang.ref.WeakReference(this)
        BotState.listener = { self.get()?.let { if (!it.isFinishing && !it.isDestroyed) it.refreshStatus() } }
        refreshStatus()
        tvAcc.postDelayed({ if (!isFinishing && !isDestroyed) refreshStatus() }, 6200)
    }

    private fun refreshStatus() {
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "✓ Поверх окон: разрешено" else "✗ Поверх окон: не разрешено"
        val acc = BotAccessibilityService.instance != null
        val listed = try {
            (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .contains("com.fishbot/")
        } catch (e: Exception) { false }
        tvAcc.text = if (acc) "✓ Спецвозможности: включены"
        else if (listed && System.currentTimeMillis() - resumeAt < 6000) "⏳ Служба подключается… подождите несколько секунд"
        else if (listed) "⚠ Служба слетела: переключатель включён, но служба не работает. Настройки → Спецвозможности → FishBot → выключите и включите переключатель. Не смахивайте FishBot из последних."
        else "✗ Спецвозможности: выключены (на Android 13+ для установленного вручную приложения " +
            "сначала разрешите «ограниченные настройки» в Настройки → Приложения → FishBot → ⋮)"
        tvAcc.append(lastExitInfo().let { if (it.isEmpty()) "" else "\n" + it })
    }

    private var leavingToSystem = false

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: android.os.Bundle?) {
        leavingToSystem = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStop() {
        super.onStop()
        if (leavingToSystem) { leavingToSystem = false; return }
        if (!isChangingConfigurations) finishAndRemoveTask()
    }

    private fun lastExitInfo(): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return ""
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val e = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
                ?: return "Последний выход процесса: нет данных"
            val r = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force-stop/смахивание)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (убит сигналом)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (падение)"
                android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
                else -> "код ${e.reason}"
            }
            val t = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(e.timestamp))
            "Последний выход процесса: $r, $t (${e.description ?: "-"})"
        } catch (ex: Exception) { "" }
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
        if (!Gate.isUnlocked(this) || !License.isActive(this)) return
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

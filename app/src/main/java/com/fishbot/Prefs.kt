package com.fishbot

import android.content.Context

/** Настройки бота. Координаты кнопки хранятся в долях экрана (ландшафт). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fishbot", Context.MODE_PRIVATE)

    var btnX: Float
        get() = sp.getFloat("bx", -1f)
        set(v) { sp.edit().putFloat("bx", v).apply() }
    var btnY: Float
        get() = sp.getFloat("by", -1f)
        set(v) { sp.edit().putFloat("by", v).apply() }
    val hasButton: Boolean get() = btnX in 0f..1f && btnY in 0f..1f

    var alarm: Boolean
        get() = sp.getBoolean("alarm", true)
        set(v) { sp.edit().putBoolean("alarm", v).apply() }
    /** Сколько забросов подряд без поклёвки до остановки (сломана удочка / нет наживки / не у воды). */
    var maxMiss: Int
        get() = sp.getInt("maxMiss", 3)
        set(v) { sp.edit().putInt("maxMiss", v).apply() }
    var biteTimeoutSec: Int
        get() = sp.getInt("biteTimeout", 30)
        set(v) { sp.edit().putInt("biteTimeout", v).apply() }
}

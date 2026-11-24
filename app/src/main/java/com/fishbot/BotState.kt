package com.fishbot

import android.os.Handler
import android.os.Looper

object BotState {
    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun notifyChanged() {
        main.post { try { listener?.invoke() } catch (e: Exception) { } }
    }
}

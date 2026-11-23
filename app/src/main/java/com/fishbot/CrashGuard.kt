package com.fishbot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object CrashGuard {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (t == Looper.getMainLooper().thread) {
                old?.uncaughtException(t, e)
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(app, "FishBot ошибка: " + e, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}

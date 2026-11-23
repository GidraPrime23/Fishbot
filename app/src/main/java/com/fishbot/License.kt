package com.fishbot

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Лицензия: ключ = ID документа keys/<ключ> в Firestore, привязка к одному устройству.
 * Сеть только в отдельных потоках. BotLoop/Vision/Pilot не затрагиваются.
 */
object License {
    private const val API_KEY = "AIzaSyDua1c1f896jg-5bPswjCetyDxYDmk89kY"
    private const val DOCS = "projects/fbl-store-ed433/databases/(default)/documents"
    private const val BASE = "https://firestore.googleapis.com/v1/$DOCS"
    private const val PREFS = "lic"
    private const val K_KEY = "k"
    private const val PERIOD_MS = 30_000L
    private const val MAX_FAILS = 3

    enum class Res { OK, BAD_KEY, BLOCKED, USED, NET }

    @Volatile private var hb: Thread? = null

    fun isActive(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_KEY, null) != null

    private fun revoke(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_KEY).apply()
    }

    private fun deviceHash(c: Context): String {
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val s = id + "|" + Build.MANUFACTURER + "|" + Build.MODEL
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private class Resp(val code: Int, val body: String)

    private fun call(url: String, post: String?): Resp? {
        return try {
            val cn = URL(url).openConnection() as HttpURLConnection
            cn.connectTimeout = 8000
            cn.readTimeout = 8000
            if (post != null) {
                cn.requestMethod = "POST"
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json")
                cn.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = cn.responseCode
            val st = if (code in 200..299) cn.inputStream else cn.errorStream
            val body = st?.bufferedReader()?.use { it.readText() } ?: ""
            cn.disconnect()
            Resp(code, body)
        } catch (e: Exception) {
            null
        }
    }

    /** Привязка/проверка: пишет device + серверное время lastSeen. */
    private fun touch(c: Context, key: String): Res {
        val body = "{\"writes\":[{\"update\":{\"name\":\"$DOCS/keys/$key\"," +
            "\"fields\":{\"device\":{\"stringValue\":\"${deviceHash(c)}\"}}}," +
            "\"updateMask\":{\"fieldPaths\":[\"device\"]}," +
            "\"updateTransforms\":[{\"fieldPath\":\"lastSeen\",\"setToServerValue\":\"REQUEST_TIME\"}]," +
            "\"currentDocument\":{\"exists\":true}}]}"
        val r = call("$BASE:commit?key=$API_KEY", body) ?: return Res.NET
        return when (r.code) {
            200 -> Res.OK
            403, 404 -> Res.BLOCKED
            else -> Res.NET
        }
    }

    /** Активация ключа. ТОЛЬКО из фонового потока. */
    fun activate(c: Context, raw: String): Res {
        val key = raw.trim()
        if (!Regex("[A-Za-z0-9]{20}").matches(key)) return Res.BAD_KEY
        val r = call("$BASE/keys/$key?key=$API_KEY", null) ?: return Res.NET
        if (r.code == 404) return Res.BAD_KEY
        if (r.code == 403) return Res.BLOCKED
        if (r.code != 200) return Res.NET
        val f = try {
            JSONObject(r.body).getJSONObject("fields")
        } catch (e: Exception) {
            return Res.NET
        }
        val blocked = f.optJSONObject("blocked")?.optBoolean("booleanValue", false) ?: false
        val dev = f.optJSONObject("device")?.optString("stringValue", "") ?: ""
        if (blocked) return Res.BLOCKED
        if (dev.isNotEmpty() && dev != deviceHash(c)) return Res.USED
        val t = touch(c, key)
        if (t == Res.OK) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_KEY, key).apply()
        }
        return t
    }

    /** Фоновая проверка раз в 30 с, пока служба работает. onDenied зовётся из фонового потока. */
    fun startHeartbeat(c: Context, onDenied: () -> Unit) {
        if (hb?.isAlive == true) return
        val app = c.applicationContext
        val t = Thread { loop(app, onDenied) }
        t.isDaemon = true
        hb = t
        t.start()
    }

    private fun loop(app: Context, onDenied: () -> Unit) {
        var fails = 0
        var first = true
        while (FloatingService.running) {
            val key = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_KEY, null)
            if (key == null) { onDenied(); return }
            val r = touch(app, key)
            if (r == Res.OK) {
                fails = 0; first = false
            } else if (r == Res.BLOCKED) {
                revoke(app); onDenied(); return
            } else {
                fails++
                if (first || fails >= MAX_FAILS) { onDenied(); return }
            }
            try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { return }
        }
    }
}

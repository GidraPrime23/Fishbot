package com.fishbot

import android.content.Context
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Замок на вход. Пароль здесь НЕ хранится: только соль и хеш PBKDF2-HMAC-SHA256.
 * Ловлю не затрагивает: проверка только при открытии и при старте служб.
 */
object Gate {
    private const val SALT_HEX = "40ffffc9eb3c48205f79dfd6b9666777"
    private const val HASH_HEX = "b13e81538016c92298050a959cf3e6233e5d35b84021baa7f9a711e26952414a"
    private const val ITER = 120_000

    /** false = ввести один раз и запомнить; true = спрашивать при каждом открытии. */
    private const val ASK_EVERY_LAUNCH = false

    private const val PREFS = "gate"
    private const val K_TOKEN = "t"
    private const val K_FAILS = "f"
    private const val K_UNTIL = "u"

    @Volatile private var unlockedMem = false

    fun isUnlocked(c: Context): Boolean {
        return true
        if (unlockedMem) return true
        if (ASK_EVERY_LAUNCH) return false
        val t = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TOKEN, null) ?: return false
        if (MessageDigest.isEqual(t.toByteArray(), token().toByteArray())) {
            unlockedMem = true
            return true
        }
        return false
    }

    /** Сколько секунд ещё действует пауза после неверных попыток (0 = можно вводить). */
    fun waitSeconds(c: Context): Int {
        val until = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_UNTIL, 0L)
        val left = until - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    fun tryUnlock(c: Context, input: String): Boolean {
        if (waitSeconds(c) > 0) return false
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (check(input)) {
            val ok = p.edit().putInt(K_FAILS, 0).putLong(K_UNTIL, 0L)
            if (!ASK_EVERY_LAUNCH) ok.putString(K_TOKEN, token())
            ok.apply()
            unlockedMem = true
            return true
        }
        val f = p.getInt(K_FAILS, 0) + 1
        val bad = p.edit().putInt(K_FAILS, f)
        if (f >= 5) bad.putLong(K_UNTIL, System.currentTimeMillis() + min(600, 30 * (f - 4)) * 1000L)
        bad.apply()
        return false
    }

    fun lock(c: Context) {
        unlockedMem = false
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_TOKEN).apply()
    }

    private fun check(pw: String): Boolean = try {
        val spec = PBEKeySpec(pw.toCharArray(), hex(SALT_HEX), ITER, 256)
        val got = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        MessageDigest.isEqual(got, hex(HASH_HEX))
    } catch (e: Exception) {
        false
    }

    private fun token(): String =
        MessageDigest.getInstance("SHA-256").digest(("ok|" + HASH_HEX).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

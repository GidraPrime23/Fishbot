package com.fishbot

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object FlightLog {
    private val sb = StringBuilder()

    @Synchronized
    fun start() {
        sb.setLength(0)
        sb.append("t_ms,marker,zTop,zBot,v,p,cmd,dt_ms,st,bands,zk\n")
    }

    @Synchronized
    fun row(line: String) {
        if (sb.length < 2000000) sb.append(line).append('\n')
    }

    @Synchronized
    fun save(ctx: Context) {
        if (sb.length < 80) return
        if (Build.VERSION.SDK_INT < 29) return
        if (!ctx.getSharedPreferences("fishbot", Context.MODE_PRIVATE).getBoolean("logs", false)) return
        try {
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "fishbot_log_" + System.currentTimeMillis() + ".csv")
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            prune(ctx, 50)
        } catch (e: Throwable) {
        }
    }

    // оставляет последние keep файлов fishbot_log_*.csv; ошибки не влияют на работу бота
    private fun prune(ctx: Context, keep: Int) {
        try {
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val found = ArrayList<Pair<String, android.net.Uri>>()
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf("fishbot_log_%.csv"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    found.add(Pair(c.getString(1), ContentUris.withAppendedId(base, c.getLong(0))))
                }
            }
            found.sortByDescending { it.first }
            for (i in keep until found.size) {
                ctx.contentResolver.delete(found[i].second, null, null)
            }
        } catch (e: Throwable) {
        }
    }
}

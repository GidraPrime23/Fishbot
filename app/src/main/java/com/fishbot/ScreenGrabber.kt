package com.fishbot

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager

/** Захват экрана через MediaProjection. Размер подстраивается под текущую ориентацию. */
class ScreenGrabber(private val ctx: Context, private val projection: MediaProjection) {

    var width = 0
        private set
    var height = 0
        private set

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var cur: Image? = null

    private fun metrics(): DisplayMetrics {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    /** Вызывать перед работой и периодически: пересоздаёт приёмник кадров при повороте экрана. */
    @Synchronized
    fun ensure() {
        val dm = metrics()
        if (dm.widthPixels == width && dm.heightPixels == height && reader != null) return
        width = dm.widthPixels
        height = dm.heightPixels
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val d = display
        if (d == null) {
            display = projection.createVirtualDisplay(
                "fishbot", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, null
            )
        } else {
            d.resize(width, height, dm.densityDpi)
            d.surface = newReader.surface
        }
        cur?.close()
        cur = null
        reader?.close()
        reader = newReader
    }

    /** Новый кадр или null, если нового пока нет. Предыдущий кадр после этого вызова недействителен. */
    @Synchronized
    fun poll(): Image? {
        val n = reader?.acquireLatestImage() ?: return null
        cur?.close()
        cur = n
        return n
    }

    /** Последний полученный кадр (опрашивает новый, если появился). */
    @Synchronized
    fun latest(): Image? {
        poll()
        return cur
    }

    /** Прямоугольник кадра как массив ARGB (строка за строкой). */
    fun region(img: Image, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val cx = x0.coerceIn(0, img.width - 1)
        val cw = minOf(w, img.width - cx)
        val ch = minOf(h, img.height - y0)
        val out = IntArray(cw * ch)
        val row = ByteArray(cw * ps)
        for (y in 0 until ch) {
            buf.position((y0 + y) * rs + cx * ps)
            buf.get(row, 0, cw * ps)
            var o = y * cw
            var i = 0
            for (x in 0 until cw) {
                val r = row[i].toInt() and 255
                val g = row[i + 1].toInt() and 255
                val b = row[i + 2].toInt() and 255
                out[o++] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                i += ps
            }
        }
        return out
    }

    @Synchronized
    fun release() {
        cur?.close(); cur = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection.stop()
    }
}

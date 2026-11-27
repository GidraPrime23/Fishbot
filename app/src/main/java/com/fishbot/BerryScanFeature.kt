package com.fishbot

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore

/**
 * Ягоды, ШАГ 2 (эксперимент): поиск куста с красными ягодами на кадре. Бот НЕ двигается.
 * Галочка в меню: через 6 с, потом раз в секунду 30 секунд берётся кадр экрана; на нём ищутся красно-оранжевые
 * кластеры на зелёном фоне (HUD, джойстик, верх и низ экрана исключены). Для каждого найденного куста в лог
 * пишутся центр, рамка, число красных точек, доля зелёного рядом и смещение от персонажа (пиксели и единицы X/Y).
 * Найденные кусты запоминаются (список KNOWN в конце лога). В «Загрузки» кладутся fishbot_scan_*.txt (лог),
 * fishbot_scan_*_fN.png (кадр с рамками) и fishbot_scan_*_fN_xy.png (вырез со счётчиком X/Y).
 * Повторить: снять галочку и поставить снова. Ловля при включении останавливается.
 */
object BerryScanFeature : Feature {
    override val id = "berryscan"
    override val title = "Ягоды: поиск куста (тест)"
    override val needs = setOf(Need.CAPTURE)
    override val defaultOn = false

    private const val START_DELAY_MS = 6000L
    private const val PERIOD_MS = 1000L
    private const val FRAMES = 30
    private const val MAX_PNG = 6

    // Калибровка шага 1 (кадр 2712x1220): 1 единица X = 57 px, 1 единица Y = 37 px, персонаж в центре кадра.
    private const val REF_W = 2712.0
    private const val PX_PER_X = 57.0
    private const val PX_PER_Y = 37.0
    private const val CHAR_X = 0.500
    private const val CHAR_Y = 0.501

    // Пороги отбора кластера (подбираются по логу и картинкам).
    private const val HOT_CELL = 3        // красных пикселей в клетке, чтобы клетка считалась «горячей»
    private const val MIN_RED = 30        // меньше: «small»
    private const val MAX_RED = 900       // больше: «big»
    private const val MIN_GREEN_PCT = 10  // зелёного вокруг, %; меньше: «nogreen»

    @Volatile private var runId = 0
    @Volatile private var active = false

    private class Cand(
        val cx: Int, val cy: Int, val x0: Int, val y0: Int, val w: Int, val h: Int,
        val red: Int, val greenPct: Int, val why: String
    )

    private class Known(var ux: Double, var uy: Double, var seen: Int, val first: Int)

    override fun onEnable(host: FeatureHost) {
        host.stopWork()
        val myRun = ++runId
        val size = host.screenSize()
        val w0 = size.first
        val h0 = size.second
        if (w0 < h0) {
            host.toast("Поверните телефон в ландшафт (как в игре)")
            return
        }
        active = true
        val sb = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        val th = Thread { scanRun(host, myRun, sb, t0, w0, h0) }
        th.isDaemon = true
        th.start()
        host.toast("Поиск куста через 6 с: закройте меню, встаньте так, чтобы кусты с ягодами были в кадре. Идёт 30 с")
    }

    override fun onDisable(host: FeatureHost) {
        runId++
        active = false
    }

    private fun r1(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()

    private fun waitMs(ms: Long, myRun: Int): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (runId != myRun) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return runId == myRun
    }

    private fun scanRun(host: FeatureHost, myRun: Int, sb: StringBuilder, t0: Long, w0: Int, h0: Int) {
        fun log(s: String) { sb.append(SystemClock.elapsedRealtime() - t0).append(' ').append(s).append('\n') }
        var why = "done"
        var frames = 0
        var withBush = 0
        var saved = 0
        val known = ArrayList<Known>()
        val stamp = System.currentTimeMillis()
        try {
            log("START model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " screen=" + w0 + "x" + h0 +
                " scale=" + PX_PER_X + "x" + PX_PER_Y + " px/unit at W=" + REF_W.toInt() + " frames=" + FRAMES)
            if (!waitMs(START_DELAY_MS, myRun)) { why = "disabled"; return }
            var lastPng = -99
            for (fi in 0 until FRAMES) {
                if (runId != myRun) { why = "disabled"; break }
                val tick = SystemClock.elapsedRealtime()
                val f = host.grabFrame()
                if (f == null) {
                    log("F" + fi + " no_frame " + host.grabWhy())
                } else {
                    frames++
                    val t1 = SystemClock.elapsedRealtime()
                    val cands = findBushes(f)
                    val ms = SystemClock.elapsedRealtime() - t1
                    val kept = cands.filter { it.why == "ok" }
                    log("F" + fi + " " + f.w + "x" + f.h + " ms=" + ms + " clusters=" + cands.size + " ok=" + kept.size)
                    if (kept.isNotEmpty()) withBush++
                    val s = f.w / REF_W
                    var shown = 0
                    for (c in kept) {
                        val ux = (c.cx - CHAR_X * f.w) / (PX_PER_X * s)
                        val uy = -(c.cy - CHAR_Y * f.h) / (PX_PER_Y * s)
                        var kn: Known? = null
                        for (k in known) {
                            val ddx = k.ux - ux
                            val ddy = k.uy - uy
                            if (ddx * ddx + ddy * ddy < 0.64) { kn = k; break }
                        }
                        if (kn == null) {
                            known.add(Known(ux, uy, 1, fi))
                        } else {
                            kn.ux = (kn.ux * kn.seen + ux) / (kn.seen + 1)
                            kn.uy = (kn.uy * kn.seen + uy) / (kn.seen + 1)
                            kn.seen++
                        }
                        if (shown++ < 8) {
                            log("  OK c=" + c.cx + "," + c.cy + " box=" + c.x0 + "," + c.y0 + "," + c.w + "," + c.h +
                                " red=" + c.red + " green=" + c.greenPct + "% off_px=" + (c.cx - (CHAR_X * f.w).toInt()) + "," +
                                (c.cy - (CHAR_Y * f.h).toInt()) + " off_units=" + r1(ux) + "," + r1(uy))
                        }
                    }
                    var shownR = 0
                    for (c in cands) {
                        if (c.why == "ok" || c.red < 10) continue
                        if (shownR++ < 8) {
                            log("  no(" + c.why + ") c=" + c.cx + "," + c.cy + " box=" + c.x0 + "," + c.y0 + "," + c.w + "," + c.h +
                                " red=" + c.red + " green=" + c.greenPct + "%")
                        }
                    }
                    if (saved < MAX_PNG && (fi == 0 || (kept.isNotEmpty() && fi - lastPng >= 3))) {
                        saved++
                        lastPng = fi
                        saveFrame(host, f, cands, fi, stamp) { msg -> log(msg) }
                    }
                }
                val rest = PERIOD_MS - (SystemClock.elapsedRealtime() - tick)
                if (rest > 0 && !waitMs(rest, myRun)) { why = "disabled"; break }
            }
        } catch (e: Throwable) {
            why = "error"
            log("ERROR " + e)
        } finally {
            log("SUMMARY why=" + why + " frames=" + frames + " with_bush=" + withBush + " png=" + saved + " known=" + known.size)
            for ((i, k) in known.withIndex()) {
                log("KNOWN #" + (i + 1) + " seen=" + k.seen + " first_frame=" + k.first + " off_units=" + r1(k.ux) + "," + r1(k.uy))
            }
            saveLog(host, sb, stamp)
            if (runId == myRun) active = false
            if (why == "done") host.toast("Поиск куста закончен. Лог и картинки в «Загрузки»: fishbot_scan_*")
        }
    }

    // красно-оранжевая точка ягоды: яркий красный, зелёного не больше 55% красного, синего не больше 50%
    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 255
        if (r < 170) return false
        val g = (c shr 8) and 255
        val b = c and 255
        return g * 100 <= r * 55 && b * 100 <= r * 50
    }

    // зелёный куст/трава
    private fun isGreen(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return g >= 70 && g > r + 8 && g > b + 25
    }

    private fun findBushes(f: ScanFrame): List<Cand> {
        val W = f.w
        val H = f.h
        val px = f.px
        if (px.size < W * H) return emptyList()
        // зона поиска: без верхней полосы (кнопки, меню), правых кнопок, низа (панель предметов, счётчик X/Y)
        val sx0 = (0.10 * W).toInt()
        val sx1 = (0.82 * W).toInt()
        val sy0 = (0.12 * H).toInt()
        val sy1 = (0.84 * H).toInt()
        // джойстик (центр 0.160 W / 0.730 H) с запасом
        val jx0 = (0.160 * W - 0.16 * H).toInt()
        val jx1 = (0.160 * W + 0.16 * H).toInt()
        val jy0 = (0.730 * H - 0.16 * H).toInt()
        val cs = maxOf(6, W / 226)
        val gw = W / cs + 1
        val gh = H / cs + 1
        val cnt = IntArray(gw * gh)
        for (y in sy0 until sy1) {
            val row = y * W
            val gy = (y / cs) * gw
            for (x in sx0 until sx1) {
                if (y >= jy0 && x >= jx0 && x <= jx1) continue
                if (isRed(px[row + x])) cnt[gy + x / cs]++
            }
        }
        val label = IntArray(gw * gh)
        val stack = IntArray(gw * gh)
        var nLab = 0
        val out = ArrayList<Cand>()
        val maxW = (0.035 * W).toInt()
        val maxH = (0.08 * H).toInt()
        for (start in cnt.indices) {
            if (cnt[start] < HOT_CELL || label[start] != 0) continue
            nLab++
            var sp = 0
            stack[sp++] = start
            label[start] = nLab
            val cells = ArrayList<Int>()
            while (sp > 0) {
                val cur = stack[--sp]
                cells.add(cur)
                val ccx = cur % gw
                val ccy = cur / gw
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val nx = ccx + dx
                        val ny = ccy + dy
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                        val ni = ny * gw + nx
                        if (cnt[ni] >= HOT_CELL && label[ni] == 0) {
                            label[ni] = nLab
                            stack[sp++] = ni
                        }
                    }
                }
            }
            var n = 0
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var sumX = 0L
            var sumY = 0L
            for (ci in cells) {
                val cx0 = (ci % gw) * cs
                val cy0 = (ci / gw) * cs
                val cx1 = minOf(cx0 + cs, W)
                val cy1 = minOf(cy0 + cs, H)
                for (y in cy0 until cy1) {
                    for (x in cx0 until cx1) {
                        if (isRed(px[y * W + x])) {
                            n++
                            sumX += x
                            sumY += y
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
            }
            if (n == 0) continue
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val pad = maxOf(w, h) / 2 + 20
            val gx0 = maxOf(0, minX - pad)
            val gx1 = minOf(W - 1, maxX + pad)
            val gy0 = maxOf(0, minY - pad)
            val gy1 = minOf(H - 1, maxY + pad)
            var green = 0
            var total = 0
            var yy = gy0
            while (yy <= gy1) {
                var xx = gx0
                while (xx <= gx1) {
                    total++
                    if (isGreen(px[yy * W + xx])) green++
                    xx += 2
                }
                yy += 2
            }
            val gpct = if (total > 0) green * 100 / total else 0
            val why = when {
                n < MIN_RED -> "small"
                n > MAX_RED -> "big"
                w > maxW || h > maxH -> "wide"
                gpct < MIN_GREEN_PCT -> "nogreen"
                else -> "ok"
            }
            out.add(Cand((sumX / n).toInt(), (sumY / n).toInt(), minX, minY, w, h, n, gpct, why))
        }
        out.sortByDescending { it.red }
        return out
    }

    private fun saveFrame(host: FeatureHost, f: ScanFrame, cands: List<Cand>, fi: Int, stamp: Long, log: (String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT < 29) return
            val W = f.w
            val H = f.h
            val px = f.px
            for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
            val bmp = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
            val xy = Bitmap.createBitmap(bmp, (0.06 * W).toInt(), (0.94 * H).toInt(), (0.16 * W).toInt(), (0.055 * H).toInt())
            putPng(host, xy, "fishbot_scan_" + stamp + "_f" + fi + "_xy.png")
            val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(m)
            val p = Paint()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.YELLOW
            cv.drawRect(0.10f * W, 0.12f * H, 0.82f * W, 0.84f * H, p)
            p.color = Color.CYAN
            val chx = (CHAR_X * W).toFloat()
            val chy = (CHAR_Y * H).toFloat()
            cv.drawLine(chx - 30f, chy, chx + 30f, chy, p)
            cv.drawLine(chx, chy - 30f, chx, chy + 30f, p)
            p.textSize = 30f
            for (c in cands) {
                if (c.why != "ok" && c.red < 10) continue
                val ok = c.why == "ok"
                p.style = Paint.Style.STROKE
                p.strokeWidth = if (ok) 5f else 2f
                p.color = if (ok) Color.GREEN else Color.RED
                val pad = 14f
                cv.drawRect(c.x0 - pad, c.y0 - pad, (c.x0 + c.w) + pad, (c.y0 + c.h) + pad, p)
                p.style = Paint.Style.FILL
                cv.drawText((if (ok) "ok " else c.why + " ") + c.red, c.x0.toFloat(), c.y0 - pad - 6f, p)
            }
            putPng(host, m, "fishbot_scan_" + stamp + "_f" + fi + ".png")
        } catch (e: Throwable) {
            log("png_error " + e)
        }
    }

    private fun putPng(host: FeatureHost, bmp: Bitmap, name: String) {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        val ctx = host.appContext()
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun saveLog(host: FeatureHost, sb: StringBuilder, stamp: Long) {
        try {
            if (Build.VERSION.SDK_INT < 29) return
            val ctx = host.appContext()
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "fishbot_scan_" + stamp + ".txt")
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {}
    }
}

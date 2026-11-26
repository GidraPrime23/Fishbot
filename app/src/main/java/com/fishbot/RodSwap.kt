package com.fishbot

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.os.SystemClock
import android.provider.MediaStore

/**
 * Автозамена сломанной удочки.
 * Признак поломки: красный круг «!» на плашке «Ваша удочка сломана.» (центр экрана сверху).
 * Дальше: рюкзак -> окно инвентаря -> клик по целой удочке (зелёная полоска под иконкой) ->
 * проверка, что в слоте экипировки полоска зелёная -> закрыть окно крестиком.
 * Все позиции заданы долями экрана (калибровка по видео 25.11, экран 2.22:1).
 * Любой сбой = возврат false, бот остановится сам, ничего не нажимая наугад.
 */
class RodSwap(
    private val grab: ScreenGrabber,
    private val onState: (String) -> Unit,
    private val isActive: () -> Boolean
) {
    private val swaps = ArrayList<Long>()

    /** Когда закончилась последняя замена (любой исход): плашка «сломана» ещё гаснет. */
    @Volatile private var lastDone = -100000L

    /** true = последний swap() не начался (инвентарь не открылся/уже открыт): это ложная тревога, не авария. */
    @Volatile var soft = false
        private set

    /** Причина последнего исхода swap(): "ok" или текст сбоя. */
    @Volatile var lastWhy = ""
        private set

    private fun now() = SystemClock.elapsedRealtime()

    private fun nap(ms: Long) {
        val end = now() + ms
        while (isActive() && now() < end) Thread.sleep(minOf(20L, maxOf(1L, end - now())))
    }

    private fun log(s: String) { FlightLog.row("${now()},$s") }

    // ------------------------------------------------------------- цвета

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0 || mx != r || mx < 150) return false
        if (255 * d / mx < 140) return false
        val h = 30.0 * (g - b) / d
        return h <= 6.0 && h >= -8.0
    }

    private fun isBarGreen(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0 || mx < 130 || g != mx) return false
        if (255 * d / mx < 80) return false
        val h = 30.0 * (2.0 + (b - r).toDouble() / d)
        return h >= 35.0 && h <= 50.0
    }

    // ------------------------------------------------------------ детекторы

    /** Видна ли плашка «Ваша удочка сломана.» (красный круг в верхней центральной области). */
    fun brokenNow(): Boolean {
        if (now() - lastDone < 7000) return false     // сразу после замены старая плашка ещё на экране
        if (!redPlaque()) return false
        for (i in 0 until 2) {                        // плашка должна держаться несколько кадров
            nap(150)
            if (!redPlaque()) return false
        }
        saveAlarm()
        if (lastCrossWhite > 60) {                    // виден крестик рыбалки: заброс идёт, интерфейс скрыт, удочка не сломана
            alarmInfo = ""
            return false
        }
        return true
    }

    private fun redPlaque(): Boolean {
        val img = grab.latest() ?: return false
        val W = img.width; val H = img.height
        val sc = H / 1220.0
        val x0 = (0.20 * W).toInt(); val y0 = (0.02 * H).toInt()
        val w = (0.40 * W).toInt(); val h = (0.38 * H).toInt()
        val px = grab.region(img, x0, y0, w, h)
        val cw = minOf(w, img.width - x0)
        if (cw <= 0) return false
        val ch = px.size / cw
        var n = 0
        var bx0 = Int.MAX_VALUE; var bx1 = -1; var by0 = Int.MAX_VALUE; var by1 = -1
        var y = 0
        while (y < ch) {
            var x = 0
            val base = y * cw
            while (x < cw) {
                if (isRed(px[base + x])) {
                    n++
                    if (x < bx0) bx0 = x
                    if (x > bx1) bx1 = x
                    if (y < by0) by0 = y
                    if (y > by1) by1 = y
                }
                x += 2
            }
            y += 2
        }
        lastRed = n; lastThr = 90 * sc * sc
        lastBox = if (n > 0) "$bx0-$bx1:$by0-$by1" else "-"
        lastWin = intArrayOf(x0, y0, cw, ch)
        return n >= lastThr
    }

    // ------------------------------------------------- проверка во время мини-игры

    /**
     * Быстрая проверка внутри мини-игры (без пауз и без сохранения картинок): красная плашка
     * «Ваша удочка сломана» на экране И крестика рыбалки нет. При настоящей мини-игре крестик виден.
     */
    fun brokenQuick(): Boolean {
        if (now() - lastDone < 7000) return false
        if (!redPlaque()) return false
        return crossWhiteNow() <= 60
    }

    /** Подтверждение после нескольких brokenQuick подряд: пишет диагностику (строка alarm, картинки). */
    fun confirmAlarm(): Boolean {
        saveAlarm()
        if (lastCrossWhite > 60) { alarmInfo = ""; return false }
        return true
    }

    /** Белых пикселей в окне крестика рыбалки сейчас (нет кадра: 999, то есть «крестик есть», тревоги не будет). */
    private fun crossWhiteNow(): Int {
        val img = grab.latest() ?: return 999
        val W = img.width; val H = img.height
        val cx0 = (0.940 * W).toInt(); val cy0 = (0.040 * H).toInt()
        val cw = (0.046 * W).toInt(); val chh = (0.090 * H).toInt()
        val cp = grab.region(img, cx0, cy0, cw, chh)
        if (cp.size < cw * chh) return 999
        var white = 0
        for (c in cp) {
            if (((c shr 16) and 255) > 200 && ((c shr 8) and 255) > 200 && (c and 255) > 200) white++
        }
        return white
    }

    // ------------------------------------------------------- диагностика тревоги

    @Volatile private var lastRed = 0
    @Volatile private var lastThr = 0.0
    @Volatile private var lastBox = "-"
    @Volatile private var lastWin = intArrayOf(0, 0, 0, 0)
    @Volatile private var alarmInfo = ""
    private var alarmSaved = 0
    @Volatile private var lastCrossWhite = 0   // белых пикселей в окне крестика рыбалки при последней тревоге

    /**
     * Тревога «удочка сломана» сработала: пишем в лог число красных отсчётов, их границы и число белых
     * пикселей в окне крестика рыбалки (вверху справа; пока ТОЛЬКО измеряем, на решение не влияет),
     * и сохраняем в «Загрузки» кадр (fishbot_alarm_<время>_full.png) и вырез окна (…_win.png).
     * Любая ошибка здесь гасится: на ловлю это влиять не должно.
     */
    private fun saveAlarm() {
        lastCrossWhite = 0
        try {
            val img = grab.latest() ?: return
            val W = img.width; val H = img.height
            val cx0 = (0.940 * W).toInt(); val cy0 = (0.040 * H).toInt()
            val cw = (0.046 * W).toInt(); val chh = (0.090 * H).toInt()
            var white = 0; var tot = 0
            val cp = grab.region(img, cx0, cy0, cw, chh)
            if (cp.size >= cw * chh) {
                for (c in cp) {
                    tot++
                    if (((c shr 16) and 255) > 200 && ((c shr 8) and 255) > 200 && (c and 255) > 200) white++
                }
            }
            lastCrossWhite = white
            val win = lastWin
            alarmInfo = "alarm,red=$lastRed,thr=${"%.0f".format(lastThr)},win=${win[0]},${win[1]},${win[2]},${win[3]}," +
                "box=$lastBox,cross_white=$white,cross_tot=$tot,frame=${W}x$H"
            if (alarmSaved >= 8) return
            alarmSaved++
            val full = grab.region(img, 0, 0, W, H)
            if (full.size < W * H) return
            for (i in full.indices) full[i] = full[i] or (0xFF shl 24)
            val ts = System.currentTimeMillis()
            val tag = if (white > 60) "blocked" else "real"
            val wx = win[0]; val wy = win[1]; val ww = win[2]; val wh = win[3]
            val ctx = BotAccessibilityService.instance ?: return
            Thread {
                try {
                    val bmp = Bitmap.createBitmap(full, W, H, Bitmap.Config.ARGB_8888)
                    if (ww > 0 && wh > 0 && wx + ww <= W && wy + wh <= H) {
                        val crop = Bitmap.createBitmap(bmp, wx, wy, ww, wh)
                        savePng(ctx, crop, "fishbot_alarm_${ts}_${tag}_win.png")
                    }
                    val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    val cv = Canvas(m)
                    val p = Paint()
                    p.style = Paint.Style.STROKE; p.strokeWidth = 4f
                    p.color = Color.YELLOW
                    cv.drawRect(wx.toFloat(), wy.toFloat(), (wx + ww).toFloat(), (wy + wh).toFloat(), p)
                    p.color = Color.CYAN
                    cv.drawRect(cx0.toFloat(), cy0.toFloat(), (cx0 + cw).toFloat(), (cy0 + chh).toFloat(), p)
                    val small = Bitmap.createScaledBitmap(m, W / 2, H / 2, true)
                    savePng(ctx, small, "fishbot_alarm_${ts}_${tag}_full.png")
                } catch (e: Throwable) { }
            }.also { it.isDaemon = true }.start()
        } catch (e: Throwable) { }
    }

    private fun savePng(ctx: android.content.Context, bmp: Bitmap, name: String) {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Открыто ли окно инвентаря: большая тёмная область по центру. */
    private fun inventoryOpen(): Boolean {
        val img = grab.latest() ?: return false
        val W = img.width; val H = img.height
        val x0 = (0.15 * W).toInt(); val y0 = (0.15 * H).toInt()
        val w = (0.70 * W).toInt(); val h = (0.80 * H).toInt()
        val px = grab.region(img, x0, y0, w, h)
        val cw = minOf(w, img.width - x0)
        if (cw <= 0) return false
        val ch = px.size / cw
        var dark = 0; var tot = 0
        var y = 0
        while (y < ch) {
            var x = 0
            val base = y * cw
            while (x < cw) {
                val c = px[base + x]
                val v = maxOf((c shr 16) and 255, maxOf((c shr 8) and 255, c and 255))
                if (v < 70) dark++
                tot++
                x += 4
            }
            y += 4
        }
        return tot > 0 && dark > 0.45 * tot
    }

    /** Самая длинная горизонтальная зелёная полоска в прямоугольнике (координаты массива). */
    private fun barRun(px: IntArray, pw: Int, ph: Int, xa: Int, xb: Int, ya: Int, yb: Int): Int {
        var best = 0
        val x0 = maxOf(0, xa); val x1 = minOf(pw, xb)
        for (y in maxOf(0, ya) until minOf(ph, yb)) {
            var cur = 0
            for (x in x0 until x1) {
                if (isBarGreen(px[y * pw + x])) { cur++; if (cur > best) best = cur } else cur = 0
            }
        }
        return best
    }

    // Эталон иконки удочки: серый 28x22, среднее вычтено (снят с кадра игры).
    private val TW = 28
    private val TH = 22
    private val TPL: IntArray = intArrayOf(-27,152,110,79,133,105,-27,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-21,-20,-1,-26,122,97,124,93,104,-24,-18,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-18,-1,-27,148,28,108,88,101,-24,-17,-17,-17,-16,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-17,-1,-26,143,23,94,85,99,-23,-17,-16,-16,-16,-16,-17,-17,-17,-17,-17,-17,-18,-17,-17,-17,-17,-18,-16,-16,-16,-1,-27,120,40,51,72,97,-23,-16,-16,-16,-16,-15,-16,-16,-16,-16,-16,-16,-17,-17,-19,-19,-28,-36,-31,-18,-16,-1,-27,141,83,65,103,95,-23,-16,-15,-15,-15,-15,-15,-15,-16,-16,-17,-16,-16,-27,-35,-40,-38,-34,-40,-26,-16,0,-26,138,44,-13,62,93,-23,-16,-15,-15,-15,-15,-15,-15,-15,-16,-19,-31,-25,-45,-40,-21,-27,-36,-40,-26,-16,0,-24,-9,-16,-15,-16,-16,-21,-15,-15,-15,-15,-14,-14,-15,-15,-18,-31,-25,0,-15,-8,-14,-15,-39,-16,-26,-14,0,-19,-14,-15,-15,-14,-15,-14,-14,-14,-14,-14,-14,-14,-14,-19,-28,-26,-3,91,34,-5,-14,-21,-26,-17,-21,-14,1,-18,-13,-13,-13,-13,-13,-13,-13,-13,-13,-13,-12,-13,-19,-31,-29,-13,18,61,12,-13,-13,-30,-1,-35,-13,-13,3,-16,-10,-10,-10,-10,-10,-10,-10,-9,-10,-10,-10,-18,-26,-19,-10,7,-9,-7,-10,-10,-26,-16,-22,-11,-10,-9,5,-15,-8,-7,-7,-7,-7,-7,-7,-7,-8,-7,-17,-27,-20,2,6,-6,-7,-7,-6,-8,-33,-20,-17,-8,-6,-7,7,-14,-3,-3,-3,-2,-2,-2,-3,-4,-3,-13,-28,0,115,26,-4,-3,-4,-3,-3,-4,-38,-31,-16,-4,-3,-4,9,-11,0,0,0,0,0,0,-1,-1,-13,-23,-15,48,57,27,0,-1,-1,-1,0,-2,-25,-26,-25,-1,-1,0,11,-9,3,3,3,3,3,3,3,-11,-26,-6,4,12,13,4,4,2,2,3,4,2,-9,-27,-35,-5,2,1,12,-7,6,6,6,6,6,6,6,-38,3,89,35,6,6,5,6,6,6,6,6,6,6,-20,-13,-29,6,6,15,-5,9,9,9,9,9,5,-23,-39,-13,53,20,9,9,9,8,9,9,9,9,9,6,-17,-29,-20,-4,8,17,-4,12,12,12,12,12,-5,-38,-29,7,12,12,12,12,12,12,13,12,12,12,12,-24,-26,-23,-7,12,12,19,-2,14,14,14,14,11,-16,-18,7,16,15,14,14,14,14,14,15,14,14,15,-18,-8,-21,2,14,14,14,20,0,18,18,18,10,-5,-2,17,19,18,19,18,18,18,18,18,18,18,19,18,-10,-22,-16,11,18,18,18,22,2,21,21,16,-9,-3,-22,1,20,21,20,21,21,21,21,21,21,21,21,22,21,-32,-16,-25,20,22,21,25,4,25,20,-11,4,-13,-8,22,24,24,25,25,25,26,26,26,26,26,26,22,-1,-44,-33,13,-8,25,25,28)

    /** Серая иконка 28x22 вокруг (cx, cy) с вычтенным средним, или null, если выходит за границы. */
    private fun icon(px: IntArray, pw: Int, ph: Int, cx: Int, cy: Int, hw: Int, hh: Int): FloatArray? {
        if (cx - hw < 0 || cy - hh < 0 || cx + hw > pw || cy + hh > ph || hw < 4 || hh < 4) return null
        val out = FloatArray(TW * TH)
        var total = 0f
        for (ty in 0 until TH) {
            val sy0 = cy - hh + (2 * hh) * ty / TH
            val sy1 = maxOf(sy0 + 1, cy - hh + (2 * hh) * (ty + 1) / TH)
            for (tx in 0 until TW) {
                val sx0 = cx - hw + (2 * hw) * tx / TW
                val sx1 = maxOf(sx0 + 1, cx - hw + (2 * hw) * (tx + 1) / TW)
                var s = 0f; var n = 0
                for (y in sy0 until sy1) for (x in sx0 until sx1) {
                    val c = px[y * pw + x]
                    s += (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000f
                    n++
                }
                val v = if (n > 0) s / n else 0f
                out[ty * TW + tx] = v
                total += v
            }
        }
        val mean = total / (TW * TH)
        for (i in out.indices) out[i] -= mean
        return out
    }

    private fun score(f: FloatArray): Float {
        var s = 0f
        for (i in f.indices) s += Math.abs(f[i] - TPL[i])
        return s / f.size
    }

    // ------------------------------------------------------------- действия

    private fun tapFrac(acc: BotAccessibilityService, fx: Double, fy: Double) {
        acc.tap((fx * grab.width).toFloat(), (fy * grab.height).toFloat())
    }

    private fun waitOpen(open: Boolean, ms: Long): Boolean {
        val t0 = now()
        while (isActive() && now() - t0 < ms) {
            if (inventoryOpen() == open) return true
            nap(100)
        }
        return false
    }

    /** Ищем целую удочку в рюкзаке. Возвращает центр слота (x, y) в координатах экрана или null. */
    private fun findGoodRod(img: Image): IntArray? {
        val W = img.width; val H = img.height
        val gx0 = (0.10 * W).toInt(); val gx1 = (0.56 * W).toInt()
        val gy0 = (0.55 * H).toInt(); val gy1 = (0.97 * H).toInt()
        val pw = gx1 - gx0; val ph = gy1 - gy0
        val px = grab.region(img, gx0, gy0, pw, ph)
        if (px.size < pw * ph) return null
        val cols = doubleArrayOf(0.168, 0.248, 0.328, 0.409, 0.488)
        val rows = doubleArrayOf(0.675, 0.852)
        var best: IntArray? = null
        var bestScore = 16f; var bestRun = 0
        for (ri in rows.indices) for (ci in cols.indices) {
            val cx = (cols[ci] * W).toInt(); val cy = (rows[ri] * H).toInt()
            val lx = cx - gx0; val ly = cy - gy0
            val run = barRunAny(px, pw, ph, lx - (0.028 * W).toInt(), lx + (0.028 * W).toInt(),
                ly + (0.048 * H).toInt(), ly + (0.074 * H).toInt())
            val f = icon(px, pw, ph, lx, ly - (0.0139 * H).toInt(), (0.0328 * W).toInt(), (0.059 * H).toInt())
            val sc = if (f != null) score(f) else 99f
            log("cell,$ri,$ci,bar=$run,score=${"%.1f".format(sc)},rgb=${barRgb(px, pw, ph, lx - (0.028 * W).toInt(), lx + (0.028 * W).toInt(), ly + (0.048 * H).toInt(), ly + (0.074 * H).toInt())}")
            if (run >= 0.006 * W && sc < 16f && (run > bestRun || (run == bestRun && sc < bestScore))) { bestRun = run; bestScore = sc; best = intArrayOf(cx, cy) }
        }
        return best
    }

    private fun isOlive(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r in 70..120 && g in 88..135 && b in 35..85 && g > r && r > b && g - r in 8..32 && g - b >= 28
    }

    /**
     * Кнопка «Надеть» (оливково-зелёная) на карточке предмета, которая открывается рядом с выбранным слотом.
     * Ищем окно размером с кнопку с максимальной долей оливковых пикселей. Возвращает центр или null.
     */
    private fun findEquipButton(cx: Int, cy: Int): IntArray? {
        val img = grab.latest() ?: return null
        val W = img.width; val H = img.height
        val x0 = maxOf(0, cx - (0.30 * W).toInt()); val x1 = minOf(W, cx + (0.20 * W).toInt())
        val y0 = maxOf(0, cy - (0.34 * H).toInt()); val y1 = minOf(H, cy + (0.34 * H).toInt())
        val rw = x1 - x0; val rh = y1 - y0
        val bw = (0.094 * W).toInt(); val bh = (0.082 * H).toInt()
        if (rw <= 0 || rh <= 0 || bw < 4 || bh < 4 || bw >= rw || bh >= rh) return null
        val px = grab.region(img, x0, y0, rw, rh)
        if (px.size < rw * rh) return null
        val iw = rw + 1
        val ii = IntArray((rh + 1) * iw)
        for (y in 0 until rh) {
            var row = 0
            for (x in 0 until rw) {
                if (isOlive(px[y * rw + x])) row++
                ii[(y + 1) * iw + x + 1] = ii[y * iw + x + 1] + row
            }
        }
        var best = 0; var bx = 0; var by = 0
        var y = 0
        while (y <= rh - bh) {
            var x = 0
            while (x <= rw - bw) {
                val sum = ii[(y + bh) * iw + x + bw] - ii[y * iw + x + bw] - ii[(y + bh) * iw + x] + ii[y * iw + x]
                if (sum > best) { best = sum; bx = x; by = y }
                x += 2
            }
            y += 2
        }
        val fill = best.toDouble() / (bw * bh)
        val tx = x0 + bx + bw / 2; val ty = y0 + by + bh / 2
        log("equip_btn,fill=${"%.2f".format(fill)},at=$tx,$ty")
        return if (fill >= 0.20) intArrayOf(tx, ty) else null
    }

    /** Зелёная полоска в слоте экипировки (после замены должна появиться). */
    private fun equippedGreen(): Boolean {
        val img = grab.latest() ?: return false
        val W = img.width; val H = img.height
        val x0 = (0.52 * W).toInt(); val y0 = (0.25 * H).toInt()
        val w = (0.12 * W).toInt(); val h = (0.18 * H).toInt()
        val px = grab.region(img, x0, y0, w, h)
        val cw = minOf(w, img.width - x0)
        if (cw <= 0) return false
        val ch = px.size / cw
        val cx = (0.580 * W).toInt() - x0; val cy = (0.295 * H).toInt() - y0
        val run = barRun(px, cw, ch, cx - (0.028 * W).toInt(), cx + (0.028 * W).toInt(),
            cy + (0.040 * H).toInt(), cy + (0.068 * H).toInt())
        log("equipped,bar=$run")
        return run >= 0.006 * W
    }

    /** Полная замена. true = целая удочка надета, инвентарь закрыт. */
    fun swap(acc: BotAccessibilityService): Boolean {
        soft = false
        val t = now()
        swaps.removeAll { t - it > 20 * 60 * 1000L }
        if (swaps.size >= 4) { lastWhy = "удочки ломаются слишком часто"; onState("удочки ломаются слишком часто"); return false }
        swaps.add(t)
        FlightLog.start("ROD")
        if (alarmInfo.isNotEmpty()) { log(alarmInfo); alarmInfo = "" }
        var why = "fail"
        try {
            FloatingService.panelPassThrough?.invoke(true)
            onState("удочка сломана")
            nap(1500)                                   // дождаться, пока плашки исчезнут
            if (inventoryOpen()) { why = "уже открыт инвентарь"; soft = true; swaps.remove(t); return false }
            onState("открываю инвентарь")
            tapFrac(acc, 0.957, 0.905)   // центр круга-рюкзака (красный «!» правее и выше)
            if (!waitOpen(true, 2500)) { why = "инвентарь не открылся"; soft = true; swaps.remove(t); return false }
            nap(500)
            val eqBefore = equippedRun()
            val wNow = grab.latest()?.width ?: 0
            if (equippedGreen() || (eqBefore > 0 && eqBefore >= (0.006 * wNow).toInt())) {   // в руках рабочая удочка (полоска любого цвета): тревога ложная
                swaps.remove(t)
                why = "ok (тревога ложная)"
                closeInventory(acc)
                return true
            }
            val img = grab.latest()
            val rod = if (img != null) findGoodRod(img) else null
            if (rod == null) {
                why = "нет годной удочки"
                onState("нет годной удочки")
                closeInventory(acc)
                return false
            }
            onState("надеваю удочку")
            log("tap_rod,${rod[0]},${rod[1]}")
            acc.tap(rod[0].toFloat(), rod[1].toFloat())
            nap(500)                                    // открывается карточка предмета с кнопкой «Надеть»
            var btn: IntArray? = null
            val tb = now()
            while (isActive() && now() - tb < 1800) {
                btn = findEquipButton(rod[0], rod[1])
                if (btn != null) break
                nap(150)
            }
            if (btn == null) {
                why = "кнопка Надеть не найдена"
                onState("кнопка Надеть не найдена")
                closeInventory(acc)
                return false
            }
            log("tap_equip,${btn[0]},${btn[1]}")
            acc.tap(btn[0].toFloat(), btn[1].toFloat())
            nap(800)
            val ok = equippedOk(eqBefore)
            if (!ok) {
                why = "замена не подтвердилась"
                onState("замена не подтвердилась")
                closeInventory(acc)
                return false
            }
            if (!closeInventory(acc)) { why = "инвентарь не закрылся"; return false }
            nap(600)
            why = "ok"
            onState("успешно одета удочка"); nap(1500)
            return true
        } finally {
            FloatingService.panelPassThrough?.invoke(false)
            lastWhy = why
            lastDone = now()
            FlightLog.save(acc, why, "rodswap")
        }
    }

    private fun isBarAny(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0 || mx < 130) return false
        if (255 * d / mx < 80) return false
        val h = when (mx) {
            r -> 60.0 * (((g - b).toDouble() / d + 6.0) % 6.0)
            g -> 60.0 * (2.0 + (b - r).toDouble() / d)
            else -> 60.0 * (4.0 + (r - g).toDouble() / d)
        }
        return h <= 100.0 || h >= 340.0
    }

    /** Как barRun, но полоска любого цвета (красный, оранжевый, жёлтый, зелёный). */
    private fun barRunAny(px: IntArray, pw: Int, ph: Int, xa: Int, xb: Int, ya: Int, yb: Int): Int {
        // полоска = цвет держится минимум в 5 строках; тонкая подсветка слота (около 3 строк) не считается
        val x0 = maxOf(0, xa); val x1 = minOf(pw, xb)
        val rowBest = ArrayList<Int>()
        for (y in maxOf(0, ya) until minOf(ph, yb)) {
            var cur = 0; var best = 0
            for (x in x0 until x1) {
                if (isBarAny(px[y * pw + x])) { cur++; if (cur > best) best = cur } else cur = 0
            }
            rowBest.add(best)
        }
        if (rowBest.size < 5) return 0
        rowBest.sortDescending()
        return rowBest[4]
    }

    /** Средний цвет пикселей полоски (для лога), формат r|g|b. */
    private fun barRgb(px: IntArray, pw: Int, ph: Int, xa: Int, xb: Int, ya: Int, yb: Int): String {
        var n = 0L; var sr = 0L; var sg = 0L; var sb = 0L
        for (y in maxOf(0, ya) until minOf(ph, yb)) {
            for (x in maxOf(0, xa) until minOf(pw, xb)) {
                val c = px[y * pw + x]
                if (isBarAny(c)) { n++; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            }
        }
        return if (n == 0L) "-" else "${sr / n}|${sg / n}|${sb / n}"
    }

    /** Длина полоски под слотом экипировки (любой цвет), -1 если кадра нет. */
    private fun equippedRun(): Int {
        val img = grab.latest() ?: return -1
        val W = img.width; val H = img.height
        val x0 = (0.52 * W).toInt(); val y0 = (0.25 * H).toInt()
        val w = (0.12 * W).toInt(); val h = (0.18 * H).toInt()
        val px = grab.region(img, x0, y0, w, h)
        val cw = minOf(w, img.width - x0)
        if (cw <= 0) return -1
        val ch = px.size / cw
        val cx = (0.580 * W).toInt() - x0; val cy = (0.295 * H).toInt() - y0
        val run = barRunAny(px, cw, ch, cx - (0.028 * W).toInt(), cx + (0.028 * W).toInt(),
            cy + (0.040 * H).toInt(), cy + (0.068 * H).toInt())
        log("equipped_any,bar=$run,rgb=${barRgb(px, cw, ch, cx - (0.028 * W).toInt(), cx + (0.028 * W).toInt(), cy + (0.040 * H).toInt(), cy + (0.068 * H).toInt())}")
        return run
    }

    /** Надета ли новая удочка: полоска есть и длиннее, чем была до замены. */
    private fun equippedOk(before: Int): Boolean {
        val W = grab.latest()?.width ?: return false
        val run = equippedRun()
        val need = maxOf((0.006 * W).toInt(), before + (0.003 * W).toInt())
        log("equip_check,before=$before,after=$run,need=$need")
        return run >= need
    }

    private fun closeInventory(acc: BotAccessibilityService): Boolean {
        for (i in 0 until 2) {
            tapFrac(acc, 0.863, 0.069)
            if (waitOpen(false, 1500)) return true
        }
        return false
    }
}

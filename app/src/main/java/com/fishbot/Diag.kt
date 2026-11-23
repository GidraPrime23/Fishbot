package com.fishbot

object Diag {
    private var last = 0L

    fun badge(px: IntArray, w: Int, h: Int, say: (String) -> Unit) {
        val t = System.currentTimeMillis()
        if (t - last < 800) return
        last = t
        var strict = 0
        var loose = 0
        var sh = 0L
        var ss = 0L
        var sv = 0L
        for (c in px) {
            val p = Vision.hsv(c)
            val hh = (p shr 16) and 255
            val s = (p shr 8) and 255
            val v = p and 255
            if (Vision.isBadge(c)) strict++
            if (hh in 8..22 && s >= 120 && v >= 120) {
                loose++
                sh += hh
                ss += s
                sv += v
            }
        }
        val m = if (loose > 0) " H${sh / loose} S${ss / loose} V${sv / loose}" else ""
        say("тчн=$strict шир=$loose$m")
    }
}

package com.fishbot;

/**
 * Ядро компьютерного зрения. Чистая Java, без Android-зависимостей:
 * работает с массивом пикселей ARGB (как Bitmap.getPixels) и не зависит от телефона.
 *
 * sc = высота экрана / 1220.0 (масштаб относительно эталонного видео).
 */
public final class Vision {
    private Vision() {}

    /** HSV как в OpenCV: H 0..179, S 0..255, V 0..255. Упаковано как (H<<16)|(S<<8)|V. */
    static int hsv(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        int d = mx - mn;
        int s = mx == 0 ? 0 : (255 * d + mx / 2) / mx;
        int h;
        if (d == 0) h = 0;
        else if (mx == r) h = (int) Math.round(30.0 * (g - b) / d);
        else if (mx == g) h = (int) Math.round(30.0 * (2 + (double) (b - r) / d));
        else h = (int) Math.round(30.0 * (4 + (double) (r - g) / d));
        if (h < 0) h += 180;
        if (h > 179) h -= 180;
        return (h << 16) | (s << 8) | mx;
    }

    /** Зелёная зона (поплавок внутри): на разных телефонах и картах H~75, S~124, V~131. */
    static boolean isZoneGreen(int c) {
        int p = hsv(c), h = p >> 16, s = (p >> 8) & 255, v = p & 255;
        return h >= 70 && h <= 80 && s >= 98 && s <= 148 && v >= 110 && v <= 155;
    }

    /** Коричневая зона (поплавок вне зоны): H~13-17, S~55-80, V~115-125. */
    static boolean isZoneBrown(int c) {
        int p = hsv(c), h = p >> 16, s = (p >> 8) & 255, v = p & 255;
        return h >= 10 && h <= 20 && s >= 40 && s <= 100 && v >= 100 && v <= 145;
    }

    static boolean isZone(int c, int kind) {
        return kind == 1 ? isZoneGreen(c) : isZoneBrown(c);
    }

    /** Жёлтая линия поплавка (~H 22, S 150, V 253). */
    static boolean isYellow(int c) {
        int p = hsv(c), h = p >> 16, s = (p >> 8) & 255, v = p & 255;
        return h >= 17 && h <= 30 && s >= 60 && v >= 95;   // с запасом на сжатое видео/мелкое разрешение
    }

    /** Оранжевый кружок «!» на кнопке рыбалки (~H 14, S 200, V 204). */
    static boolean isBadge(int c) {
        int p = hsv(c), h = p >> 16, s = (p >> 8) & 255, v = p & 255;
        return h >= 10 && h <= 18 && s >= 160 && s <= 245 && v >= 160 && v <= 245;
    }

    /** Поклёвка: на кнопке есть оранжевый значок «!». px - область вокруг кнопки. */
    public static boolean hasBite(int[] px, int w, int h, double sc) {
        int n = 0;
        for (int i = 0, len = w * h; i < len; i++) if (isBadge(px[i])) n++;
        return n >= 300 * sc * sc;
    }

    /**
     * Ищем зелёную зону мини-игры. Возвращает {xl, xr, top, bottom} в координатах массива или null.
     * Зона - прямоугольник нужного размера цвета H~75; озеро/трава/пруд в этот диапазон не попадают.
     * Жёлтая линия поплавка режет зону на куски, поэтому куски склеиваем.
     */
    public static int[] findZone(int[] px, int w, int h, double sc) {
        int[] z = findZone(px, w, h, sc, 1);          // зелёная
        return z != null ? z : findZone(px, w, h, sc, 2); // коричневая
    }

    /** kind: 1 = зелёная, 2 = коричневая. Результат: {xl, xr, top, bottom, kind}. */
    static int[] findZone(int[] px, int w, int h, double sc, int kind) {
        final int st = sc > 0.6 ? 2 : 1;
        final double zh = 181 * sc, zwExp = 63 * sc;
        int[] col = new int[w];
        for (int y = 0; y < h; y += st) {
            int base = y * w;
            for (int x = 0; x < w; x += st) if (isZone(px[base + x], kind)) col[x]++;
        }
        int need = (int) (0.25 * zh / st);
        // непрерывные полосы столбцов (шаг st)
        int bestL = -1, bestR = -1;
        long bestSum = 0;
        int runStart = -1;
        long sum = 0;
        for (int x = 0; x <= w + st; x += st) {
            boolean ok = x < w && col[x] >= need;
            if (ok) {
                if (runStart < 0) { runStart = x; sum = 0; }
                sum += col[x];
            } else if (runStart >= 0) {
                int lastX = x - st;
                int width = lastX - runStart + st;
                if (width >= 0.40 * zwExp && width <= 1.7 * zwExp && sum > bestSum) {
                    bestSum = sum; bestL = runStart; bestR = lastX + st - 1;
                }
                runStart = -1;
            }
        }
        if (bestL < 0) return null;
        int xl = bestL, xr = Math.min(w - 1, bestR), width = xr - xl + 1;
        // строки: где заливка занимает >= 40% ширины
        int rowNeed = (int) (0.40 * width / st);
        int gapMax = (int) (0.30 * zh);
        int gTop = -1, gBot = -1, bTop = -1, bBot = -1;
        for (int y = 0; y < h; y += st) {
            int cnt = 0, base = y * w;
            for (int x = xl; x <= xr; x += st) if (isZone(px[base + x], kind)) cnt++;
            if (cnt >= rowNeed) {
                if (gTop < 0) { gTop = y; gBot = y; }
                else if (y - gBot <= gapMax) gBot = y;
                else {
                    if (bTop < 0 || gBot - gTop > bBot - bTop) { bTop = gTop; bBot = gBot; }
                    gTop = y; gBot = y;
                }
            }
        }
        if (gTop >= 0 && (bTop < 0 || gBot - gTop > bBot - bTop)) { bTop = gTop; bBot = gBot; }
        if (bTop < 0) return null;
        int height = bBot - bTop + st;
        if (height < 0.6 * zh || height > 1.6 * zh) return null;
        return new int[]{xl, xr, bTop, bBot + st - 1, kind};
    }


    /** Y рыбки внутри зоны (светло-голубые пиксели) или NaN. Зона: {xl, xr, top, bottom}. */
    public static double findFish(int[] px, int w, int h, int[] zone, double sc, double markerY) {
        int st = sc > 0.6 ? 2 : 1;
        int y0 = Math.max(0, zone[2]), y1 = Math.min(h - 1, zone[3]);
        int x0 = Math.max(0, zone[0]), x1 = Math.min(w - 1, zone[1]);
        double zc = (zone[2] + zone[3]) / 2.0, ex = 26 * sc;
        if (!Double.isNaN(markerY) && Math.abs(markerY - zc) < ex + 12 * sc) return Double.NaN;
        long sum = 0;
        int cnt = 0;
        for (int y = y0; y <= y1; y += st) {
            if (!Double.isNaN(markerY) && Math.abs(y - markerY) < ex) continue;
            int base = y * w;
            for (int x = x0; x <= x1; x += st) {
                int p = hsv(px[base + x]);
                int hh = p >> 16, s = (p >> 8) & 255, v = p & 255;
                if (hh >= 90 && hh <= 115 && s >= 10 && s <= 100 && v >= 150) { sum += y; cnt++; }
            }
        }
        if (cnt < 12) return Double.NaN;
        double fy = (double) sum / cnt;
        if (Math.abs(fy - zc) > 0.25 * (zone[3] - zone[2])) return Double.NaN;
        return fy;
    }
    /** Y жёлтой линии поплавка рядом с зоной или NaN. Зона: {xl, xr, top, bottom}. */
    public static double findMarker(int[] px, int w, int h, int[] zone, double sc) {
        int zw = zone[1] - zone[0] + 1;
        int x0 = Math.max(0, zone[0] - (int) (0.45 * zw)), x1 = Math.min(w - 1, zone[1] + (int) (0.45 * zw));
        double zc = (zone[2] + zone[3]) / 2.0, zhh = 181 * sc;
        int y0 = Math.max(0, (int) (zc - 3.0 * zhh)), y1 = Math.min(h - 1, (int) (zc + 3.0 * zhh));
        int need = (int) (0.6 * zw);
        double bestY = Double.NaN, bestD = 1e18;
        int gS = -1, gE = -1;
        for (int y = y0; y <= y1 + 1; y++) {
            boolean hit = false;
            if (y <= y1) {
                int cnt = 0, base = y * w;
                for (int x = x0; x <= x1; x++) if (isYellow(px[base + x])) cnt++;
                hit = cnt >= need;
            }
            if (hit) { if (gS < 0) gS = y; gE = y; }
            else if (gS >= 0) {
                double m = (gS + gE) / 2.0, d = Math.abs(m - zc);
                if (gE - gS < 0.04 * 1220 * sc * 2 && d < bestD) { bestD = d; bestY = m; }
                gS = -1;
            }
        }
        return bestY;
    }

    /** Регулятор: true = держать кнопку (поплавок вверх), false = отпустить. */
    public static final class Controller {
        /** kd - упреждение (сек) на задержку; для отпускания берётся kd + 0.05. */
        public double kd = 0.30, deadband = 0.03, smooth = 0.4;
        private double prevT = Double.NaN, prevY = Double.NaN, v = 0, tgt = Double.NaN, lastFlip = -1e9;
        private boolean holding = false;

        public void reset() {
            prevT = Double.NaN; prevY = Double.NaN; v = 0; tgt = Double.NaN;
            holding = false; lastFlip = -1e9;
        }

        /** true = держать кнопку (поплавок вверх), false = отпустить. Цель - центр зоны. */
        public boolean decide(double t, double marker, double zTop, double zBot) {
            double h = zBot - zTop;
            double target = (zTop + zBot) / 2.0;
            tgt = target;
            if (Double.isNaN(prevT)) {
                prevT = t; prevY = marker;
            } else if (t - prevT >= 0.005) {
                double inst = (marker - prevY) / (t - prevT);
                v = smooth * v + (1 - smooth) * inst;
                prevT = t; prevY = marker;
            }
            double lead = holding ? kd + 0.05 : kd;
            double shift = Math.max(-1.5 * h, Math.min(1.5 * h, v * lead));
            double p = marker + shift;
            double e = p - target;
            double band = deadband * h;
            boolean want;
            if (p < zTop) want = false;
            else if (p > zBot) want = true;
            else if (holding) want = !(e < -band);
            else want = e > band;
            if (want != holding) {
                boolean urgent = p < zTop || p > zBot;
                if (urgent || t - lastFlip >= 0.06) { holding = want; lastFlip = t; }
            }
            return holding;
        }

        /** Поплавок не найден на кадре: оцениваем, где он, и не держим вслепую. */
        public boolean lost(double t) {
            if (Double.isNaN(prevT) || Double.isNaN(tgt)) return false;
            double dt = Math.min(0.4, Math.max(0.0, t - prevT));
            boolean want = (prevY + v * dt) > tgt;
            if (want != holding) { holding = want; lastFlip = t; }
            return holding;
        }
    }
}

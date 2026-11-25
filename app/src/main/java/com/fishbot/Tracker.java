package com.fishbot;

public final class Tracker {
    public static final double UP = 75, DOWN = 73, DELAY = 0.07;
    public static final double TOP = 450, BOT = 1180;
    private double y = Double.NaN, tEst = 0, tGood = -1e9;
    private boolean curHold = false, prevHold = false, started = false;
    private double tChange = -1e9;
    public int state = 0;
    public String bands = "";
    private final double[] pc = new double[8], pa = new double[8];
    private int pn = 0;
    private double pt = 0;

    public void reset() {
        y = Double.NaN; tEst = 0; tGood = -1e9;
        curHold = false; prevHold = false; started = false; tChange = -1e9;
        state = 0; bands = ""; pn = 0;
    }

    private static boolean yellow(int c) {
        int p = Vision.hsv(c);
        int hh = (p >> 16) & 255, s = (p >> 8) & 255, v = p & 255;
        return hh >= 17 && hh <= 30 && s >= 60 && v >= 95;
    }

    public double find(int[] px, int w, int h, int[] zone, double sc, long tMs, boolean hold) {
        double t = tMs / 1000.0;
        if (hold != curHold) { prevHold = curHold; curHold = hold; tChange = t; }
        if (!Double.isNaN(y)) {
            boolean eff = (t - tChange >= DELAY) ? curHold : prevHold;
            y += (eff ? -UP : DOWN) * sc * (t - tEst);
            y = Math.max(TOP * sc, Math.min(BOT * sc, y));
            tEst = t;
        }
        int zw = zone[1] - zone[0] + 1;
        int x0 = Math.max(0, zone[0] - (int) (0.45 * zw)), x1 = Math.min(w - 1, zone[1] + (int) (0.45 * zw));
        double zc = (zone[2] + zone[3]) / 2.0, zhh = 181 * sc;
        int y0 = Math.max(0, (int) (zc - 3.0 * zhh)), y1 = Math.min(h - 1, (int) (zc + 3.0 * zhh));
        int need = (int) (0.6 * zw);
        double maxThick = 0.04 * 1220 * sc * 2;
        double[] cs = new double[8];
        int nb = 0, gS = -1, gE = -1;
        for (int yy = y0; yy <= y1 + 1; yy++) {
            boolean hit = false;
            if (yy <= y1) {
                int cnt = 0, base = yy * w;
                for (int x = x0; x <= x1; x++) if (yellow(px[base + x])) cnt++;
                hit = cnt >= need;
            }
            if (hit) { if (gS < 0) gS = yy; gE = yy; }
            else if (gS >= 0) {
                if (gE - gS < maxThick && nb < 8 && (gS + gE) / 2.0 <= 1000 * sc) cs[nb++] = (gS + gE) / 2.0;
                gS = -1;
            }
        }
        double[] age = new double[8];
        for (int i = 0; i < nb; i++) {
            for (int j = 0; j < pn; j++) {
                if (Math.abs(cs[i] - pc[j]) <= 1.5) { age[i] = pa[j] + (t - pt); break; }
            }
        }
        for (int i = 0; i < nb; i++) { pc[i] = cs[i]; pa[i] = age[i]; }
        pn = nb; pt = t;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nb; i++) { if (i > 0) sb.append('|'); sb.append((int) cs[i]); }
        bands = sb.toString();

        boolean haveRef = !Double.isNaN(y) && (t - tGood) < 1.5;
        if (!haveRef) {
            if (nb == 0) { state = 0; y = Double.NaN; return Double.NaN; }
            double best = cs[0];
            for (int i = 1; i < nb; i++) {
                if (!started) { if (cs[i] > best) best = cs[i]; }
                else if (Math.abs(cs[i] - zc) < Math.abs(best - zc)) best = cs[i];
            }
            started = true;
            y = best; tEst = t; tGood = t; state = 3;
            return y;
        }
        double gate = Math.min(200, 30 + 150 * (t - tGood)) * sc;
        boolean eff = (t - tChange >= DELAY) ? curHold : prevHold;
        int bi = -1;
        for (int i = 0; i < nb; i++) {
            if (age[i] >= 0.35) continue;
            double d = Math.abs(cs[i] - y);
            if (d <= gate && (bi < 0 || d < Math.abs(cs[bi] - y))) bi = i;
        }
        if (bi >= 0) { y = cs[bi]; tGood = t; state = 1; return y; }
        for (int i = 0; i < nb; i++) {
            if (age[i] < 0.35) continue;
            boolean floor = !eff && cs[i] >= 940 * sc && y >= cs[i] - 3 * sc && cs[i] - y < gate + 3 * sc;
            boolean ceil = eff && cs[i] <= 660 * sc && y <= cs[i] + 3 * sc && y - cs[i] < gate + 3 * sc;
            if (floor || ceil) { y = cs[i]; tGood = t; state = 4; return y; }
        }
        state = 2;
        return y;
    }
}

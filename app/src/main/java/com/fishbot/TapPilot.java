package com.fishbot;

public final class TapPilot {
    public double gap = 0.10, lat = 0.12, band = 0.04, win = 0.16;
    public double v = 0, p = 0;
    private final double[] ts = new double[16], ys = new double[16];
    private int n = 0;
    private double lastTap = -1e9;

    public void reset() { n = 0; v = 0; p = 0; lastTap = -1e9; }

    public boolean decide(double t, double marker, double zTop, double zBot) {
        double h = zBot - zTop, target = (zTop + zBot) / 2.0;
        if (n == 16) {
            System.arraycopy(ts, 1, ts, 0, 15);
            System.arraycopy(ys, 1, ys, 0, 15);
            n = 15;
        }
        ts[n] = t; ys[n] = marker; n++;
        int s = 0;
        while (s < n - 1 && t - ts[s] > win) s++;
        int c = n - s;
        if (c >= 3) {
            double mt = 0, my = 0;
            for (int i = s; i < n; i++) { mt += ts[i]; my += ys[i]; }
            mt /= c; my /= c;
            double num = 0, den = 0;
            for (int i = s; i < n; i++) {
                num += (ts[i] - mt) * (ys[i] - my);
                den += (ts[i] - mt) * (ts[i] - mt);
            }
            if (den > 1e-9) v = num / den;
        }
        double vv = Math.max(-120, Math.min(120, v));
        p = marker + vv * lat;
        if (p > target + band * h && t - lastTap >= gap) {
            lastTap = t;
            return true;
        }
        return false;
    }
}

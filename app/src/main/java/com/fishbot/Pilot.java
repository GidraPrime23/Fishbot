package com.fishbot;

public final class Pilot {
    public double leadPress = 0.10, leadRelease = 0.16, band = 0.10;
    public double dwell = 0.09, win = 0.16, edge = 0.05;
    public double v = 0, p = 0;
    private final double[] ts = new double[16], ys = new double[16];
    private int n = 0;
    private boolean holding = false;
    private double lastFlip = -1e9, lastSeen = Double.NaN;

    public void reset() {
        n = 0; v = 0; p = 0; holding = false; lastFlip = -1e9; lastSeen = Double.NaN;
    }

    public boolean decide(double t, double marker, double zTop, double zBot) {
        double h = zBot - zTop, target = (zTop + zBot) / 2.0;
        lastSeen = t;
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
        double lead = holding ? leadRelease : leadPress;
        double shift = Math.max(-h, Math.min(h, v * lead));
        p = marker + shift;
        double e = p - target, b = band * h;
        boolean want, urgent = false;
        if (p < zTop + edge * h) { want = false; urgent = true; }
        else if (p > zBot - edge * h) { want = true; urgent = true; }
        else if (holding) want = !(e < -b);
        else want = e > b;
        if (want != holding && (urgent || t - lastFlip >= dwell)) {
            holding = want; lastFlip = t;
        }
        return holding;
    }

    public boolean holding() { return holding; }

    public boolean lost(double t) {
        if (Double.isNaN(lastSeen) || t - lastSeen > 0.15) {
            if (holding) { holding = false; lastFlip = t; }
        }
        return holding;
    }
}

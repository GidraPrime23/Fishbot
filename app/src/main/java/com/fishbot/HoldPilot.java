package com.fishbot;

public final class HoldPilot {
    public double lat = 0.10, up = 70, band = 0.05, win = 0.16, k = 0.8;
    public int minMs = 80, maxMs = 1500;
    public double v = 0, vm = 0, p = 0;
    private final double[] ts = new double[16], ms = new double[16], gs = new double[16];
    private int n = 0;
    private double pStart = -1e9, pEnd = -1e9;

    public void reset() { n = 0; v = 0; vm = 0; p = 0; pStart = -1e9; pEnd = -1e9; }

    public void pressed(double t, int dur) { pStart = t; pEnd = t + dur / 1000.0; }

    private static double cl(double x, double a, double b) { return Math.max(a, Math.min(b, x)); }

    public int decide(double t, double marker, double zTop, double zBot, double aim) {
        double h = zBot - zTop;
        double g = Double.isNaN(aim) ? (zTop + zBot) / 2.0 : aim;
        if (n == 16) {
            System.arraycopy(ts, 1, ts, 0, 15);
            System.arraycopy(ms, 1, ms, 0, 15);
            System.arraycopy(gs, 1, gs, 0, 15);
            n = 15;
        }
        ts[n] = t; ms[n] = marker; gs[n] = g; n++;
        int s = 0;
        while (s < n - 1 && t - ts[s] > win) s++;
        int c = n - s;
        if (c >= 3) {
            double mt = 0, mm = 0, mg = 0;
            for (int i = s; i < n; i++) { mt += ts[i]; mm += ms[i]; mg += gs[i]; }
            mt /= c; mm /= c; mg /= c;
            double a = 0, b = 0, den = 0;
            for (int i = s; i < n; i++) {
                double dt = ts[i] - mt;
                a += dt * (ms[i] - mm);
                b += dt * (gs[i] - mg);
                den += dt * dt;
            }
            if (den > 1e-9) { vm = a / den; v = (a - b) / den; }
        }
        if (t > pStart + 0.25 && t < pEnd) up += 0.05 * (cl(-vm, 20, 150) - up);
        if (t < pEnd + 0.03) return 0;
        p = (marker - g) + cl(v, -150, 150) * lat;
        double need = p - band * h;
        if (need <= 0) return 0;
        int d = (int) Math.round(need / Math.max(30, up) * 1000 * k);
        return (int) cl(d, minMs, maxMs);
    }
}

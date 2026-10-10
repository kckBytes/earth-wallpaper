package com.chai.earthwall;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Ventusky-style wind streaks: a few thousand particles drifting with the 10 m wind, each drawn as a short
 * fading trail of its last positions. Simulated on the CPU on the sphere (cheap at this count); the GPU only
 * projects the points. Particles are (re)born on the side of the Earth facing the viewer.
 */
final class WindStreaks {
    static final int N = 2200, K = 10;                     // particles, points per trail
    private static final double SPEED = 0.42;              // degrees per second per m/s of wind (a visual time-lapse)
    final FloatBuffer verts = ByteBuffer.allocateDirect(N * K * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final double[] lat = new double[N], lon = new double[N], age = new double[N], life = new double[N];
    private final float[] hist = new float[N * K * 3];
    private final int[] head = new int[N], filled = new int[N];
    private final Random rnd = new Random();
    private double pushAcc;
    private boolean seeded;

    interface Wind { void at(double lat, double lon, double[] uv); }   // m/s east, north

    private void spawn(int i, double cLat, double cLon) {
        // uniform-ish over a cap ~80 degrees around the view centre
        double ang = Math.toRadians(80) * Math.sqrt(rnd.nextDouble()), dir = rnd.nextDouble() * 2 * Math.PI;
        double la0 = Math.toRadians(cLat), lo0 = Math.toRadians(cLon);
        double la = Math.asin(Math.sin(la0) * Math.cos(ang) + Math.cos(la0) * Math.sin(ang) * Math.cos(dir));
        double lo = lo0 + Math.atan2(Math.sin(dir) * Math.sin(ang) * Math.cos(la0), Math.cos(ang) - Math.sin(la0) * Math.sin(la));
        lat[i] = Math.toDegrees(la); lon[i] = Math.toDegrees(lo);
        age[i] = 0; life[i] = 1.6 + rnd.nextDouble() * 2.4;
        filled[i] = 0; head[i] = 0;
    }

    /** Advance by dt seconds and refill the vertex buffer (x, y, z, opacity per point; K points per particle). */
    void step(double dt, Wind w, double cLat, double cLon) {
        if (!seeded) { for (int i = 0; i < N; i++) { spawn(i, cLat, cLon); age[i] = rnd.nextDouble() * life[i]; } seeded = true; }
        dt = Math.min(dt, 0.1);
        pushAcc += dt;
        boolean push = pushAcc >= 1 / 30.0;
        if (push) pushAcc = 0;
        double[] uv = new double[2];
        for (int i = 0; i < N; i++) {
            age[i] += dt;
            w.at(lat[i], lon[i], uv);
            double spd = Math.hypot(uv[0], uv[1]);
            if (age[i] > life[i] || Math.abs(lat[i]) > 84 || (spd < 0.4 && age[i] > 0.5)) { spawn(i, cLat, cLon); continue; }
            double c = Math.max(0.15, Math.cos(Math.toRadians(lat[i])));
            lat[i] += uv[1] * SPEED * dt;
            lon[i] += uv[0] * SPEED * dt / c;
            if (push || filled[i] == 0) {
                double la = Math.toRadians(lat[i]), lo = Math.toRadians(lon[i]);
                int o = (i * K + head[i]) * 3;
                hist[o] = (float) (Math.cos(la) * Math.cos(lo)); hist[o + 1] = (float) (Math.cos(la) * Math.sin(lo)); hist[o + 2] = (float) Math.sin(la);
                head[i] = (head[i] + 1) % K;
                if (filled[i] < K) filled[i]++;
            }
        }
        verts.position(0);
        for (int i = 0; i < N; i++) {
            double fade = Math.min(1, Math.min(age[i] / 0.5, (life[i] - age[i]) / 0.6));
            w.at(lat[i], lon[i], uv);
            double strength = Math.max(0.35, Math.min(1, Math.hypot(uv[0], uv[1]) / 7.0));
            int n = filled[i];
            for (int k = 0; k < K; k++) {
                // oldest .. newest; points not yet filled collapse onto the oldest one (zero-length, invisible)
                int idx = n < K ? Math.min(k, Math.max(0, n - 1)) : (head[i] + k) % K;
                int o = (i * K + idx) * 3;
                float a = n < 2 ? 0f : (float) (fade * strength * (k / (K - 1.0)));
                verts.put(hist[o]).put(hist[o + 1]).put(hist[o + 2]).put(a);
            }
        }
        verts.position(0);
    }

    void reset() { seeded = false; }
}

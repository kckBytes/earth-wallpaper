package com.chai.earthwall;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Estimates how the cloud field moved between two equirectangular cloud maps (block matching on a
 * 512x256 downsample, sub-pixel refined, outlier-filtered, smoothed) and encodes the result as a
 * 64x32 RG8 texture of degrees/hour (east, north), 128 = still. Pure Java so it can be unit-tested
 * on a desktop JVM. Cost: ~50-150 ms once per new cloud map.
 */
final class FlowEstimator {
    private FlowEstimator() {}

    static final int GW = 64, GH = 32;          // output grid (5.6 deg cells)
    static final float FLOW_MAX = 2.5f;         // deg/hour at byte 255 — must match globe.frag
    private static final int W = 512, H = 256;  // working resolution (0.703 deg/px)
    private static final int HALF = 8;          // 17x17 template
    private static final int SEARCH = 7;        // +-7 px = +-4.9 deg between frames

    /** Neutral (no-motion) texture. */
    /** "mean X, p90 Y, max Z deg/h" — diagnostics for the log. */
    static String stats(ByteBuffer flow) {
        int n = flow.capacity() / 2;
        float[] m = new float[n];
        for (int i = 0; i < n; i++) {
            float e = ((flow.get(2 * i) & 0xFF) - 128) / 127f * FLOW_MAX, no = ((flow.get(2 * i + 1) & 0xFF) - 128) / 127f * FLOW_MAX;
            m[i] = (float) Math.hypot(e, no);
        }
        double sum = 0; for (float v : m) sum += v;
        java.util.Arrays.sort(m);
        return String.format(java.util.Locale.US, "mean %.2f, p90 %.2f, max %.2f deg/h", sum / n, m[(int) (n * 0.9)], m[n - 1]);
    }

    static ByteBuffer still() {
        ByteBuffer b = ByteBuffer.allocateDirect(GW * GH * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < GW * GH * 2; i++) b.put((byte) 128);
        b.position(0);
        return b;
    }

    /**
     * @param a older map, row-major bytes (w x h), b newer map (may be a different resolution)
     * @param hours time between the two maps
     * @return RG8 buffer GW x GH, or {@link #still()} if the interval is implausible
     */
    static ByteBuffer estimate(ByteBuffer a, int aw, int ah, ByteBuffer b, int bw, int bh, double hours) {
        if (hours < 0.75 || hours > 9) return still();
        float[] A = downsample(a, aw, ah), B = downsample(b, bw, bh);
        float[] fx = new float[GW * GH], fy = new float[GW * GH];
        boolean[] ok = new boolean[GW * GH];
        int cell = W / GW;                                    // 8 px

        for (int gy = 0; gy < GH; gy++) {
            int cy = gy * cell + cell / 2;
            double lat = 90.0 - (cy + 0.5) * 180.0 / H;
            if (Math.abs(lat) > 66) continue;                  // source mirrors the poles: no real motion there
            for (int gx = 0; gx < GW; gx++) {
                int cx = gx * cell + cell / 2;
                // template statistics: skip featureless blocks (clear sky / uniform deck)
                double m = 0, m2 = 0; int n = 0;
                for (int dy = -HALF; dy <= HALF; dy++) {
                    int y = cy + dy; if (y < 0 || y >= H) continue;
                    for (int dx = -HALF; dx <= HALF; dx++) {
                        float v = A[y * W + wrap(cx + dx)]; m += v; m2 += v * v; n++;
                    }
                }
                m /= n; double sd = Math.sqrt(Math.max(m2 / n - m * m, 0));
                if (sd < 0.045) continue;

                float[] cost = new float[(2 * SEARCH + 1) * (2 * SEARCH + 1)];
                float best = Float.MAX_VALUE; int bx = 0, by = 0;
                for (int sy = -SEARCH; sy <= SEARCH; sy++) {
                    for (int sx = -SEARCH; sx <= SEARCH; sx++) {
                        float s = 0;
                        for (int dy = -HALF; dy <= HALF; dy += 1) {
                            int y = cy + dy, y2 = y + sy;
                            if (y < 0 || y >= H || y2 < 0 || y2 >= H) { s += 0.25f; continue; }
                            int ra = y * W, rb = y2 * W;
                            for (int dx = -HALF; dx <= HALF; dx++) {
                                s += Math.abs(A[ra + wrap(cx + dx)] - B[rb + wrap(cx + dx + sx)]);
                            }
                        }
                        cost[(sy + SEARCH) * (2 * SEARCH + 1) + sx + SEARCH] = s;
                        if (s < best) { best = s; bx = sx; by = sy; }
                    }
                }
                float zero = cost[SEARCH * (2 * SEARCH + 1) + SEARCH];
                // reject matches that are barely better than "didn't move" — usually noise
                if (best > zero * 0.97f && (bx != 0 || by != 0)) { bx = 0; by = 0; }
                float subx = bx, suby = by;
                if (Math.abs(bx) < SEARCH) subx += parabola(cost, bx - 1, by, bx, by, bx + 1, by);
                if (Math.abs(by) < SEARCH) suby += parabola(cost, bx, by - 1, bx, by, bx, by + 1);
                fx[gy * GW + gx] = subx; fy[gy * GW + gx] = suby; ok[gy * GW + gx] = true;
            }
        }
        median3(fx, ok); median3(fy, ok);
        fill(fx, fy, ok);
        blur(fx); blur(fy);

        // px per interval -> deg per hour. +x px = east, +y px = south.
        double degPerPx = 360.0 / W;
        ByteBuffer out = ByteBuffer.allocateDirect(GW * GH * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < GW * GH; i++) {
            double east = fx[i] * degPerPx / hours, north = -fy[i] * degPerPx / hours;
            out.put(enc(east)).put(enc(north));
        }
        out.position(0);
        return out;
    }

    private static byte enc(double v) {
        long q = Math.round(128 + v / FLOW_MAX * 127);
        return (byte) Math.max(1, Math.min(255, q));
    }

    private static int wrap(int x) { return ((x % W) + W) % W; }

    private static float at(float[] c, int sx, int sy) {
        int s = 2 * SEARCH + 1;
        return c[(sy + SEARCH) * s + sx + SEARCH];
    }

    private static float parabola(float[] c, int x0, int y0, int x1, int y1, int x2, int y2) {
        float l = at(c, x0, y0), m = at(c, x1, y1), r = at(c, x2, y2);
        float d = l - 2 * m + r;
        if (d <= 1e-6f) return 0f;
        return Math.max(-0.5f, Math.min(0.5f, 0.5f * (l - r) / d));
    }

    /** Box-filter to W x H, values in 0..1. */
    static float[] downsample(ByteBuffer src, int sw, int sh) {
        float[] out = new float[W * H];
        int fx = sw / W, fy = sh / H;
        float norm = 1f / (255f * fx * fy);
        byte[] row = new byte[sw];
        float[] acc = new float[W];
        for (int y = 0; y < H; y++) {
            java.util.Arrays.fill(acc, 0);
            for (int k = 0; k < fy; k++) {
                src.position((y * fy + k) * sw);
                src.get(row);
                for (int x = 0; x < sw; x++) acc[x / fx] += row[x] & 0xFF;
            }
            for (int x = 0; x < W; x++) out[y * W + x] = acc[x] * norm;
        }
        src.position(0);
        return out;
    }

    private static void median3(float[] f, boolean[] ok) {
        float[] src = f.clone();
        float[] win = new float[9];
        for (int y = 0; y < GH; y++) for (int x = 0; x < GW; x++) {
            if (!ok[y * GW + x]) continue;
            int n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int yy = y + dy; if (yy < 0 || yy >= GH) continue;
                int i = yy * GW + ((x + dx + GW) % GW);
                if (ok[i]) win[n++] = src[i];
            }
            java.util.Arrays.sort(win, 0, n);
            f[y * GW + x] = win[n / 2];
        }
    }

    /** Fill blocks with no measurement from measured neighbours (iterative diffusion), decaying to 0. */
    private static void fill(float[] fx, float[] fy, boolean[] ok) {
        boolean[] have = ok.clone();
        for (int it = 0; it < 24; it++) {
            boolean[] next = have.clone();
            float[] nx = fx.clone(), ny = fy.clone();
            for (int y = 0; y < GH; y++) for (int x = 0; x < GW; x++) {
                int i = y * GW + x;
                if (have[i]) continue;
                float sx = 0, sy = 0; int n = 0;
                int[][] nb = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (int[] d : nb) {
                    int yy = y + d[1]; if (yy < 0 || yy >= GH) continue;
                    int j = yy * GW + ((x + d[0] + GW) % GW);
                    if (have[j]) { sx += fx[j]; sy += fy[j]; n++; }
                }
                if (n > 0) { nx[i] = 0.85f * sx / n; ny[i] = 0.85f * sy / n; next[i] = true; }
            }
            System.arraycopy(nx, 0, fx, 0, fx.length);
            System.arraycopy(ny, 0, fy, 0, fy.length);
            have = next;
        }
    }

    private static void blur(float[] f) {
        float[] k = {0.25f, 0.5f, 0.25f};
        float[] t = new float[f.length];
        for (int y = 0; y < GH; y++) for (int x = 0; x < GW; x++) {
            float s = 0; for (int d = -1; d <= 1; d++) s += k[d + 1] * f[y * GW + ((x + d + GW) % GW)];
            t[y * GW + x] = s;
        }
        for (int y = 0; y < GH; y++) for (int x = 0; x < GW; x++) {
            float s = 0;
            for (int d = -1; d <= 1; d++) { int yy = Math.max(0, Math.min(GH - 1, y + d)); s += k[d + 1] * t[yy * GW + x]; }
            f[y * GW + x] = s;
        }
    }
}

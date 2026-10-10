package com.chai.earthwall;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Colour scales for the weather views, in the spirit of Ventusky's (256 x 16 RGBA texture, one row per layer). */
final class Palettes {
    private Palettes() {}
    static final int W = 256, ROWS = 16;

    interface Scale { double pos(double v); }               // physical value -> 0..1 (same as the server's encoding)

    // rows: 0 temperature, 1 rain, 2 snow, 3 wind speed, 4 gusts, 5 pressure, 6 thunderstorms, 7 humidity, 8 snow depth, 9 waves
    static ByteBuffer build() {
        ByteBuffer b = ByteBuffer.allocateDirect(W * ROWS * 4).order(ByteOrder.nativeOrder());
        Scale temp = v -> (v + 60) / 110, rate = v -> Math.log1p(v) / Math.log1p(50), wind = v -> v / 40, gust = v -> v / 60,
                pres = v -> (v - 950) / 110, cape = v -> Math.sqrt(v) / Math.sqrt(5000), rh = v -> v / 100,
                snow = v -> Math.sqrt(v) / Math.sqrt(3), wave = v -> v / 15;
        double[][][] rows = {
            {{-60, .45, .25, .55, 1}, {-40, .62, .43, .80, 1}, {-25, .36, .45, .85, 1}, {-10, .40, .70, .95, 1}, {0, .62, .85, .92, 1},
             {5, .45, .80, .62, 1}, {12, .52, .80, .36, 1}, {20, .95, .85, .35, 1}, {27, .96, .60, .25, 1}, {34, .86, .30, .25, 1},
             {42, .66, .15, .35, 1}, {50, .50, .10, .45, 1}},
            {{0, .55, .75, .95, 0}, {0.08, .55, .75, .95, 0}, {0.15, .55, .75, .95, .35}, {0.5, .35, .60, .95, .7}, {2, .20, .40, .90, .85},
             {6, .45, .25, .85, .9}, {15, .85, .25, .65, .95}, {40, .95, .55, .80, 1}},
            {{0, .85, .85, .95, 0}, {0.08, .85, .85, .95, 0}, {0.15, .85, .85, .95, .35}, {0.5, .90, .80, .95, .6}, {2, .95, .70, .95, .8},
             {6, .85, .50, .90, .9}, {20, .70, .30, .85, 1}},
            {{0, .20, .25, .55, 1}, {3, .20, .45, .70, 1}, {6, .20, .65, .65, 1}, {9, .35, .75, .40, 1}, {12, .80, .80, .30, 1},
             {16, .95, .60, .25, 1}, {21, .90, .30, .30, 1}, {28, .75, .20, .55, 1}, {40, .95, .85, .95, 1}},
            {{0, .20, .25, .55, 1}, {3, .20, .45, .70, 1}, {6, .20, .65, .65, 1}, {9, .35, .75, .40, 1}, {12, .80, .80, .30, 1},
             {16, .95, .60, .25, 1}, {21, .90, .30, .30, 1}, {28, .75, .20, .55, 1}, {60, .95, .85, .95, 1}},
            {{950, .55, .25, .65, 1}, {975, .30, .35, .80, 1}, {995, .30, .65, .85, 1}, {1013, .55, .80, .55, 1}, {1025, .95, .85, .40, 1},
             {1040, .95, .55, .30, 1}, {1060, .85, .30, .30, 1}},
            {{0, .95, .95, .50, 0}, {100, .95, .95, .50, 0}, {250, .95, .95, .50, .3}, {800, .98, .85, .30, .6}, {1500, .98, .60, .20, .8},
             {2500, .90, .25, .20, .9}, {4000, .85, .20, .70, 1}},
            {{0, .65, .45, .25, 1}, {30, .85, .75, .40, 1}, {60, .45, .75, .55, 1}, {80, .30, .60, .85, 1}, {100, .25, .35, .85, 1}},
            {{0, .85, .90, 1, 0}, {0.005, .85, .90, 1, 0}, {0.02, .85, .90, 1, .45}, {0.1, .80, .88, 1, .75}, {0.5, .70, .80, 1, .9}, {2, .95, .95, 1, 1}},
            {{0, .10, .20, .45, 1}, {1, .15, .40, .70, 1}, {2.5, .20, .65, .70, 1}, {4, .40, .75, .40, 1}, {6, .90, .80, .30, 1},
             {9, .90, .40, .30, 1}, {15, .75, .20, .60, 1}},
        };
        Scale[] sc = {temp, rate, rate, wind, gust, pres, cape, rh, snow, wave};
        for (int r = 0; r < ROWS; r++) {
            for (int x = 0; x < W; x++) {
                double u = x / (W - 1.0);
                float[] c = {0, 0, 0, 0};
                if (r < rows.length) {
                    double[][] st = rows[r];
                    int k = 0;
                    while (k < st.length - 2 && sc[r].pos(st[k + 1][0]) < u) k++;
                    double p0 = sc[r].pos(st[k][0]), p1 = sc[r].pos(st[k + 1][0]);
                    double f = Math.max(0, Math.min(1, (u - p0) / Math.max(1e-9, p1 - p0)));
                    for (int i = 0; i < 4; i++) c[i] = (float) (st[k][i + 1] + f * (st[k + 1][i + 1] - st[k][i + 1]));
                }
                b.put((byte) Math.round(c[0] * 255)).put((byte) Math.round(c[1] * 255)).put((byte) Math.round(c[2] * 255)).put((byte) Math.round(c[3] * 255));
            }
        }
        b.position(0);
        return b;
    }
}

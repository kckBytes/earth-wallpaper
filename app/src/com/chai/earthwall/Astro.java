package com.chai.earthwall;

/** Low-precision solar ephemeris (≈0.01° — far below a pixel) and camera basis. */
final class Astro {
    private Astro() {}

    /**
     * Writes the unit vector toward the Sun in Earth-fixed (ECEF) coordinates into {@code outSun}
     * and returns Greenwich mean sidereal time in radians.
     */
    static double sun(long utcMillis, float[] outSun) {
        double d = utcMillis / 86400000.0 + 2440587.5 - 2451545.0;          // days since J2000.0
        double g = Math.toRadians(357.529 + 0.98560028 * d);                 // mean anomaly
        double q = 280.459 + 0.98564736 * d;                                 // mean longitude
        double lam = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.00000036 * d);                // obliquity
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lam));
        double gmst = Math.toRadians(280.46061837 + 360.98564736629 * d) % (2 * Math.PI);
        if (gmst < 0) gmst += 2 * Math.PI;
        double lon = ra - gmst;                                              // sub-solar longitude
        outSun[0] = (float) (Math.cos(dec) * Math.cos(lon));
        outSun[1] = (float) (Math.cos(dec) * Math.sin(lon));
        outSun[2] = (float) Math.sin(dec);
        return gmst;
    }

    /**
     * Column-major 3x3 mapping view space (+x right, +y up, +z toward camera) to ECEF, for a camera
     * hanging over (latDeg, lonDeg) with north up on screen.
     */
    static void viewToEcef(double latDeg, double lonDeg, float[] m) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double zx = Math.cos(la) * Math.cos(lo), zy = Math.cos(la) * Math.sin(lo), zz = Math.sin(la);
        double yx = -zz * zx, yy = -zz * zy, yz = 1 - zz * zz;
        double n = Math.sqrt(yx * yx + yy * yy + yz * yz);
        yx /= n; yy /= n; yz /= n;
        double xx = yy * zz - yz * zy, xy = yz * zx - yx * zz, xz = yx * zy - yy * zx;
        m[0] = (float) xx; m[1] = (float) xy; m[2] = (float) xz;
        m[3] = (float) yx; m[4] = (float) yy; m[5] = (float) yz;
        m[6] = (float) zx; m[7] = (float) zy; m[8] = (float) zz;
    }
}

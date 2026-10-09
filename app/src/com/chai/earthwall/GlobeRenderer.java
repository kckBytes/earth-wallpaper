package com.chai.earthwall;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Owns the GL program and textures. Every method must be called on the GL thread. */
final class GlobeRenderer {
    private static final String TAG = "EarthWall";
    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;

    static final int DAY = 0, LIGHTS = 1, WATER = 2;
    // tex: 0 day, 1 lights, 2 water, 3/4 cloud slots, 5/6 flow slots (slot k uses tex[3+k], tex[5+k])
    private final int[] tex = new int[7];
    private final long[] slotTime = new long[2];
    private int cur = 0, prev = 0;
    private boolean haveDay, haveLights, haveWater, haveClouds;
    private float anisotropy = 1f;

    private int prog, vbo;
    private int uRes, uCenter, uRadius, uCamDist, uView, uSun, uGmst, uCloudMix, uFade, uTime,
            uStarShift, uStars, uAdvect, uAdvectPrev, uMarkerPx, uMarkerAmt, uMarkerSize, uPass, uRect;
    private int labelProg, uLabRect, uLabAlpha, labelTex = 0, labelW, labelH;
    float renderScale = 1f;                        // < 1: shade into an offscreen buffer, then upscale
    private int fbo, fboTex, fboW, fboH;

    /** Everything that changes per frame. */
    static final class Frame {
        int w, h;
        float cx, cy, radius, camDist, gmst, cloudMix, fade, time, starDx, starDy;
        float markerX, markerY, markerAmt, markerSize;
        float labelAmt, labelCx, labelCy;          // city-name label (GL px, centre)
        boolean stars;
        long nowMs, cloudNowMs;           // cloudNowMs: clock for cloud motion (runs behind during a time-lapse)
        final float[] view = new float[9], sun = new float[3];
    }

    boolean init(AssetManager am) {
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, readAsset(am, "globe.vert"));
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, readAsset(am, "globe.frag"));
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glBindAttribLocation(prog, 0, "aPos");
            GLES20.glLinkProgram(prog);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(prog));
        } catch (Exception e) {
            Log.e(TAG, "shader setup failed", e);
            return false;
        }
        GLES20.glUseProgram(prog);
        uRes = loc("uRes"); uCenter = loc("uCenter"); uRadius = loc("uRadius"); uCamDist = loc("uCamDist");
        uView = loc("uViewToEcef"); uSun = loc("uSun"); uGmst = loc("uGmst"); uCloudMix = loc("uCloudMix");
        uFade = loc("uFade"); uTime = loc("uTime"); uStarShift = loc("uStarShift"); uStars = loc("uStars");
        uAdvect = loc("uAdvect"); uAdvectPrev = loc("uAdvectPrev");
        uMarkerPx = loc("uMarkerPx"); uMarkerAmt = loc("uMarkerAmt"); uMarkerSize = loc("uMarkerSize");
        uPass = loc("uPass"); uRect = loc("uRect");
        String[] samplers = {"uDay", "uLights", "uWater", "uClouds", "uCloudsPrev", "uFlow", "uFlowPrev"};
        for (int i = 0; i < samplers.length; i++) GLES20.glUniform1i(loc(samplers[i]), i);

        float[] tri = {0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f};   // unit quad
        FloatBuffer fb = ByteBuffer.allocateDirect(tri.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(tri).position(0);
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        vbo = b[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, tri.length * 4, fb, GLES20.GL_STATIC_DRAW);

        GLES20.glGenTextures(7, tex, 0);
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (ext != null && ext.contains("GL_EXT_texture_filter_anisotropic")) {
            float[] mx = new float[1];
            GLES20.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY, mx, 0);
            anisotropy = Math.min(4f, mx[0]);
        }
        uploadFlow(5, FlowEstimator.still());
        uploadFlow(6, FlowEstimator.still());
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, readAsset(am, "globe.vert"));
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, readAsset(am, "label.frag"));
            labelProg = GLES20.glCreateProgram();
            GLES20.glAttachShader(labelProg, vs);
            GLES20.glAttachShader(labelProg, fs);
            GLES20.glBindAttribLocation(labelProg, 0, "aPos");
            GLES20.glLinkProgram(labelProg);
            uLabRect = GLES20.glGetUniformLocation(labelProg, "uRect");
            uLabAlpha = GLES20.glGetUniformLocation(labelProg, "uAlpha");
            GLES20.glUseProgram(labelProg);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(labelProg, "uTex"), 0);
        } catch (Exception e) { Log.e(TAG, "label shader failed", e); labelProg = 0; }
        return true;
    }

    boolean ready() { return haveDay && haveLights && haveWater && haveClouds; }

    void uploadDay(Bitmap bmp) {
        bind(tex[DAY]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        finishTexture(true);
        haveDay = true;
    }

    void uploadSingle(int unit, ByteBuffer data, int w, int h) {
        bind(tex[unit]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED,
                GLES20.GL_UNSIGNED_BYTE, data);
        finishTexture(true);
        if (unit == LIGHTS) haveLights = true;
        if (unit == WATER) haveWater = true;
    }

    /**
     * New cloud map + its motion field: goes into the spare slot and becomes current; the old current
     * (still advecting along its own flow) becomes the crossfade source, so a fresh map "corrects" the
     * predicted clouds smoothly instead of popping.
     */
    void pushClouds(CloudUpdater.CloudFrame f) {
        int slot = haveClouds ? 1 - cur : 0;
        bind(tex[3 + slot]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, f.img.w, f.img.h, 0, GLES30.GL_RED,
                GLES20.GL_UNSIGNED_BYTE, f.img.data);
        finishTexture(true);
        uploadFlow(5 + slot, f.flow);
        slotTime[slot] = f.timeMs;
        prev = haveClouds ? cur : slot;
        cur = slot;
        haveClouds = true;
    }

    private void uploadFlow(int unitTex, ByteBuffer flow) {
        bind(tex[unitTex]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, FlowEstimator.GW, FlowEstimator.GH, 0,
                GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, flow);
        finishTexture(false);
    }

    /** Replace the city-name label texture (premultiplied ARGB bitmap). */
    void setLabel(Bitmap b) {
        if (labelTex == 0) { int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); labelTex = t[0]; }
        bind(labelTex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        labelW = b.getWidth(); labelH = b.getHeight();
    }

    private static float hoursSince(long t, long now) {
        if (t <= 0) return 0f;
        return Math.max(-12f, Math.min(6f, (now - t) / 3_600_000f));    // negative = run the flow backward (time-lapse start)
    }

    private void ensureFbo(int w, int h) {
        if (fbo != 0 && fboW == w && fboH == h) return;
        if (fbo == 0) {
            int[] t = new int[1];
            GLES20.glGenFramebuffers(1, t, 0); fbo = t[0];
            GLES20.glGenTextures(1, t, 0); fboTex = t[0];
        }
        bind(fboTex);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "offscreen buffer incomplete; rendering at full resolution");
            renderScale = 1f;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        fboW = w; fboH = h;
    }

    void draw(Frame f) {
        GLES20.glViewport(0, 0, f.w, f.h);
        if (!ready()) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        // Offscreen at reduced resolution: every px-space quantity is scaled by s.
        float s = renderScale;
        int fw = f.w, fh = f.h;
        if (s < 0.999f) {
            fw = Math.max(1, Math.round(f.w * s)); fh = Math.max(1, Math.round(f.h * s));
            ensureFbo(fw, fh);
        }
        boolean off = renderScale < 0.999f;
        if (!off) s = 1f;
        if (off) GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fw, fh);
        GLES20.glUseProgram(prog);
        int[] units = {tex[DAY], tex[LIGHTS], tex[WATER], tex[3 + cur], tex[3 + prev], tex[5 + cur], tex[5 + prev]};
        for (int i = 0; i < units.length; i++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, units[i]);
        }
        GLES20.glUniform2f(uRes, fw, fh);
        GLES20.glUniform2f(uCenter, f.cx * s, f.cy * s);
        GLES20.glUniform1f(uRadius, f.radius * s);
        GLES20.glUniform1f(uCamDist, f.camDist);
        GLES20.glUniformMatrix3fv(uView, 1, false, f.view, 0);
        GLES20.glUniform3f(uSun, f.sun[0], f.sun[1], f.sun[2]);
        GLES20.glUniform1f(uGmst, f.gmst);
        GLES20.glUniform1f(uCloudMix, f.cloudMix);
        GLES20.glUniform1f(uFade, f.fade);
        GLES20.glUniform1f(uTime, f.time);
        GLES20.glUniform2f(uStarShift, f.starDx * s, f.starDy * s);
        GLES20.glUniform1f(uStars, f.stars ? 1f : 0f);
        GLES20.glUniform1f(uAdvect, hoursSince(slotTime[cur], f.cloudNowMs));
        GLES20.glUniform1f(uAdvectPrev, hoursSince(slotTime[prev], f.cloudNowMs));
        GLES20.glUniform2f(uMarkerPx, f.markerX * s, f.markerY * s);
        GLES20.glUniform1f(uMarkerAmt, f.markerAmt);
        GLES20.glUniform1f(uMarkerSize, f.markerSize * s);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);

        // pass 0: stars over the whole screen (cheap)
        GLES30.glUniform1i(uPass, 0);
        GLES20.glUniform4f(uRect, -1f, -1f, 1f, 1f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        // pass 1: full shader only where the globe and its atmosphere can be
        float m = f.radius * 1.12f;
        float x0 = clampNdc((f.cx - m) / f.w * 2f - 1f), x1 = clampNdc((f.cx + m) / f.w * 2f - 1f);
        float y0 = clampNdc((f.cy - m) / f.h * 2f - 1f), y1 = clampNdc((f.cy + m) / f.h * 2f - 1f);
        if (x1 > x0 && y1 > y0) {
            GLES30.glUniform1i(uPass, 1);
            GLES20.glUniform4f(uRect, x0, y0, x1, y1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        }
        if (off) {                                     // upscale to the window
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo);
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glBlitFramebuffer(0, 0, fw, fh, 0, 0, f.w, f.h, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, f.w, f.h);
        }
        drawLabel(f);
    }

    private static float clampNdc(float v) { return Math.max(-1f, Math.min(1f, v)); }

    private void drawLabel(Frame f) {
        if (f.labelAmt <= 0.003f || labelTex == 0 || labelProg == 0) return;
        GLES20.glUseProgram(labelProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTex);
        float x0 = (f.labelCx - labelW * 0.5f) / f.w * 2f - 1f, x1 = (f.labelCx + labelW * 0.5f) / f.w * 2f - 1f;
        float y0 = (f.labelCy - labelH * 0.5f) / f.h * 2f - 1f, y1 = (f.labelCy + labelH * 0.5f) / f.h * 2f - 1f;
        GLES20.glUniform4f(uLabRect, x0, y0, x1, y1);
        GLES20.glUniform1f(uLabAlpha, f.labelAmt);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    void release() {
        if (fbo != 0) { GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0); GLES20.glDeleteTextures(1, new int[]{fboTex}, 0); fbo = 0; }
        GLES20.glDeleteTextures(7, tex, 0);
        GLES20.glDeleteBuffers(1, new int[]{vbo}, 0);
        GLES20.glDeleteProgram(prog);
        haveDay = haveLights = haveWater = haveClouds = false;
    }

    // ------------------------------------------------------------------ helpers
    private void bind(int t) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
    }

    private void finishTexture(boolean mips) {
        if (mips) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                mips ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (mips && anisotropy > 1f) GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, anisotropy);
    }

    private int loc(String n) { return GLES20.glGetUniformLocation(prog, n); }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    static String readAsset(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}

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
            uStarShift, uStars, uAdvect, uAdvectPrev, uMarkerPx, uMarkerAmt, uMarkerSize, uPass, uRect, uMode;
    // replay: a ring of three frame textures (A older, B newer, C being filled in slices for the next step)
    // plus the motion A->B. Swapped in for the cloud slots while a replay plays.
    private final int[] rtex = new int[4];             // 0..2 frame ring, 3 motion
    private int ra = 0, rb = 1, rc = 2, rW, rH;
    private int labelProg, uLabRect, uLabAlpha, labelTex = 0, labelW, labelH;
    static final int MAX_STORMS = 32;
    private final int[] stormTex = new int[MAX_STORMS];
    private final float[] stormW = new float[MAX_STORMS], stormH = new float[MAX_STORMS],
            stormAx = new float[MAX_STORMS], stormAy = new float[MAX_STORMS];
    private int stormTexN;
    // weather views: two model sheets (the times around "now") + palettes; wind streaks
    private final int[] wxTex = new int[3];            // 0, 1 sheets; 2 palette
    private int uWxMix, uWxChan, uWxPal, uWxClouds, uWxAmt, uWxStyle;
    private int streakProg, sView, sCenter, sRadius, sCamDist, sRes, sAlpha, sColor, sRound, sPoint, streakVbo, streakIbo, streakIdx, dotVbo, trackVbo;
    private int sSun, sHalo, sInk, sOffset;
    // ribbons: one instanced quad per trail segment
    private int ribProg, rView, rCenter, rRadius, rCamDist, rRes, rSun, rK, rWN, rWD, rOut, rAlpha, rDark, rOutA, cornerVbo;
    private float maxLine = 1f;                       // widest line this GPU draws (for the streaks' dark outline)
    static final int MAX_DOTS = 4096;
    static final int MAX_TRACK = 4096;                   // hurricane paths: line vertices (pairs) + their 6-hourly fixes
    private int auroraTex, uAuroraAmt, bordersTex, uBorderAmt;
    float renderScale = 1f;                        // < 1: shade into an offscreen buffer, then upscale
    private int fbo, fboTex, fboW, fboH;

    /** Everything that changes per frame. */
    static final class Frame {
        int w, h;
        float cx, cy, radius, camDist, gmst, cloudMix, fade, time, starDx, starDy;
        float markerX, markerY, markerAmt, markerSize;
        float labelAmt, labelCx, labelCy;          // city-name label (GL px, centre)
        float wxMix, wxClouds, wxAmt; int wxChan, wxPal, wxStyle;  // weather view: blend between the two sheets, channel, palette row
        java.nio.FloatBuffer streaks; float streakAlpha;   // wind streak points (null = none)
        float sDayW = 3.5f, sNightW = 1.6f, sOutW = 1f, sOutA = 0.5f, sDark = 1f;   // streak look (tuning panel)
        java.nio.FloatBuffer fireDots, boltDots; int fireN, boltN;   // fires / lightning: xyz + opacity per dot
        float auroraAmt, borderAmt;
        int stormN;                                // storm labels: anchor (storm centre, GL px) and opacity
        final float[] stormX = new float[MAX_STORMS], stormY = new float[MAX_STORMS], stormAmt = new float[MAX_STORMS];
        final float[] stormScale = new float[MAX_STORMS];          // < 1: drawn smaller so it fits among the others
        java.nio.FloatBuffer trackLines, trackFixes; int trackN, fixN;   // hurricane paths (GL_LINES pairs) and fixes
        int trackEndedN, fixEndedN;                // the first ones belong to storms that have ended (drawn red)
        boolean stars;
        long nowMs, cloudNowMs;           // cloudNowMs: clock for cloud motion (runs behind during a time-lapse)
        int mode;                         // shader view: 0 natural, 1 infrared, 2 sea temperature, 3 night lights
        boolean replay;                   // draw the replay pair instead of the live slots
        boolean tail;                     // just after a replay: crossfade its last frame into the live map
        float tailMix, tailHours;         // 0..1 toward live; hours from that last frame to now
        float replayMix, replayHours;     // position between frame A and B (0..1), hours between them
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
        uPass = loc("uPass"); uRect = loc("uRect"); uMode = loc("uMode");
        uWxMix = loc("uWxMix"); uWxChan = loc("uWxChan"); uWxPal = loc("uWxPal"); uWxClouds = loc("uWxClouds"); uWxAmt = loc("uWxAmt"); uWxStyle = loc("uWxStyle");
        uAuroraAmt = loc("uAuroraAmt"); uBorderAmt = loc("uBorderAmt");
        String[] samplers = {"uDay", "uLights", "uWater", "uClouds", "uCloudsPrev", "uFlow", "uFlowPrev", "uWxA", "uWxB", "uPal", "uAurora", "uBorders"};
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
        GLES20.glGenTextures(4, rtex, 0);
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (ext != null && ext.contains("GL_EXT_texture_filter_anisotropic")) {
            float[] mx = new float[1];
            GLES20.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY, mx, 0);
            anisotropy = Math.min(4f, mx[0]);
        }
        uploadFlow(5, FlowEstimator.still());
        uploadFlow(6, FlowEstimator.still());
        GLES20.glGenTextures(3, wxTex, 0);
        int[] at = new int[1]; GLES20.glGenTextures(1, at, 0); auroraTex = at[0];
        uploadAurora(new byte[4], 2, 2);
        int[] bt = new int[1]; GLES20.glGenTextures(1, bt, 0); bordersTex = bt[0];
        ByteBuffer none = ByteBuffer.allocateDirect(4); none.position(0);
        uploadBorders(none, 2, 2);
        byte[] one = new byte[4 * 3];
        for (int k = 0; k < 2; k++) uploadWx(k, one, 2, 2);
        bind(wxTex[2]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, Palettes.W, Palettes.ROWS, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, Palettes.build());
        clampLinear();
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, readAsset(am, "streak.vert"));
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, readAsset(am, "streak.frag"));
            streakProg = GLES20.glCreateProgram();
            GLES20.glAttachShader(streakProg, vs); GLES20.glAttachShader(streakProg, fs);
            GLES20.glBindAttribLocation(streakProg, 0, "aP");
            GLES20.glLinkProgram(streakProg);
            int[] ok2 = new int[1];
            GLES20.glGetProgramiv(streakProg, GLES20.GL_LINK_STATUS, ok2, 0);
            if (ok2[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(streakProg));
            sView = GLES20.glGetUniformLocation(streakProg, "uView"); sCenter = GLES20.glGetUniformLocation(streakProg, "uCenter");
            sRadius = GLES20.glGetUniformLocation(streakProg, "uRadius"); sCamDist = GLES20.glGetUniformLocation(streakProg, "uCamDist");
            sRes = GLES20.glGetUniformLocation(streakProg, "uRes"); sAlpha = GLES20.glGetUniformLocation(streakProg, "uAlpha");
            sColor = GLES20.glGetUniformLocation(streakProg, "uColor"); sRound = GLES20.glGetUniformLocation(streakProg, "uRound");
            sPoint = GLES20.glGetUniformLocation(streakProg, "uPointSize");
            sSun = GLES20.glGetUniformLocation(streakProg, "uSun"); sHalo = GLES20.glGetUniformLocation(streakProg, "uHalo");
            sInk = GLES20.glGetUniformLocation(streakProg, "uInk");
            sOffset = GLES20.glGetUniformLocation(streakProg, "uOffsetPx");
            float[] lw = new float[2];
            GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, lw, 0);
            maxLine = lw[1];
            int[] bb = new int[2];
            GLES20.glGenBuffers(2, bb, 0);
            streakVbo = bb[0]; streakIbo = bb[1];
            int[] tb = new int[1]; GLES20.glGenBuffers(1, tb, 0); trackVbo = tb[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, trackVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_TRACK * 2 * 16, null, GLES20.GL_STREAM_DRAW);
            int[] db = new int[1]; GLES20.glGenBuffers(1, db, 0); dotVbo = db[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dotVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_DOTS * 16, null, GLES20.GL_STREAM_DRAW);
            int n = WindStreaks.N, k = WindStreaks.K;
            java.nio.ShortBuffer ib = ByteBuffer.allocateDirect(n * (k + 1) * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int i = 0; i < n; i++) { for (int j = 0; j < k; j++) ib.put((short) (i * k + j)); ib.put((short) 0xFFFF); }
            ib.position(0);
            streakIdx = n * (k + 1);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, streakIbo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, streakIdx * 2, ib, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, streakVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * k * 16, null, GLES20.GL_STREAM_DRAW);
        } catch (Exception e) { Log.e(TAG, "streak shader failed", e); streakProg = 0; }
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, readAsset(am, "ribbon.vert"));
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, readAsset(am, "ribbon.frag"));
            ribProg = GLES20.glCreateProgram();
            GLES20.glAttachShader(ribProg, vs); GLES20.glAttachShader(ribProg, fs);
            GLES20.glBindAttribLocation(ribProg, 0, "aCorner");
            GLES20.glBindAttribLocation(ribProg, 1, "aA");
            GLES20.glBindAttribLocation(ribProg, 2, "aB");
            GLES20.glLinkProgram(ribProg);
            int[] ok3 = new int[1];
            GLES20.glGetProgramiv(ribProg, GLES20.GL_LINK_STATUS, ok3, 0);
            if (ok3[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(ribProg));
            rView = GLES20.glGetUniformLocation(ribProg, "uView"); rCenter = GLES20.glGetUniformLocation(ribProg, "uCenter");
            rRadius = GLES20.glGetUniformLocation(ribProg, "uRadius"); rCamDist = GLES20.glGetUniformLocation(ribProg, "uCamDist");
            rRes = GLES20.glGetUniformLocation(ribProg, "uRes"); rSun = GLES20.glGetUniformLocation(ribProg, "uSun");
            rK = GLES20.glGetUniformLocation(ribProg, "uK"); rWN = GLES20.glGetUniformLocation(ribProg, "uWNight");
            rWD = GLES20.glGetUniformLocation(ribProg, "uWDay"); rOut = GLES20.glGetUniformLocation(ribProg, "uOutline");
            rAlpha = GLES20.glGetUniformLocation(ribProg, "uAlpha"); rDark = GLES20.glGetUniformLocation(ribProg, "uDark");
            rOutA = GLES20.glGetUniformLocation(ribProg, "uOutA");
            float[] corners = {0f, -1f, 0f, 1f, 1f, -1f, 1f, 1f};       // the quad template (triangle strip)
            java.nio.FloatBuffer cb = ByteBuffer.allocateDirect(corners.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            cb.put(corners).position(0);
            int[] cv = new int[1]; GLES20.glGenBuffers(1, cv, 0); cornerVbo = cv[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cornerVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, corners.length * 4, cb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            Log.i(TAG, "streaks: ribbons ready (" + (WindStreaks.N * WindStreaks.K - 1) + " segments, 2 triangles each)");
        } catch (Exception e) { Log.e(TAG, "ribbon shader failed, using lines", e); ribProg = 0; }
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

    /**
     * Replay frames are uploaded in horizontal slices (texSubImage) spread over several display frames,
     * into the third texture of the ring, so stepping to the next half-hour never stalls drawing.
     * No mipmaps: the 2048-px frames are about as dense as the globe on screen, so they're never minified much.
     */
    void replayBegin(int w, int h) {
        rW = w; rH = h; ra = 0; rb = 1; rc = 2;
        for (int i = 0; i < 3; i++) {
            bind(rtex[i]);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null);
            finishTexture(false);
        }
    }
    /** Upload rows [y0, y0+rows) of img into ring slot 0 = A, 1 = B, 2 = C (the one being prepared). */
    void replaySlice(int slot, Images.Single img, int y0, int rows) {
        int id = rtex[slot == 0 ? ra : slot == 1 ? rb : rc];
        bind(id);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer d = img.data.duplicate();
        d.position(y0 * img.w);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, y0, img.w, rows, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, d);
    }
    void uploadReplayFlow(ByteBuffer flow) { uploadFlowTo(rtex[3], flow); }
    /** Step: B becomes A, the prepared C becomes B, the old A is free for the next frame. */
    void rotateReplay() { int t = ra; ra = rb; rb = rc; rc = t; }

    private void uploadFlow(int unitTex, ByteBuffer flow) { uploadFlowTo(tex[unitTex], flow); }

    private void uploadFlowTo(int texId, ByteBuffer flow) {
        bind(texId);
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

    /** A weather sheet (RGB bytes, w x h) into slot 0 (earlier time) or 1 (later). */
    void uploadWx(int slot, byte[] rgb, int w, int h) {
        bind(wxTex[slot]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgb));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Country borders + coastlines line mask (R8, equirectangular, mipmapped so lines stay thin and smooth). */
    void uploadBorders(ByteBuffer l8, int w, int h) {
        bind(bordersTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, l8);
        finishTexture(true);
    }

    /** NOAA aurora forecast grid (L8, w x h, lon -180 at the left, north at the top). */
    void uploadAurora(byte[] l8, int w, int h) {
        bind(auroraTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(l8));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void clampLinear() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Ribbons: one instanced quad per trail segment; width, ink and outline computed on the GPU in one pass. */
    private boolean drawRibbons(Frame f) {
        if (ribProg == 0 || streakVbo == 0) return false;
        GLES20.glUseProgram(ribProg);
        GLES20.glUniformMatrix3fv(rView, 1, false, f.view, 0);
        GLES20.glUniform2f(rCenter, f.cx, f.cy);
        GLES20.glUniform1f(rRadius, f.radius);
        GLES20.glUniform1f(rCamDist, f.camDist);
        GLES20.glUniform2f(rRes, f.w, f.h);
        GLES20.glUniform3f(rSun, f.sun[0], f.sun[1], f.sun[2]);
        GLES30.glUniform1i(rK, WindStreaks.K);
        float scale = Math.max(0.75f, Math.min(1.25f, f.radius / 650f));   // a touch thinner when the globe is small
        GLES20.glUniform1f(rWN, f.sNightW * scale);
        GLES20.glUniform1f(rWD, f.sDayW * scale);
        GLES20.glUniform1f(rOut, f.sOutW);
        GLES20.glUniform1f(rAlpha, f.streakAlpha);
        GLES20.glUniform1f(rDark, f.sDark);
        GLES20.glUniform1f(rOutA, f.sOutA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, streakVbo);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, WindStreaks.N * WindStreaks.K * 16, f.streaks);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 16, 0);     // A = point i
        GLES30.glVertexAttribDivisor(1, 1);
        GLES20.glEnableVertexAttribArray(2);
        GLES20.glVertexAttribPointer(2, 4, GLES20.GL_FLOAT, false, 16, 16);    // B = point i + 1
        GLES30.glVertexAttribDivisor(2, 1);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cornerVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES30.glVertexAttribDivisor(0, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, WindStreaks.N * WindStreaks.K - 1);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES30.glVertexAttribDivisor(1, 0); GLES30.glVertexAttribDivisor(2, 0);   // leave state as the other passes expect
        GLES20.glDisableVertexAttribArray(1); GLES20.glDisableVertexAttribArray(2);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);           // the quad, for the label passes
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);   // re-point attribute 0 at it (pointers keep the old buffer)
        return true;
    }

    private void drawStreaks(Frame f) {
        if (f.streaks == null || f.streakAlpha <= 0.003f) return;
        if (drawRibbons(f)) return;
        if (streakProg == 0) return;
        GLES20.glUseProgram(streakProg);
        GLES20.glUniformMatrix3fv(sView, 1, false, f.view, 0);
        GLES20.glUniform2f(sCenter, f.cx, f.cy);
        GLES20.glUniform1f(sRadius, f.radius);
        GLES20.glUniform1f(sCamDist, f.camDist);
        GLES20.glUniform2f(sRes, f.w, f.h);
        GLES20.glUniform1f(sAlpha, f.streakAlpha);
        GLES20.glUniform3f(sColor, 1f, 1f, 1f);
        GLES30.glUniform1i(sRound, 0);
        GLES20.glUniform3f(sSun, f.sun[0], f.sun[1], f.sun[2]);
        GLES20.glUniform1f(sPoint, 1f);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, streakVbo);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, WindStreaks.N * WindStreaks.K * 16, f.streaks);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, streakIbo);
        GLES20.glEnable(GLES30.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        float core = Math.max(1f, Math.min(2f, f.radius / 450f));
        GLES20.glLineWidth(core);
        GLES20.glUniform1f(sInk, 1f);
        // the outline (by day): the same line nudged a pixel each way, drawn underneath; works on any GPU
        GLES30.glUniform1i(sHalo, 1);
        float o = Math.max(1f, core * 0.9f);
        float[][] offs = {{o, 0}, {-o, 0}, {0, o}, {0, -o}};
        for (float[] d : offs) {
            GLES20.glUniform2f(sOffset, d[0], d[1]);
            GLES20.glDrawElements(GLES20.GL_LINE_STRIP, streakIdx, GLES20.GL_UNSIGNED_SHORT, 0);
        }
        // the line itself: dark ink by day, white glow by night
        GLES30.glUniform1i(sHalo, 0);
        GLES20.glUniform2f(sOffset, 0f, 0f);
        GLES20.glDrawElements(GLES20.GL_LINE_STRIP, streakIdx, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES30.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);           // the quad, for the label passes
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
    }

    /** Hurricane / cyclone paths: where it has been (6-hourly fixes, older = fainter) and an arrow where it's heading. */
    private void drawTracks(Frame f) {
        if (streakProg == 0 || f.trackN == 0) return;
        GLES20.glUseProgram(streakProg);
        GLES20.glUniformMatrix3fv(sView, 1, false, f.view, 0);
        GLES20.glUniform2f(sCenter, f.cx, f.cy);
        GLES20.glUniform1f(sRadius, f.radius);
        GLES20.glUniform1f(sCamDist, f.camDist);
        GLES20.glUniform2f(sRes, f.w, f.h);
        GLES30.glUniform1i(sHalo, 0);
        GLES20.glUniform1f(sInk, 0f);
        GLES20.glUniform2f(sOffset, 0f, 0f);
        GLES20.glUniform1f(sAlpha, f.fade);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, trackVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, f.trackN * 16, f.trackLines);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
        GLES30.glUniform1i(sRound, 0);
        GLES20.glUniform1f(sPoint, 1f);
        // ended storms in red, live ones in the cockpit green (the same colours as their labels), on a black outline
        float lw = Math.min(maxLine - 3f, Math.max(2f, f.w / 420f));
        GLES20.glUniform3f(sColor, 0f, 0f, 0f);
        GLES20.glLineWidth(Math.max(1f, Math.min(maxLine, lw + 3f)));
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, f.trackN);
        GLES20.glLineWidth(Math.max(1f, lw));
        if (f.trackEndedN > 0) { GLES20.glUniform3f(sColor, 1f, 0.29f, 0.24f); GLES20.glDrawArrays(GLES20.GL_LINES, 0, f.trackEndedN); }
        if (f.trackN > f.trackEndedN) { GLES20.glUniform3f(sColor, 0.36f, 1f, 0.53f); GLES20.glDrawArrays(GLES20.GL_LINES, f.trackEndedN, f.trackN - f.trackEndedN); }
        if (f.fixN > 0) {
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, f.trackN * 16, f.fixN * 16, f.trackFixes);
            GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, f.trackN * 16);
            GLES30.glUniform1i(sRound, 1);
            GLES20.glUniform1f(sPoint, Math.max(f.w / 150f, f.radius / 100f));
            GLES20.glUniform3f(sColor, 0f, 0f, 0f);
            GLES20.glUniform1f(sPoint, Math.max(f.w / 150f, f.radius / 100f) + 4f);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, f.fixN);
            GLES20.glUniform1f(sPoint, Math.max(f.w / 150f, f.radius / 100f));
            if (f.fixEndedN > 0) { GLES20.glUniform3f(sColor, 1f, 0.29f, 0.24f); GLES20.glDrawArrays(GLES20.GL_POINTS, 0, f.fixEndedN); }
            if (f.fixN > f.fixEndedN) { GLES20.glUniform3f(sColor, 0.36f, 1f, 0.53f); GLES20.glDrawArrays(GLES20.GL_POINTS, f.fixEndedN, f.fixN - f.fixEndedN); }
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
    }

    private void drawDots(Frame f) {
        if (streakProg == 0 || (f.fireN == 0 && f.boltN == 0)) return;
        GLES20.glUseProgram(streakProg);
        GLES20.glUniformMatrix3fv(sView, 1, false, f.view, 0);
        GLES20.glUniform2f(sCenter, f.cx, f.cy);
        GLES20.glUniform1f(sRadius, f.radius);
        GLES20.glUniform1f(sCamDist, f.camDist);
        GLES20.glUniform2f(sRes, f.w, f.h);
        GLES30.glUniform1i(sRound, 1);
        GLES30.glUniform1i(sHalo, 0);
        GLES20.glUniform1f(sInk, 0f);                        // dots keep their own colours
        GLES20.glUniform2f(sOffset, 0f, 0f);
        GLES20.glUniform1f(sAlpha, f.fade);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);         // dots glow
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dotVbo);
        GLES20.glEnableVertexAttribArray(0);
        float s = Math.max(f.w / 180f, f.radius / 120f);          // never smaller than ~2 dp, even on the whole globe
        if (f.fireN > 0) {
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, f.fireN * 16, f.fireDots);
            GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glUniform3f(sColor, 1f, 0.45f, 0.12f);
            GLES20.glUniform1f(sPoint, s);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, f.fireN);
        }
        if (f.boltN > 0) {
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, f.boltN * 16, f.boltDots);
            GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glUniform3f(sColor, 0.85f, 0.9f, 1f);
            GLES20.glUniform1f(sPoint, s * 1.4f);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, f.boltN);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
    }

    /** Replace the storm label textures; ax/ay = where the storm mark sits in each bitmap (px from top-left). */
    void setStormLabels(Bitmap[] b, float[] ax, float[] ay) {
        if (stormTexN > 0) GLES20.glDeleteTextures(stormTexN, stormTex, 0);
        stormTexN = Math.min(b.length, MAX_STORMS);
        if (stormTexN == 0) return;
        GLES20.glGenTextures(stormTexN, stormTex, 0);
        for (int i = 0; i < stormTexN; i++) {
            bind(stormTex[i]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b[i], 0);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            stormW[i] = b[i].getWidth(); stormH[i] = b[i].getHeight(); stormAx[i] = ax[i]; stormAy[i] = ay[i];
        }
    }

    private void drawStorms(Frame f) {
        if (labelProg == 0 || stormTexN == 0) return;
        boolean any = false;
        for (int i = 0; i < Math.min(f.stormN, stormTexN); i++) {
            if (f.stormAmt[i] <= 0.003f) continue;
            if (!any) {
                GLES20.glUseProgram(labelProg);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                any = true;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stormTex[i]);
            float k = f.stormScale[i] > 0f ? f.stormScale[i] : 1f;
            float left = f.stormX[i] - stormAx[i] * k, top = f.stormY[i] + stormAy[i] * k;
            GLES20.glUniform4f(uLabRect, left / f.w * 2f - 1f, (top - stormH[i] * k) / f.h * 2f - 1f,
                    (left + stormW[i] * k) / f.w * 2f - 1f, top / f.h * 2f - 1f);
            GLES20.glUniform1f(uLabAlpha, f.stormAmt[i]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        }
        if (any) GLES20.glDisable(GLES20.GL_BLEND);
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
        int[] units0 = f.replay
                ? new int[]{tex[DAY], tex[LIGHTS], tex[WATER], rtex[rb], rtex[ra], rtex[3], rtex[3]}
                : f.tail
                ? new int[]{tex[DAY], tex[LIGHTS], tex[WATER], tex[3 + cur], rtex[rb], tex[5 + cur], rtex[3]}
                : new int[]{tex[DAY], tex[LIGHTS], tex[WATER], tex[3 + cur], tex[3 + prev], tex[5 + cur], tex[5 + prev]};
        int[] units = java.util.Arrays.copyOf(units0, 12);
        units[7] = wxTex[0]; units[8] = wxTex[1]; units[9] = wxTex[2]; units[10] = auroraTex; units[11] = bordersTex;
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
        GLES20.glUniform1f(uCloudMix, f.replay ? f.replayMix : f.tail ? f.tailMix : f.cloudMix);
        GLES30.glUniform1i(uMode, f.mode);
        GLES20.glUniform1f(uWxMix, f.wxMix);
        GLES30.glUniform1i(uWxChan, f.wxChan);
        GLES30.glUniform1i(uWxPal, f.wxPal);
        GLES20.glUniform1f(uWxClouds, f.wxClouds);
        GLES20.glUniform1f(uWxAmt, f.wxAmt);
        GLES30.glUniform1i(uWxStyle, f.wxStyle);
        GLES20.glUniform1f(uAuroraAmt, f.auroraAmt);
        GLES20.glUniform1f(uBorderAmt, f.borderAmt);
        GLES20.glUniform1f(uFade, f.fade);
        GLES20.glUniform1f(uTime, f.time);
        GLES20.glUniform2f(uStarShift, f.starDx * s, f.starDy * s);
        GLES20.glUniform1f(uStars, f.stars ? 1f : 0f);
        if (f.replay) {          // morph A -> B: A drifts forward along the motion, B comes back from where it was
            GLES20.glUniform1f(uAdvect, -(1f - f.replayMix) * f.replayHours);
            GLES20.glUniform1f(uAdvectPrev, f.replayMix * f.replayHours);
        } else if (f.tail) {     // the replay's last frame keeps drifting forward while the live map fades in
            GLES20.glUniform1f(uAdvect, hoursSince(slotTime[cur], f.cloudNowMs));
            GLES20.glUniform1f(uAdvectPrev, f.tailHours);
        } else {
            GLES20.glUniform1f(uAdvect, hoursSince(slotTime[cur], f.cloudNowMs));
            GLES20.glUniform1f(uAdvectPrev, hoursSince(slotTime[prev], f.cloudNowMs));
        }
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
        drawStreaks(f);
        drawDots(f);
        drawTracks(f);
        drawStorms(f);
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
        GLES20.glDeleteTextures(4, rtex, 0);
        if (stormTexN > 0) { GLES20.glDeleteTextures(stormTexN, stormTex, 0); stormTexN = 0; }
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

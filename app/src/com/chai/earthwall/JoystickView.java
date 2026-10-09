package com.chai.earthwall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Thumbstick. Reports a deflection in [-1, 1] per axis (x right, y up) while held; springs back to
 * centre on release. A vertical-only stick (zoom) draws as a capsule.
 */
final class JoystickView extends View {
    interface Listener { void onStick(float x, float y); }

    private final boolean verticalOnly;
    private final Listener listener;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            knob = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float kx, ky;                 // knob offset, -1..1 (screen y down)
    private float returnAnim = 0f;        // eases the knob home after release
    private final String topMark, bottomMark;

    JoystickView(Context c, boolean verticalOnly, String topMark, String bottomMark, Listener l) {
        super(c);
        this.verticalOnly = verticalOnly;
        this.listener = l;
        this.topMark = topMark;
        this.bottomMark = bottomMark;
        base.setColor(0x1FFFFFFF);
        ring.setColor(0x40FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.2f));
        knob.setColor(0xD9FFFFFF);
        knob.setShadowLayer(dp(6), 0, 0, 0x66000000);
        mark.setColor(0x99FFFFFF);
        mark.setTextAlign(Paint.Align.CENTER);
        mark.setTextSize(dp(15));
        setLayerType(LAYER_TYPE_SOFTWARE, null);  // for the knob shadow
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float travel() {
        return (verticalOnly ? getHeight() : Math.min(getWidth(), getHeight())) * 0.5f - dp(26);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
        if (verticalOnly) {
            float w = dp(52);
            RectF r = new RectF(cx - w / 2, dp(4), cx + w / 2, getHeight() - dp(4));
            c.drawRoundRect(r, w / 2, w / 2, base);
            c.drawRoundRect(r, w / 2, w / 2, ring);
            if (topMark != null) c.drawText(topMark, cx, r.top + dp(22), mark);
            if (bottomMark != null) c.drawText(bottomMark, cx, r.bottom - dp(12), mark);
        } else {
            float rad = Math.min(getWidth(), getHeight()) * 0.5f - dp(4);
            c.drawCircle(cx, cy, rad, base);
            c.drawCircle(cx, cy, rad, ring);
        }
        float t = travel();
        c.drawCircle(cx + kx * t, cy + ky * t, dp(22), knob);
        if (returnAnim > 0f) {
            returnAnim = Math.max(0f, returnAnim - 0.18f);
            kx *= 0.6f; ky *= 0.6f;
            if (returnAnim > 0f) postInvalidateOnAnimation(); else { kx = 0; ky = 0; }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float t = Math.max(travel(), 1f);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                returnAnim = 0f;
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float dx = verticalOnly ? 0 : (e.getX() - getWidth() * 0.5f) / t;
                float dy = (e.getY() - getHeight() * 0.5f) / t;
                float len = (float) Math.hypot(dx, dy);
                if (len > 1f) { dx /= len; dy /= len; }
                kx = dx; ky = dy;
                listener.onStick(kx, -ky);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                listener.onStick(0f, 0f);
                returnAnim = 1f;
                invalidate();
                return true;
        }
        return false;
    }
}

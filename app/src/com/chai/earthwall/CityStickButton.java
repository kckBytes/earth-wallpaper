package com.chai.earthwall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * One small round button. Tap: next city. Press and hold (or start dragging): it becomes a joystick —
 * a ring appears, the knob follows your thumb within it, and the Earth flies in that direction.
 * Release: the knob springs back into a button.
 * The view is larger than the button so the ring has room; touches outside the button are ignored
 * (they fall through to the globe for double-tap zoom).
 */
final class CityStickButton extends View {
    interface Listener {
        void onTapNext();
        void onStick(float x, float y);       // -1..1, y up; (0,0) on release
    }

    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int slop;
    private boolean down, stick;
    private float downX, downY, kx, ky;      // knob offset in px
    private float ringAlpha;                  // 0..1, animates the ring in/out
    private final Runnable enterStick = this::enterStick;

    CityStickButton(Context c, Listener l) {
        super(c);
        listener = l;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        fill.setColor(0x33FFFFFF);
        stroke.setColor(0x66FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.2f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.2f));
        arrow.setColor(0xF0FFFFFF);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeWidth(dp(2.2f));
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float buttonR() { return dp(26); }
    private float ringR() { return Math.min(getWidth(), getHeight()) * 0.5f - dp(6); }

    private void enterStick() {
        if (!down || stick) return;
        stick = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.hypot(e.getX() - cx, e.getY() - cy) > buttonR() * 1.3f) return false;  // not on the button
                down = true; stick = false; downX = e.getX(); downY = e.getY(); kx = ky = 0;
                getParent().requestDisallowInterceptTouchEvent(true);
                ui.postDelayed(enterStick, 260);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down) return false;
                if (!stick && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) enterStick();
                if (stick) {
                    float dx = e.getX() - cx, dy = e.getY() - cy, R = ringR() - buttonR() * 0.6f;
                    float len = (float) Math.hypot(dx, dy);
                    if (len > R) { dx *= R / len; dy *= R / len; }
                    kx = dx; ky = dy;
                    listener.onStick(dx / R, -dy / R);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(enterStick);
                if (down && !stick && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.onTapNext();
                }
                if (stick) listener.onStick(0f, 0f);
                down = false; stick = false;
                invalidate();
                return true;
        }
        return false;
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
        // ring fades in while in joystick mode, knob eases home after release
        float targetRing = stick ? 1f : 0f;
        ringAlpha += (targetRing - ringAlpha) * 0.3f;
        if (!stick) { kx *= 0.55f; ky *= 0.55f; }
        boolean moving = Math.abs(targetRing - ringAlpha) > 0.01f || (!stick && (Math.abs(kx) > 0.5f || Math.abs(ky) > 0.5f));
        if (ringAlpha > 0.01f) {
            ring.setColor(((int) (0x55 * ringAlpha) << 24) | 0xFFFFFF);
            fill.setColor(((int) (0x14 * ringAlpha) << 24) | 0xFFFFFF);
            c.drawCircle(cx, cy, ringR(), fill);
            c.drawCircle(cx, cy, ringR(), ring);
        }
        float bx = cx + kx, by = cy + ky, r = buttonR() * (down ? 0.94f : 1f);
        fill.setColor(down ? 0x55FFFFFF : 0x33FFFFFF);
        c.drawCircle(bx, by, r, fill);
        c.drawCircle(bx, by, r, stroke);
        if (stick) {                                  // joystick: a small dot
            arrow.setStyle(Paint.Style.FILL);
            c.drawCircle(bx, by, dp(4), arrow);
            arrow.setStyle(Paint.Style.STROKE);
        } else {                                      // button: a chevron "next"
            Path p = new Path();
            float a = dp(6);
            p.moveTo(bx - a * 0.5f, by - a);
            p.lineTo(bx + a * 0.6f, by);
            p.lineTo(bx - a * 0.5f, by + a);
            c.drawPath(p, arrow);
        }
        if (moving) postInvalidateOnAnimation();
    }
}

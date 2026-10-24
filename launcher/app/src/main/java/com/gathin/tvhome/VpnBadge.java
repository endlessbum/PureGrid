package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * The "VPN" capsule drawn inside the network pill: a lock and the word VPN, dark on green while a VPN
 * is up, dark on amber while it is up but its traffic is blocked, and dark on white while it is down.
 * The lock is open when the VPN is up and closed when it is down, so the state reads without the
 * colour.
 */
final class VpnBadge extends ReplacementSpan {

    /** The VPN is up and traffic flows through it. */
    static final int UP = 0;
    /** The VPN is up but has no traffic: the capsule turns amber instead of green. */
    static final int BLOCKED = 1;
    /** No VPN is up (or no VPN app is installed). */
    static final int OFF = 2;

    private static final int GREEN = 0xFF30D158;
    private static final int AMBER = 0xFFFF9F0A;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF06290F;
    private static final int INK_OFF = 0xFF111111;

    private final Drawable lock;
    private final float density;
    private final int fill, ink;
    private final RectF rect = new RectF();

    /** @param lock the lock icon, open or closed; never null */
    VpnBadge(Drawable lock, int state, float density) {
        this.fill = state == UP ? GREEN : state == BLOCKED ? AMBER : WHITE;
        this.ink = state == OFF ? INK_OFF : INK;
        this.lock = lock != null ? lock.mutate() : null;
        if (this.lock != null) this.lock.setTint(ink);
        this.density = density;
    }

    private float textSize(Paint p) {
        return p.getTextSize() * 0.78f;
    }

    private float lockSize(Paint p) {
        return lock != null ? p.getTextSize() * 0.8f : 0;
    }

    private float lockGap() {
        return lock != null ? 4 * density : 2 * density;
    }

    /** The capsule itself, without the space around it. */
    private float capsuleWidth(Paint paint, CharSequence text, int start, int end) {
        float old = paint.getTextSize();
        paint.setTextSize(textSize(paint));
        float w = paint.measureText(text, start, end);
        paint.setTextSize(old);
        return 7 * density + lockSize(paint) + lockGap() + w + 9 * density;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        // margin + capsule + a little room after it, so the capsule's outline is never drawn right at
        // the edge of the text box, where the pill's own rounded border would shave it.
        return (int) (8 * density + capsuleWidth(paint, text, start, end) + 4 * density);
    }

    @Override
    public void draw(@NonNull Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     @NonNull Paint paint) {
        float base = paint.getTextSize();
        float lockPx = lockSize(paint);
        float h = base * 1.3f;
        float cy = (top + bottom) / 2f;
        float left = x + 8 * density;
        rect.set(left, cy - h / 2f, left + capsuleWidth(paint, text, start, end), cy + h / 2f);

        int oldColor = paint.getColor();
        paint.setColor(fill);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);

        int lx = (int) (left + 7 * density);
        if (lock != null) {
            lock.setBounds(lx, (int) (cy - lockPx / 2f), (int) (lx + lockPx), (int) (cy + lockPx / 2f));
            lock.draw(c);
        }

        paint.setColor(ink);
        paint.setTextSize(textSize(paint));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float ty = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, start, end, lx + lockPx + lockGap(), ty, paint);
        paint.setTextSize(base);
        paint.setColor(oldColor);
    }
}

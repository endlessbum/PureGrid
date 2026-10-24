package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * The top bar's Google account button. What it draws:
 *  - the active Google profile's avatar, when the contacts provider gives us one;
 *  - otherwise the account's first letter by itself (the avatar is not always reachable on a TV:
 *    contacts sync can be off);
 *  - a monochrome "G" when no account is connected at all.
 * Everything is local: no network, no dialogs, nothing the user has to confirm. The glyph follows
 * the same white/dark state list the other buttons tint their icons with.
 */
public class AccountAvatarView extends View {
    private TextPaint letterPaint;
    private ColorStateList tint;
    /** The avatar exactly as delivered; the circle-cut copy is (re)built on every size change. */
    private Bitmap avatar;
    private Bitmap cutout;
    private String letter = "G";

    public AccountAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letterPaint.setTextSize(20f * density);
        // The same white/dark state list the other top-bar buttons tint their icons with.
        tint = getContext().getColorStateList(R.color.pill_text);
    }

    /** Sets the content: the profile photo when available, otherwise the account's letter (or the bare mono "G"). */
    public void setAccount(Bitmap photo, String accountName) {
        avatar = photo;
        // The first code point, never half a surrogate pair; a fixed locale so the letter's shape
        // does not change with the TV's language.
        letter = accountName == null || accountName.isEmpty() ? "G"
                : new String(Character.toChars(accountName.codePointAt(0))).toUpperCase(Locale.ROOT);
        buildCutout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildCutout();
    }

    /** Centre-crops the photo to a square the size of the button's inner box; onDraw clips that round. */
    private void buildCutout() {
        if (avatar == null) {
            cutout = null;
            return;
        }
        int box = Math.max(1, Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom()));
        int side = Math.max(1, Math.min(avatar.getWidth(), avatar.getHeight()));
        Bitmap square = Bitmap.createBitmap(avatar, (avatar.getWidth() - side) / 2,
                (avatar.getHeight() - side) / 2, side, side);
        cutout = Bitmap.createScaledBitmap(square, box, box, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int color = tint != null ? tint.getColorForState(getDrawableState(), Color.WHITE) : Color.WHITE;
        letterPaint.setColor(color);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        Rect inner = new Rect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom());
        if (cutout != null) {
            // The avatar shows as a Google avatar does: clipped to a circle inside the button.
            Path circle = new Path();
            circle.addCircle(cx, cy, Math.min(inner.width(), inner.height()) / 2f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(circle);
            canvas.drawBitmap(cutout, cx - cutout.getWidth() / 2f, cy - cutout.getHeight() / 2f, null);
            canvas.restore();
            return;
        }
        // Text metrics centre the letter's ink, not its line box.
        canvas.drawText(letter, cx, cy - (letterPaint.ascent() + letterPaint.descent()) / 2f, letterPaint);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (tint != null && cutout == null) invalidate();   // focus can flip the glyph's colour
    }
}

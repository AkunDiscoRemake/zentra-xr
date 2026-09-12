package com.neonforge.render;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Neon / holographic text renderer. Uses the system sans-serif typeface (full
 * PT-BR accent support) with layered glow passes for the futuristic look.
 */
public final class NeonFont {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint barPaint = new Paint();

    public NeonFont() {
        Typeface tf = Typeface.create("sans-serif", Typeface.BOLD);
        core.setTypeface(tf);
        glow.setTypeface(tf);
        glow2.setTypeface(tf);
        glow.setMaskFilter(new BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL));
        glow2.setMaskFilter(new BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL));
    }

    public float measure(String s, float size) {
        core.setTextSize(size);
        return core.measureText(s);
    }

    public void draw(Canvas c, String s, float x, float y, float size, int color, int align) {
        draw(c, s, x, y, size, color, align, 1f);
    }

    public void draw(Canvas c, String s, float x, float y, float size, int color, int align, float glowStrength) {
        if (s == null || s.length() == 0) return;
        core.setTextSize(size);
        glow.setTextSize(size);
        glow2.setTextSize(size);

        float tx = x;
        if (align == ALIGN_CENTER) tx = x - core.measureText(s) / 2f;
        else if (align == ALIGN_RIGHT) tx = x - core.measureText(s);

        glow.setColor(color);
        glow.setAlpha((int) (120 * glowStrength));
        glow2.setColor(color);
        glow2.setAlpha((int) (70 * glowStrength));
        c.drawText(s, tx, y, glow2);
        c.drawText(s, tx, y, glow);

        core.setColor(Color.WHITE);
        core.setAlpha(235);
        c.drawText(s, tx, y, core);
        core.setColor(color);
        core.setAlpha(150);
        c.drawText(s, tx, y, core);
    }

    /** Simple glowing progress/bar drawing helper used across the HUD/menus. */
    public static void bar(Canvas c, float x, float y, float w, float h, float frac, int color, int bg) {
        Paint p = barPaint;
        p.setColor(bg);
        c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
        if (frac > 0f) {
            p.setColor(color);
            c.drawRoundRect(x, y, x + w * frac, y + h, h / 2, h / 2, p);
            p.setColor(Color.WHITE);
            p.setAlpha(120);
            c.drawRect(x + 2, y + h * 0.2f, x + w * frac - 2, y + h * 0.35f, p);
        }
    }
}

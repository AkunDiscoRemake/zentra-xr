package com.neonforge.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.neonforge.render.NeonFont;
import com.neonforge.render.Renderer;
import com.neonforge.render.Sprites;

/** Shared drawing helpers: holographic panels, neon buttons, sliders and
 *  procedurally animated hand glyphs used by the tutorial. */
public final class UiKit {

    public static final int G_POINT = 0;
    public static final int G_PINCH = 1;
    public static final int G_GRAB = 2;
    public static final int G_SHIELD = 3;
    public static final int G_ENERGY = 4;
    public static final int G_FIRE = 5;
    public static final int G_TWO = 6;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public static class Btn {
        public float x, y, w, h;
        public String label;
        public int id;
    }

    public boolean hit(Btn b, float x, float y) {
        return x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h;
    }

    public boolean hit(float rx, float ry, float rw, float rh, float x, float y) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    public void panel(Canvas c, float x, float y, float w, float h, int color, float alpha) {
        fill.setColor(Color.argb((int) (alpha * 46), 10, 14, 30));
        c.drawRoundRect(x, y, x + w, y + h, 12, 12, fill);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(color);
        p.setAlpha((int) (alpha * 200));
        c.drawRoundRect(x, y, x + w, y + h, 12, 12, p);
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
    }

    public void button(Canvas c, Renderer r, Btn b, boolean selected, int color) {
        panel(c, b.x, b.y, b.w, b.h, color, selected ? 1f : 0.75f);
        if (selected) {
            p.setColor(color);
            p.setAlpha(40);
            c.drawRoundRect(b.x, b.y, b.x + b.w, b.y + b.h, 12, 12, p);
            p.setAlpha(255);
        }
        float size = Math.min(b.h * 0.4f, 30f);
        r.font().draw(c, b.label, b.x + b.w / 2f, b.y + b.h / 2f + size * 0.35f,
                size, selected ? Color.WHITE : color, NeonFont.ALIGN_CENTER);
    }

    public void slider(Canvas c, Renderer r, float x, float y, float w, String label,
                       float value, float min, float max, int color) {
        float size = Math.min(20f, w * 0.045f);
        r.font().draw(c, label, x, y + size * 0.4f, size, Color.WHITE, NeonFont.ALIGN_LEFT);
        r.font().draw(c, fmt(value), x + w, y + size * 0.4f, size, color, NeonFont.ALIGN_RIGHT);
        float sy = y + size + 8;
        p.setColor(Color.argb(90, color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF));
        c.drawRoundRect(x, sy, x + w, sy + 6, 3, 3, p);
        float fx = x + (value - min) / (max - min) * w;
        p.setColor(color);
        c.drawCircle(fx, sy + 3, 9, p);
    }

    /** Hand glyph animation for tutorial cards (kind in G_*). */
    public void handGlyph(Canvas c, float cx, float cy, float s, int kind, float t, int color) {
        p.setColor(color);
        p.setAlpha(220);
        p.setStrokeWidth(Math.max(2f, s * 0.05f));

        switch (kind) {
            case G_POINT:
                drawFingers(c, cx, cy, s, new float[]{1f, 0.2f, 0.2f, 0.2f, 0.2f}, color, p);
                break;
            case G_PINCH: {
                float d = 0.75f - 0.55f * (0.5f + 0.5f * (float) Math.sin(t * 3.5f));
                drawFingers(c, cx, cy, s, new float[]{d, d, 0.15f, 0.15f, 0.15f}, color, p);
                break;
            }
            case G_GRAB:
                drawFingers(c, cx, cy, s, new float[]{0.15f, 0.15f, 0.15f, 0.15f, 0.15f}, color, p);
                break;
            case G_SHIELD:
                drawFingers(c, cx, cy, s, new float[]{1f, 1f, 1f, 1f, 1f}, color, p);
                p.setStyle(Paint.Style.STROKE);
                p.setAlpha(120);
                c.drawCircle(cx, cy - s * 0.1f, s * 0.95f, p);
                p.setStyle(Paint.Style.FILL);
                break;
            case G_ENERGY: {
                drawFingers(c, cx, cy, s, new float[]{0.15f, 0.15f, 0.15f, 0.15f, 0.15f}, color, p);
                float gr = s * (0.3f + 0.35f * (0.5f + 0.5f * (float) Math.sin(t * 2.2f)));
                p.setAlpha(140);
                c.drawCircle(cx, cy - s * 0.4f, gr, p);
                p.setAlpha(255);
                break;
            }
            case G_FIRE:
                drawFingers(c, cx, cy, s, new float[]{1f, 0.2f, 0.2f, 0.2f, 0.2f}, color, p);
                p.setAlpha(180);
                c.drawLine(cx, cy - s * 0.7f, cx + s * 0.9f, cy - s * 1.2f, p);
                p.setAlpha(255);
                break;
            case G_TWO: {
                drawFingers(c, cx - s * 0.55f, cy, s * 0.7f, new float[]{1f, 1f, 0.2f, 0.2f, 0.2f}, Sprites.C_CYAN, p);
                drawFingers(c, cx + s * 0.55f, cy, s * 0.7f, new float[]{1f, 1f, 0.2f, 0.2f, 0.2f}, Sprites.C_MAGENTA, p);
                float gr = s * (0.24f + 0.1f * (float) Math.sin(t * 3f));
                p.setColor(Color.WHITE);
                p.setAlpha(220);
                c.drawCircle(cx, cy, gr, p);
                p.setAlpha(255);
                break;
            }
        }
        p.setAlpha(255);
    }

    private void drawFingers(Canvas c, float cx, float cy, float s, float[] lens, int color, Paint paint) {
        float[] angles = {-0.85f, -0.42f, 0f, 0.42f, 0.85f};
        float palmR = s * 0.34f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.PI + angles[i];
            float len = s * (0.55f * lens[i] + 0.2f);
            float x0 = cx + (float) Math.cos(a) * palmR * 0.7f;
            float y0 = cy - (float) Math.sin(a) * palmR * 0.7f;
            float x1 = cx + (float) Math.cos(a) * (palmR + len);
            float y1 = cy - (float) Math.sin(a) * (palmR + len);
            c.drawLine(x0, y0, x1, y1, paint);
            c.drawCircle(x1, y1, Math.max(3f, s * 0.09f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(cx, cy, palmR, paint);
    }

    private static String fmt(float v) {
        int pct = Math.round(v * 100);
        return pct + "%";
    }
}

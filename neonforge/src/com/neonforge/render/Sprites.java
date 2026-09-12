package com.neonforge.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.neonforge.world.Enemy;

/**
 * Procedurally generated sprites: neon orbs, glows, energy bolts and stylized
 * robot enemies. Everything is drawn at runtime — no texture assets.
 */
public final class Sprites {

    public static final int C_CYAN = 0xFF19E6FF;
    public static final int C_MAGENTA = 0xFFFF3D8F;
    public static final int C_AMBER = 0xFFFFB347;
    public static final int C_GREEN = 0xFF39FF88;
    public static final int C_RED = 0xFFFF4055;
    public static final int C_VIOLET = 0xFF9B5CFF;

    public Bitmap glowCyan, glowMagenta, glowAmber, glowGreen, glowRed, glowViolet, glowWhite;
    public Bitmap orbCore, orbBattery, boltCyan, boltRed;
    public Bitmap robotDrone, robotGuard;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Sprites() {
        glowCyan = glow(C_CYAN);
        glowMagenta = glow(C_MAGENTA);
        glowAmber = glow(C_AMBER);
        glowGreen = glow(C_GREEN);
        glowRed = glow(C_RED);
        glowViolet = glow(C_VIOLET);
        glowWhite = glow(Color.WHITE);
        orbCore = orb(C_CYAN, C_VIOLET);
        orbBattery = orb(C_GREEN, C_AMBER);
        boltCyan = bolt(C_CYAN);
        boltRed = bolt(C_RED);
        robotDrone = robot(false);
        robotGuard = robot(true);
    }

    private Bitmap glow(int color) {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{color, color & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);
        return b;
    }

    private Bitmap orb(int inner, int outer) {
        int s = 96;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawBitmap(glowWhite, s / 2f - 32, s / 2f - 32, p);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{Color.WHITE, inner, outer & 0x00FFFFFF}, new float[]{0f, 0.35f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setShader(null);
        return b;
    }

    private Bitmap bolt(int color) {
        int s = 48;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawBitmap(glow(color), 0, 0, p);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, s * 0.16f, p);
        return b;
    }

    private Bitmap robot(boolean guard) {
        int s = 128;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int accent = guard ? C_AMBER : C_RED;
        int body = guard ? 0xFF3A3A46 : 0xFF30303C;

        // hover glow
        c.drawBitmap(glow(guard ? C_AMBER : C_MAGENTA), s / 2f - 32, s - 64, p);

        // body
        p.setColor(body);
        c.drawRoundRect(s * 0.28f, s * 0.36f, s * 0.72f, s * 0.86f, 18, 18, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(accent);
        c.drawRoundRect(s * 0.28f, s * 0.36f, s * 0.72f, s * 0.86f, 18, 18, p);
        p.setStyle(Paint.Style.FILL);

        // head / visor
        p.setColor(0xFF22222C);
        c.drawRoundRect(s * 0.34f, s * 0.20f, s * 0.66f, s * 0.40f, 10, 10, p);
        p.setColor(accent);
        c.drawRoundRect(s * 0.38f, s * 0.25f, s * 0.62f, s * 0.34f, 6, 6, p);

        // eye
        p.setColor(Color.WHITE);
        c.drawCircle(s * 0.5f, s * 0.295f, s * 0.035f, p);

        // side fins / arms
        p.setColor(body);
        c.drawRoundRect(s * 0.14f, s * 0.44f, s * 0.26f, s * 0.70f, 10, 10, p);
        c.drawRoundRect(s * 0.74f, s * 0.44f, s * 0.86f, s * 0.70f, 10, 10, p);

        // chest core
        p.setColor(accent);
        c.drawCircle(s * 0.5f, s * 0.56f, s * 0.07f, p);
        p.setColor(Color.WHITE);
        c.drawCircle(s * 0.5f, s * 0.56f, s * 0.03f, p);
        return b;
    }

    public Bitmap glowFor(int color) {
        if (color == C_CYAN) return glowCyan;
        if (color == C_MAGENTA) return glowMagenta;
        if (color == C_AMBER) return glowAmber;
        if (color == C_GREEN) return glowGreen;
        if (color == C_RED) return glowRed;
        if (color == C_VIOLET) return glowViolet;
        return glowWhite;
    }

    public Bitmap enemy(Enemy e) {
        return e.type == Enemy.TYPE_GUARD ? robotGuard : robotDrone;
    }

    public Bitmap pickup(int type) {
        return type == 0 ? orbCore : orbBattery;
    }

    public Bitmap bolt(boolean player) {
        return player ? boltCyan : boltRed;
    }
}

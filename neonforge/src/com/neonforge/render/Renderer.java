package com.neonforge.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.neonforge.core.Settings;
import com.neonforge.hand.Gesture;
import com.neonforge.hand.GestureSystem;
import com.neonforge.hand.HandState;
import com.neonforge.hand.HandTracker;
import com.neonforge.world.Enemy;
import com.neonforge.world.Map;
import com.neonforge.world.Pickup;
import com.neonforge.world.Player;
import com.neonforge.world.Projectile;

/**
 * RenderingSystem: owns the offscreen render target, projection math, the
 * ray-caster pass, billboard sprites, particles, the hand overlay and the HUD.
 */
public final class Renderer {

    private int bw, bh;          // buffer size
    private int sw, sh;          // screen size
    private Bitmap buffer;
    private Canvas canvas;
    private float scale;

    public final Sprites sprites = new Sprites();
    public final NeonFont font = new NeonFont();
    public ParticleSystem particles;

    // camera
    private float px, py, dirX, dirY, planeX, planeY, pitch, fov;
    private float horizonY;
    private float[] zbuf = new float[1024];

    private final Paint blitPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ceilPaint = new Paint();
    private final Paint floorPaint = new Paint();
    private final Paint vignettePaint = new Paint();
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint();

    private final float[] projOut = new float[2];
    private final float[] spriteDepth = new float[64];
    private final int[] spriteOrder = new int[64];

    public Renderer() {
        particlePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public int bufferWidth() { return bw; }
    public int bufferHeight() { return bh; }
    public int screenWidth() { return sw; }
    public int screenHeight() { return sh; }
    public Sprites sprites() { return sprites; }
    public NeonFont font() { return font; }
    public Paint particlePaint() { return particlePaint; }
    public Canvas canvas() { return canvas; }

    public void setSize(int w, int h, float renderScale) {
        sw = w; sh = h;
        int nbw = Math.max(160, (int) (w * renderScale));
        int nbh = Math.max(90, (int) (nbw * (float) h / Math.max(1, w)));
        if (nbw != bw || nbh != bh || buffer == null) {
            bw = nbw; bh = nbh;
            if (buffer != null) buffer.recycle();
            buffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(buffer);
            scale = (float) bw / Math.max(1, sw);
            if (zbuf.length < bw) {
                zbuf = new float[Math.max(1024, bw)];
            }
            rebuildGradients();
        }
    }

    private void rebuildGradients() {
        ceilPaint.setShader(new LinearGradient(0, 0, 0, bh * 0.5f,
                new int[]{Color.rgb(12, 14, 30), Color.rgb(4, 6, 14)},
                null, Shader.TileMode.CLAMP));
        floorPaint.setShader(new LinearGradient(0, bh * 0.5f, 0, bh,
                new int[]{Color.rgb(6, 8, 18), Color.rgb(3, 4, 10)},
                null, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(bw / 2f, bh / 2f, Math.max(bw, bh) * 0.7f,
                new int[]{0x00000000, 0x88000000}, new float[]{0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    public void setCamera(float px, float py, float angle, float fovDeg, float pitch) {
        this.px = px; this.py = py;
        this.pitch = pitch;
        fov = fovDeg * 3.14159265f / 180f;
        dirX = (float) Math.cos(angle);
        dirY = (float) Math.sin(angle);
        float pl = (float) Math.tan(fov / 2f);
        planeX = -dirY * pl;
        planeY = dirX * pl;
        horizonY = bh * 0.5f + pitch * bh * 0.22f;
    }

    /** Project a world (floor) point; out[0]=screen x, out[1]=depth. */
    public boolean project(float wx, float wy, float[] out) {
        float relX = wx - px, relY = wy - py;
        float invDet = 1f / (planeX * dirY - dirX * planeY);
        float tx = invDet * (dirY * relX - dirX * relY);
        float ty = invDet * (-planeY * relX + planeX * relY);
        if (ty <= 0.05f) return false;
        out[0] = (bw / 2f) * (1f + tx / ty);
        out[1] = ty;
        return true;
    }

    public void beginFrame() {
        canvas.drawColor(Color.rgb(4, 6, 14));
    }

    public void endFrame(Canvas screen) {
        canvas.drawRect(0, 0, bw, bh, vignettePaint);
        screen.drawBitmap(buffer, null,
                new android.graphics.Rect(0, 0, sw, sh), blitPaint);
    }

    // ------------------------------------------------------------------
    // world pass
    // ------------------------------------------------------------------
    public void drawWorld(GameCtx g) {
        Map map = g.map();
        Player p = g.player();
        Raycaster rc = g.raycaster();
        Settings s = g.settings();

        setCamera(p.x, p.y, p.angle, s.fov, p.pitch);
        if (zbuf.length < bw) return;

        rc.render(canvas, map, p.x, p.y, dirX, dirY, planeX, planeY, bw, bh, p.pitch,
                zbuf, s.drawDistance, s.wallShadows);

        drawSprites(g);
        particles.draw(canvas, this);
    }

    private void drawSprites(GameCtx g) {
        int n = 0;
        int en = g.enemyCount();
        int pn = g.pickupCount();
        int jn = g.projectileCount();

        // collect + sort by depth (far -> near)
        for (int i = 0; i < en && n < 64; i++) {
            Enemy e = g.enemies()[i];
            if (!e.alive) continue;
            if (project(e.x, e.y, projOut)) { spriteDepth[n] = projOut[1]; spriteOrder[n] = 0x100000 | i; n++; }
        }
        for (int i = 0; i < pn && n < 64; i++) {
            Pickup k = g.pickups()[i];
            if (!k.active) continue;
            if (project(k.x, k.y, projOut)) { spriteDepth[n] = projOut[1]; spriteOrder[n] = 0x200000 | i; n++; }
        }
        for (int i = 0; i < jn && n < 64; i++) {
            Projectile j = g.projectiles()[i];
            if (!j.active) continue;
            if (project(j.x, j.y, projOut)) { spriteDepth[n] = projOut[1]; spriteOrder[n] = 0x300000 | i; n++; }
        }

        // selection sort descending depth (far first)
        for (int i = 0; i < n - 1; i++) {
            int m = i;
            for (int k = i + 1; k < n; k++) if (spriteDepth[k] > spriteDepth[m]) m = k;
            float td = spriteDepth[i]; spriteDepth[i] = spriteDepth[m]; spriteDepth[m] = td;
            int to = spriteOrder[i]; spriteOrder[i] = spriteOrder[m]; spriteOrder[m] = to;
        }

        for (int sIdx = 0; sIdx < n; sIdx++) {
            int code = spriteOrder[sIdx];
            int kind = code >>> 20;
            int idx = code & 0xFFFFF;
            float depth = spriteDepth[sIdx];
            if (kind == 1) {
                Enemy e = g.enemies()[idx];
                float flash = e.hitFlash > 0f ? 0.6f : 0f;
                drawBillboard(sprites.enemy(e), e.x, e.y, 0.55f + (float) Math.sin(e.bob) * 0.05f,
                        0.62f, 255, flash, depth);
            } else if (kind == 2) {
                Pickup k = g.pickups()[idx];
                float h = k.height + 0.12f + (float) Math.sin(k.bob) * 0.06f;
                int alpha = (k.state == Pickup.S_SELECTED || k.state == Pickup.S_PULLED) ? 255 : 220;
                drawBillboard(sprites.pickup(k.type), k.x, k.y, h, 0.34f, alpha, 0f, depth);
                if (k.state == Pickup.S_SELECTED || k.state == Pickup.S_PULLED) {
                    drawRing(k.x, k.y, h, Sprites.C_CYAN, depth);
                }
            } else {
                Projectile j = g.projectiles()[idx];
                drawBillboard(sprites.bolt(j.ownerIsPlayer), j.x, j.y, 0.5f, 0.28f, 255, 0f, depth);
            }
        }

        drawReactor(g);
    }

    private void drawReactor(GameCtx g) {
        float[] o = new float[2];
        if (!project(g.reactorX(), g.reactorY(), o)) return;
        float d = o[1];
        if (d < 0.05f) return;
        float s = (1.9f * bh) / d;
        float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(3f, s * 0.05f));
        linePaint.setColor(Sprites.C_GREEN);
        linePaint.setAlpha((int) (200 * pulse));
        canvas.drawCircle(o[0], horizonY, s / 2f, linePaint);
        linePaint.setStyle(Paint.Style.FILL);
        linePaint.setAlpha(255);
        hudPaint.setAlpha((int) (60 * pulse));
        canvas.drawBitmap(sprites.glowGreen, o[0] - s / 2f, horizonY - s / 2f, hudPaint);
        hudPaint.setAlpha(255);
    }

    private void drawRing(float wx, float wy, float h, int color, float depth) {
        float[] o = new float[2];
        if (!project(wx, wy, o)) return;
        float s = (0.9f * bh) / o[1];
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(2f, s * 0.06f));
        linePaint.setColor(color);
        linePaint.setAlpha(200);
        canvas.drawCircle(o[0], horizonY - h * bh / o[1], s / 2f, linePaint);
        linePaint.setStyle(Paint.Style.FILL);
    }

    /** Billboard sprite with z-clip and vertical placement. */
    private void drawBillboard(Bitmap bmp, float wx, float wy, float worldH, float scaleF,
                               int alpha, float whiteFlash, float depth) {
        float[] o = new float[2];
        if (!project(wx, wy, o)) return;
        float sx = o[0];
        // z-clip against wall buffer at a few columns
        int cx = (int) sx;
        boolean occluded = false;
        for (int c = Math.max(0, cx - 6); c <= Math.min(bw - 1, cx + 6); c += 6) {
            if (zbuf[c] < depth - 0.12f) { occluded = true; break; }
        }
        if (occluded) return;

        float size = scaleF * bh / depth;
        if (size < 2f) return;
        float z = worldH - 0.5f;
        float screenY = horizonY - z * bh / depth;

        hudPaint.setAlpha(alpha);
        canvas.save();
        canvas.translate(sx, screenY);
        if (whiteFlash > 0f) {
            hudPaint.setColorFilter(null);
        }
        canvas.drawBitmap(bmp, -size / 2f, -size / 2f, hudPaint);
        if (whiteFlash > 0f) {
            flashPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Color.argb((int) (255 * whiteFlash), 255, 255, 255),
                    PorterDuff.Mode.SRC_ATOP));
            canvas.drawBitmap(bmp, -size / 2f, -size / 2f, flashPaint);
            flashPaint.setColorFilter(null);
        }
        canvas.restore();
        hudPaint.setAlpha(255);
    }

    // ------------------------------------------------------------------
    // hand overlay
    // ------------------------------------------------------------------
    public void drawHands(GameCtx g) {
        GestureSystem.Result r = g.gesture();
        HandTracker tracker = g.tracker();
        HandState dom = tracker.dominantHand();
        HandState sec = tracker.secondHand();
        float hs = g.settings().handScale;

        if (g.cameraActive()) {
            if (dom != null && dom.tracked) {
                drawHand(dom, Sprites.C_CYAN, hs, g);
                if (r.twoHands && sec != null && sec.tracked) {
                    drawHand(sec, Sprites.C_MAGENTA, hs, g);
                }
            }
        } else if (g.showSyntheticHand()) {
            // touch fallback: stylized hand at the aim position
            drawSyntheticHand(g.aimScreenX(), g.aimScreenY(), Sprites.C_CYAN, hs, r, g);
        }
    }

    private void drawSyntheticHand(float nx, float ny, int color, float hs, GestureSystem.Result r, GameCtx g) {
        float cx = nx * bw, cy = ny * bh;
        float rad = 40 * hs;
        handGlow.setColor(color);
        handGlow.setAlpha(70);
        canvas.drawCircle(cx, cy, rad * 1.4f, handGlow);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(4f);
        canvas.drawCircle(cx, cy, rad, linePaint);
        // index ray toward the aim crosshair
        float ax = (0.5f + r.aimX * 0.5f) * bw;
        float ay = (0.5f + r.aimY * 0.5f) * bh;
        linePaint.setAlpha(160);
        canvas.drawLine(cx, cy, ax, ay, linePaint);
        linePaint.setAlpha(255);
        drawCrosshair(ax, ay, color);
    }

    private void drawHand(HandState h, int color, float hs, GameCtx g) {
        float px = h.palmX * bw, py = h.palmY * bh;
        float ix = h.indexX * bw, iy = h.indexY * bh;
        float tx = h.thumbX * bw, ty = h.thumbY * bh;
        float wx = h.wristX * bw, wy = h.wristY * bh;
        float rad = h.radius * bw * 0.9f * hs;
        if (rad < 8f) rad = 8f;
        if (rad > 90f) rad = 90f;

        handGlow.setColor(color);
        handGlow.setAlpha(46);
        canvas.drawCircle(px, py, rad * 1.6f, handGlow);

        linePaint.setColor(color);
        linePaint.setStrokeWidth(Math.max(3f, rad * 0.12f));
        linePaint.setAlpha(200);
        canvas.drawLine(wx, wy, px, py, linePaint);
        canvas.drawLine(px, py, ix, iy, linePaint);
        canvas.drawLine(px, py, tx, ty, linePaint);

        canvas.drawCircle(px, py, rad, linePaint);
        linePaint.setColor(Color.WHITE);
        linePaint.setAlpha(220);
        canvas.drawCircle(ix, iy, Math.max(5f, rad * 0.28f), linePaint);
        linePaint.setAlpha(170);
        canvas.drawCircle(tx, ty, Math.max(4f, rad * 0.24f), linePaint);
        linePaint.setAlpha(140);
        canvas.drawCircle(wx, wy, Math.max(4f, rad * 0.2f), linePaint);
        linePaint.setAlpha(255);

        // aim ray + crosshair
        float ax = (0.5f + g.gesture().aimX * 0.5f) * bw;
        float ay = (0.5f + g.gesture().aimY * 0.5f) * bh;
        linePaint.setColor(color);
        linePaint.setAlpha(110);
        canvas.drawLine(ix, iy, ax, ay, linePaint);
        linePaint.setAlpha(255);
        drawCrosshair(ax, ay, color);

        drawGestureLabel(g, px, py, color);
    }

    private void drawGestureLabel(GameCtx g, float x, float y, int color) {
        String name = gestureName(g.gesture().gesture);
        if (name == null) return;
        float size = Math.max(14f, bh * 0.028f);
        font.draw(canvas, name, x, y - bh * 0.12f, size, color, NeonFont.ALIGN_CENTER);
    }

    private String gestureName(com.neonforge.hand.Gesture ge) {
        switch (ge) {
            case POINT: return "APONTAR";
            case PINCH: return "PINCH";
            case OPEN_PALM: return "ESCUDO";
            case FIST: return "ENERGIA";
            case TWO_HANDS: return "DUAS MÃOS";
            default: return null;
        }
    }

    private void drawCrosshair(float x, float y, int color) {
        float s = Math.max(8f, bh * 0.022f);
        linePaint.setColor(color);
        linePaint.setAlpha(200);
        linePaint.setStrokeWidth(2f);
        canvas.drawCircle(x, y, s, linePaint);
        canvas.drawLine(x - s * 1.5f, y, x - s * 0.5f, y, linePaint);
        canvas.drawLine(x + s * 0.5f, y, x + s * 1.5f, y, linePaint);
        canvas.drawLine(x, y - s * 1.5f, x, y - s * 0.5f, linePaint);
        canvas.drawLine(x, y + s * 0.5f, x, y + s * 1.5f, linePaint);
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------
    public void drawHud(GameCtx g) {
        Player p = g.player();
        float m = Math.max(14f, bh * 0.024f);

        // energy / shield / health bars
        float bx = bw * 0.02f, by = bh * 0.04f, barW = bw * 0.22f, barH = Math.max(6f, bh * 0.016f);
        NeonFont.bar(canvas, bx, by, barW, barH, p.energy / 100f, Sprites.C_CYAN, 0x3319E6FF);
        NeonFont.bar(canvas, bx, by + barH + 6, barW, barH, p.shield / 100f, Sprites.C_VIOLET, 0x339B5CFF);
        NeonFont.bar(canvas, bx, by + 2 * (barH + 6), barW, barH, p.health / 100f, Sprites.C_GREEN, 0x3339FF88);
        font.draw(canvas, "ENERGIA", bx, by + 3 * (barH + 6) + m * 0.9f, m * 0.8f, Sprites.C_CYAN, NeonFont.ALIGN_LEFT);

        // objective + score (top right)
        font.draw(canvas, g.objectiveText(), bw * 0.98f, by + m, m, Sprites.C_AMBER, NeonFont.ALIGN_RIGHT);
        font.draw(canvas, "PONTOS " + p.score, bw * 0.98f, by + m * 2.4f, m * 0.85f, Sprites.C_MAGENTA, NeonFont.ALIGN_RIGHT);

        // tracking indicator
        if (g.cameraActive()) {
            float q = g.gesture().trackingQuality;
            int col = q > 0.4f ? Sprites.C_GREEN : (q > 0.15f ? Sprites.C_AMBER : Sprites.C_RED);
            hudPaint.setColor(col);
            hudPaint.setAlpha(200);
            canvas.drawCircle(bw * 0.985f, bh * 0.9f, Math.max(5f, bh * 0.012f), hudPaint);
            font.draw(canvas, "RASTREAMENTO", bw * 0.98f, bh * 0.955f, m * 0.7f, col, NeonFont.ALIGN_RIGHT);
            if (g.gesture().lowLight) {
                font.draw(canvas, "MELHORE A ILUMINAÇÃO OU POSICIONE AS MÃOS DENTRO DA CÂMERA",
                        bw * 0.5f, bh * 0.955f, m * 0.75f, Sprites.C_AMBER, NeonFont.ALIGN_CENTER);
            }
        } else if (!g.cameraActive() && g.showTouchHint()) {
            font.draw(canvas, "MODO TOQUE: ARRASTE PARA MIRAR • TOQUE PARA INTERAGIR",
                    bw * 0.5f, bh * 0.955f, m * 0.75f, Sprites.C_CYAN, NeonFont.ALIGN_CENTER);
            // virtual gesture buttons (fallback mode only)
            float bw2 = bw * 0.2f, bh2 = bh * 0.14f;
            NeonFont.bar(canvas, bw * 0.02f, bh * 0.80f, bw2, bh2, g.gesture().gesture == Gesture.OPEN_PALM ? 1f : 0f,
                    Sprites.C_VIOLET, 0x339B5CFF);
            font.draw(canvas, "ESCUDO", bw * 0.02f + bw2 / 2f, bh * 0.80f + bh2 / 2f + m * 0.35f,
                    m * 0.8f, Color.WHITE, NeonFont.ALIGN_CENTER);
            NeonFont.bar(canvas, bw * 0.78f, bh * 0.80f, bw2, bh2, g.gesture().charge,
                    Sprites.C_CYAN, 0x3319E6FF);
            font.draw(canvas, "ENERGIA", bw * 0.78f + bw2 / 2f, bh * 0.80f + bh2 / 2f + m * 0.35f,
                    m * 0.8f, Color.WHITE, NeonFont.ALIGN_CENTER);
        }

        // charge meter
        if (g.gesture().charge > 0.01f) {
            float cw = bw * 0.3f, ch = Math.max(8f, bh * 0.02f);
            float cx = bw * 0.5f - cw / 2f;
            NeonFont.bar(canvas, cx, bh * 0.84f, cw, ch, g.gesture().charge, Sprites.C_CYAN, 0x3319E6FF);
            font.draw(canvas, "CARREGANDO ENERGIA", bw * 0.5f, bh * 0.83f - m * 0.4f, m * 0.8f,
                    Sprites.C_CYAN, NeonFont.ALIGN_CENTER);
        }

        // interaction hint (contextual)
        String hint = g.interactionHint();
        if (hint != null) {
            font.draw(canvas, hint, bw * 0.5f, bh * 0.62f, m * 0.85f, Color.WHITE, NeonFont.ALIGN_CENTER);
        }

        // toast (GESTO DETECTADO etc.)
        String toast = g.toast();
        if (toast != null) {
            font.draw(canvas, toast, bw * 0.5f, bh * 0.3f, m * 1.3f, Sprites.C_GREEN, NeonFont.ALIGN_CENTER, 1f);
        }
    }

    /** Minimal interface decoupling the renderer from the Game class. */
    public interface GameCtx {
        Player player();
        Map map();
        Settings settings();
        Raycaster raycaster();
        Enemy[] enemies();
        int enemyCount();
        Pickup[] pickups();
        int pickupCount();
        Projectile[] projectiles();
        int projectileCount();
        GestureSystem.Result gesture();
        HandTracker tracker();
        boolean cameraActive();
        boolean showSyntheticHand();
        boolean showTouchHint();
        float aimScreenX();
        float aimScreenY();
        float reactorX();
        float reactorY();
        String objectiveText();
        String interactionHint();
        String toast();
    }
}

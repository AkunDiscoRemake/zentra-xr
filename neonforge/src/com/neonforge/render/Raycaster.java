package com.neonforge.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.neonforge.world.Map;

/**
 * First-person neon ray-caster: grid-based DDA wall casting with distance fog,
 * side lighting, emissive trims, energy stripes and sliding doors. Writes a
 * per-column depth buffer used to z-clip billboard sprites.
 */
public final class Raycaster {

    private static final int BG_R = 4, BG_G = 6, BG_B = 14;

    private final Paint wallPaint = new Paint();
    private final Paint trimPaint = new Paint();

    public void render(Canvas canvas, Map map, float px, float py,
                       float dirX, float dirY, float planeX, float planeY,
                       int W, int H, float pitch, float[] zbuf, float drawDistance,
                       boolean wallShadows) {

        int horizon = (int) (H * 0.5f + pitch * H * 0.22f);
        if (horizon < 0) horizon = 0;
        if (horizon > H) horizon = H;

        float dist2 = drawDistance * drawDistance;

        for (int x = 0; x < W; x++) {
            float cameraX = 2f * x / W - 1f;
            float rdx = dirX + planeX * cameraX;
            float rdy = dirY + planeY * cameraX;

            int mapX = (int) Math.floor(px);
            int mapY = (int) Math.floor(py);

            float ddx = Math.abs(1f / (rdx == 0f ? 1e-9f : rdx));
            float ddy = Math.abs(1f / (rdy == 0f ? 1e-9f : rdy));
            int stepX, stepY;
            float sideX, sideY;
            if (rdx < 0) { stepX = -1; sideX = (px - mapX) * ddx; }
            else { stepX = 1; sideX = (mapX + 1f - px) * ddx; }
            if (rdy < 0) { stepY = -1; sideY = (py - mapY) * ddy; }
            else { stepY = 1; sideY = (mapY + 1f - py) * ddy; }

            int side = 0;
            int hitType = Map.T_WALL;
            boolean hit = false;
            int guard = 0;
            while (!hit && guard++ < 64) {
                if (sideX < sideY) {
                    sideX += ddx; mapX += stepX; side = 0;
                } else {
                    sideY += ddy; mapY += stepY; side = 1;
                }
                int t = map.typeAt(mapX, mapY);
                if (t == Map.T_WALL || t == Map.T_ENERGY) {
                    hit = true; hitType = t;
                } else if (t == Map.T_DOOR && map.doorOpen[mapY * Map.W + mapX] < 0.55f) {
                    hit = true; hitType = t;
                }
            }

            float perp = (side == 0) ? (sideX - ddx) : (sideY - ddy);
            if (perp < 0.02f) perp = 0.02f;
            zbuf[x] = perp;
            if (perp * perp > dist2) {
                // beyond draw distance: fog wall to background (skip drawing)
                continue;
            }

            float wallX = (side == 0) ? (py + perp * rdy) : (px + perp * rdx);
            wallX -= (float) Math.floor(wallX);

            int lineH = (int) (H / perp);
            int drawStart = H / 2 - lineH / 2 + (int) (pitch * H * 0.22f);
            int drawEnd = H / 2 + lineH / 2 + (int) (pitch * H * 0.22f);
            if (drawStart < 0) drawStart = 0;
            if (drawEnd > H) drawEnd = H;

            float doorSlide = 0f;
            if (hitType == Map.T_DOOR) {
                doorSlide = map.doorOpen[mapY * Map.W + mapX];
                int slide = (int) (doorSlide * lineH);
                drawStart -= slide;
                drawEnd -= slide;
            }

            int shIdx = mapY * Map.W + mapX;
            if (shIdx < 0) shIdx = 0;
            if (shIdx >= Map.W * Map.H) shIdx = Map.W * Map.H - 1;
            float shade = map.shade[shIdx];

            // base color
            int r, g, b;
            int trim;
            switch (hitType) {
                case Map.T_ENERGY:
                    r = (int) (30 + shade * 20);
                    g = (int) (24 + shade * 14);
                    b = (int) (70 + shade * 40);
                    trim = Sprites.C_VIOLET;
                    break;
                case Map.T_DOOR:
                    r = (int) (58 + shade * 20);
                    g = (int) (40 + shade * 16);
                    b = (int) (24 + shade * 12);
                    trim = Sprites.C_AMBER;
                    break;
                default:
                    r = (int) (24 + shade * 30);
                    g = (int) (30 + shade * 36);
                    b = (int) (44 + shade * 44);
                    trim = Sprites.C_CYAN;
                    break;
            }

            // side lighting
            float light = (side == 0) ? 0.62f : 0.92f;
            r = (int) (r * light);
            g = (int) (g * light);
            b = (int) (b * light);

            // distance fog
            float fog = perp / drawDistance;
            if (fog > 1f) fog = 1f;
            r = (int) (r + (BG_R - r) * fog);
            g = (int) (g + (BG_G - g) * fog);
            b = (int) (b + (BG_B - b) * fog);

            wallPaint.setColor(Color.rgb(r, g, b));
            canvas.drawRect(x, drawStart, x + 1, drawEnd, wallPaint);

            if (drawEnd <= drawStart) continue;

            // emissive trims
            int trimH = Math.max(1, lineH / 90);
            if (trimH < 1) trimH = 1;
            trimPaint.setColor(trim);
            trimPaint.setAlpha((int) (180 * (1f - fog)));
            canvas.drawRect(x, drawStart, x + 1, drawStart + trimH, trimPaint);
            canvas.drawRect(x, drawEnd - trimH, x + 1, drawEnd, trimPaint);

            // energy stripes on energy cells
            if (hitType == Map.T_ENERGY) {
                int sh = lineH / 5;
                int cy = drawStart + sh * 2;
                if (cy < drawEnd) canvas.drawRect(x, cy, x + 1, cy + Math.max(1, sh / 3), trimPaint);
                cy = drawStart + sh * 3;
                if (cy < drawEnd) canvas.drawRect(x, cy, x + 1, cy + Math.max(1, sh / 3), trimPaint);
            }

            // wall shadows: darken the very bottom near the floor for grounding
            if (wallShadows && fog < 0.7f) {
                int gnd = Math.max(1, lineH / 6);
                wallPaint.setColor(Color.argb((int) (120 * (1f - fog)), 0, 0, 0));
                canvas.drawRect(x, drawEnd - gnd, x + 1, drawEnd, wallPaint);
            }
        }
    }
}

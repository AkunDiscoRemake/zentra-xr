package com.neonforge.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import com.neonforge.world.Map;

/** Lightweight world-space particle pool (sparks, glows, impacts). */
public final class ParticleSystem {

    private int cap;
    private int count;
    private final float[] x, y, z, vx, vy, vz, life, maxLife, size, grav;
    private final int[] color;

    public ParticleSystem(int capacity) {
        cap = capacity;
        x = new float[cap]; y = new float[cap]; z = new float[cap];
        vx = new float[cap]; vy = new float[cap]; vz = new float[cap];
        life = new float[cap]; maxLife = new float[cap]; size = new float[cap]; grav = new float[cap];
        color = new int[cap];
        count = 0;
    }

    public void clear() { count = 0; }

    public int count() { return count; }

    public void spawn(float px, float py, float pz, float dx, float dy, float dz,
                      float ttl, float sz, int col, float gravity) {
        if (count >= cap) return;
        int i = count++;
        x[i] = px; y[i] = py; z[i] = pz;
        vx[i] = dx; vy[i] = dy; vz[i] = dz;
        life[i] = ttl; maxLife[i] = ttl; size[i] = sz; color[i] = col; grav[i] = gravity;
    }

    public void burst(float px, float py, float pz, int n, int col, float speed, float ttl, float sz) {
        for (int k = 0; k < n; k++) {
            float a = (float) (Math.random() * Math.PI * 2);
            float sp = speed * (0.4f + (float) Math.random() * 0.6f);
            spawn(px, py, pz,
                    (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                    (float) (Math.random() * speed * 0.7f),
                    ttl * (0.5f + (float) Math.random() * 0.5f),
                    sz * (0.5f + (float) Math.random() * 0.5f), col, 6f);
        }
    }

    public void update(float dt, Map map) {
        int w = 0;
        for (int i = 0; i < count; i++) {
            life[i] -= dt;
            if (life[i] <= 0f) continue;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            z[i] += vz[i] * dt;
            vz[i] -= grav[i] * dt;
            if (z[i] < 0f) { z[i] = 0f; vz[i] = -vz[i] * 0.3f; vx[i] *= 0.7f; vy[i] *= 0.7f; }
            if (map.isSolidAt(x[i], y[i])) {
                x[i] -= vx[i] * dt; y[i] -= vy[i] * dt;
                vx[i] *= -0.3f; vy[i] *= -0.3f;
            }
            // compact in place
            if (w != i) {
                x[w] = x[i]; y[w] = y[i]; z[w] = z[i];
                vx[w] = vx[i]; vy[w] = vy[i]; vz[w] = vz[i];
                life[w] = life[i]; maxLife[w] = maxLife[i];
                size[w] = size[i]; color[w] = color[i]; grav[w] = grav[i];
            }
            w++;
        }
        count = w;
    }

    public void draw(Canvas canvas, Renderer r) {
        if (count == 0) return;
        Paint p = r.particlePaint();
        float[] out = new float[2];
        float H = r.bufferHeight();
        for (int i = 0; i < count; i++) {
            if (!r.project(x[i], y[i], out)) continue;
            float sx = out[0], depth = out[1];
            if (depth <= 0.05f) continue;
            float s = (size[i] * H * 0.7f) / depth;
            if (s < 1f) continue;
            float a = life[i] / maxLife[i];
            p.setAlpha((int) (a * 230));
            android.graphics.Bitmap g = r.sprites().glowFor(color[i]);
            canvas.save();
            canvas.translate(sx, H * 0.5f - z[i] * H / depth);
            canvas.drawBitmap(g, -s / 2f, -s / 2f, p);
            canvas.restore();
        }
        p.setAlpha(255);
    }
}

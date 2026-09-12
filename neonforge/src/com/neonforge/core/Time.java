package com.neonforge.core;

/** Frame timing helper (fixed-ish delta, clamped to avoid physics explosions). */
public final class Time {
    private long last = -1;
    private float delta = 1f / 60f;
    private float fps = 60f;
    private float fpsSmooth = 60f;

    public void tick() {
        long now = System.nanoTime();
        if (last < 0) {
            last = now;
            return;
        }
        long ns = now - last;
        last = now;
        float d = ns / 1_000_000_000f;
        if (d <= 0f) d = 1f / 120f;
        if (d > 0.1f) d = 0.1f;
        delta = d;
        if (d > 0f) {
            float inst = 1f / d;
            fpsSmooth += (inst - fpsSmooth) * 0.08f;
        }
    }

    public float delta() { return delta; }

    public float fps() { return fpsSmooth; }
}

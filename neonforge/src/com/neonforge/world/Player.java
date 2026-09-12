package com.neonforge.world;

/** First-person player state: position, view, energy/shield/health and score. */
public final class Player {
    public float x, y;
    public float angle = 0f;
    public float pitch = 0f;      // -1..1 vertical look
    public float energy = 100f;
    public float shield = 100f;
    public float health = 100f;
    public int score = 0;
    public int coresCollected = 0;
    public boolean alive = true;
    public float hitFlash = 0f;

    public void reset(float sx, float sy, float a) {
        x = sx; y = sy; angle = a; pitch = 0f;
        energy = 100f; shield = 100f; health = 100f;
        score = 0; coresCollected = 0; alive = true; hitFlash = 0f;
    }

    public void update(float dt) {
        if (hitFlash > 0f) hitFlash = Math.max(0f, hitFlash - dt * 2f);
        // slow energy regen when not under stress
        if (energy < 100f) energy = Math.min(100f, energy + 4f * dt);
    }
}

package com.neonforge.world;

/** Robotic enemy: patrols, chases and fires energy bolts. No gore — deactivation. */
public final class Enemy {
    public static final int TYPE_DRONE = 0;   // fast, weak
    public static final int TYPE_GUARD = 1;   // slow, tanky

    public float x, y, angle;
    public int type;
    public float hp, maxHp;
    public int state = 0;          // 0 patrol, 1 chase, 2 attack
    public float fireTimer = 0f;
    public float patrolAngle = 0f;
    public float hitFlash = 0f;
    public float bob = 0f;
    public boolean alive = true;
    public float speed;

    public void spawn(float sx, float sy, int t) {
        x = sx; y = sy; type = t; angle = 0f;
        if (t == TYPE_DRONE) { maxHp = 40f; speed = 1.4f; }
        else { maxHp = 90f; speed = 0.8f; }
        hp = maxHp; state = 0; fireTimer = 0f; hitFlash = 0f; alive = true;
    }

    public void update(float dt, float px, float py, boolean seePlayer, Map map) {
        if (!alive) return;
        bob += dt * 6f;
        if (hitFlash > 0f) hitFlash = Math.max(0f, hitFlash - dt * 3f);

        float dx = px - x, dy = py - y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (seePlayer && dist < 9f) {
            state = (dist < 6.5f) ? 2 : 1;
        } else {
            state = 0;
        }

        float mx = 0, my = 0;
        if (state == 1) {           // chase
            mx = dx / Math.max(0.001f, dist);
            my = dy / Math.max(0.001f, dist);
        } else if (state == 2) {    // strafe + keep distance
            float perp = ((dist < 4f) ? -1f : 1f);
            mx = (-dy / Math.max(0.001f, dist)) * perp * 0.6f + (dx / Math.max(0.001f, dist)) * 0.4f;
            my = (dx / Math.max(0.001f, dist)) * perp * 0.6f + (dy / Math.max(0.001f, dist)) * 0.4f;
        } else {                    // patrol: slow drift
            patrolAngle += dt * 0.6f;
            mx = (float) Math.cos(patrolAngle) * 0.4f;
            my = (float) Math.sin(patrolAngle) * 0.4f;
        }

        float[] out = new float[2];
        map.collideMove(x, y, 0.3f, mx * speed * dt, my * speed * dt, out);
        x = out[0]; y = out[1];
        angle = (float) Math.atan2(py - y, px - x);

        if (state >= 1) fireTimer -= dt;
    }

    public boolean wantsToFire() {
        return alive && state >= 1 && fireTimer <= 0f;
    }
}

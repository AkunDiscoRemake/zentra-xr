package com.neonforge.world;

/** Interactive physics object: energy cores (throwable) and batteries (power). */
public final class Pickup {
    public static final int TYPE_CORE = 0;
    public static final int TYPE_BATTERY = 1;

    public static final int S_IDLE = 0;      // resting on the floor
    public static final int S_SELECTED = 1;  // holographic highlight
    public static final int S_PULLED = 2;    // being pulled toward the hand
    public static final int S_GRABBED = 3;   // held by the hand
    public static final int S_THROWN = 4;    // ballistic physics

    public float x, y;
    public float height = 0f;    // visual height above floor (for arcs)
    public float vx = 0f, vy = 0f, vz = 0f;
    public int type = TYPE_CORE;
    public int state = S_IDLE;
    public float bob = 0f;
    public boolean active = true;
    public float radius = 0.32f;
    public float mass = 1f;

    public void spawn(float sx, float sy, int t) {
        x = sx; y = sy; type = t;
        height = 0f; vx = 0f; vy = 0f; vz = 0f;
        state = S_IDLE; active = true; bob = (float) (Math.random() * 6.28);
        radius = (t == TYPE_CORE) ? 0.3f : 0.28f;
        mass = (t == TYPE_CORE) ? 1.6f : 1.2f;
    }

    public void update(float dt, Map map) {
        if (!active) return;
        bob += dt * 3f;

        if (state == S_IDLE) {
            vx *= 0.9f; vy *= 0.9f;
        }

        if (state == S_THROWN || state == S_PULLED) {
            // horizontal motion with wall collision + bounce
            x += vx * dt;
            y += vy * dt;
            if (map.isSolidAt(x, y)) {
                x -= vx * dt; y -= vy * dt;
                // try axis separated bounce
                if (!map.isSolidAt(x + vx * dt, y)) { x += vx * dt; vx = -vx * 0.5f; }
                else if (!map.isSolidAt(x, y + vy * dt)) { y += vy * dt; vy = -vy * 0.5f; }
                else { vx = -vx * 0.4f; vy = -vy * 0.4f; }
            }
            // gravity on vertical axis
            vz -= 9.8f * dt;
            height += vz * dt;
            if (height < 0f) {
                height = 0f;
                vz = -vz * 0.35f;
                vx *= 0.8f; vy *= 0.8f;
                if (Math.abs(vz) < 0.5f) vz = 0f;
            }
            vx *= (1f - 0.6f * dt);
            vy *= (1f - 0.6f * dt);
            if (state == S_THROWN && Math.abs(vx) + Math.abs(vy) < 0.05f && height <= 0.01f) {
                state = S_IDLE;
            }
        }
    }

    public void grab(float hx, float hy) {
        state = S_GRABBED;
        height = 0.5f;
        vx = 0f; vy = 0f; vz = 0f;
        x = hx; y = hy;
    }

    public void throwFrom(float hx, float hy, float dx, float dy, float power) {
        state = S_THROWN;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) { dx = 1f; len = 1f; }
        vx = dx / len * power;
        vy = dy / len * power;
        vz = 2.2f;
        height = 0.4f;
    }

    public void select() {
        if (state == S_IDLE) state = S_SELECTED;
    }

    public void deselect() {
        if (state == S_SELECTED) state = S_IDLE;
    }
}

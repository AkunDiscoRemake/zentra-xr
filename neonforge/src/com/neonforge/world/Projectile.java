package com.neonforge.world;

/** Energy bolt fired by the player or an enemy. */
public final class Projectile {
    public float x, y, vx, vy;
    public float life = 2.5f;
    public float damage = 20f;
    public boolean ownerIsPlayer;
    public boolean active = false;
    public float r = 0.14f;

    public void fire(float sx, float sy, float dx, float dy, float speed, float dmg, boolean player) {
        x = sx; y = sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) { dx = 1f; len = 1f; }
        vx = dx / len * speed;
        vy = dy / len * speed;
        damage = dmg;
        ownerIsPlayer = player;
        life = 2.5f;
        active = true;
    }

    public boolean update(float dt, Map map) {
        if (!active) return false;
        life -= dt;
        if (life <= 0f) { active = false; return false; }
        x += vx * dt;
        y += vy * dt;
        if (map.isSolidAt(x, y)) {
            active = false;
            return false;
        }
        return true;
    }
}

package com.neonforge.combat;

import com.neonforge.audio.AudioEngine;
import com.neonforge.hand.Gesture;
import com.neonforge.hand.GestureSystem;
import com.neonforge.render.ParticleSystem;
import com.neonforge.render.Sprites;
import com.neonforge.world.Enemy;
import com.neonforge.world.Map;
import com.neonforge.world.Physics;
import com.neonforge.world.Pickup;
import com.neonforge.world.Player;
import com.neonforge.world.Projectile;

import java.util.Random;

/**
 * CombatSystem: charging/firing energy, shield blocking, enemy AI fire,
 * projectile collisions and enemy deactivation (no gore — energy only).
 */
public final class CombatSystem {

    private final Enemy[] enemies;
    private final Projectile[] projectiles;
    private final Pickup[] pickups;
    private final Player player;
    private final Map map;
    private final ParticleSystem particles;
    private final AudioEngine audio;
    private final Random rng = new Random(7);

    public boolean shieldActive = false;

    public CombatSystem(Enemy[] e, Projectile[] p, Pickup[] k, Player pl, Map m,
                        ParticleSystem ps, AudioEngine a) {
        enemies = e; projectiles = p; pickups = k; player = pl; map = m;
        particles = ps; audio = a;
    }

    public void playerFire(float charge, float dirX, float dirY, float originX, float originY) {
        if (charge < 0.15f) return;
        float power = 0.4f + charge * 0.8f;
        Projectile j = findProjectile();
        if (j == null) return;
        j.fire(originX, originY, dirX, dirY, 9f + charge * 8f, 14f + charge * 40f, true);
        player.energy = Math.max(0f, player.energy - (10f + charge * 22f));
        particles.burst(originX, originY, 0.5f, 8, Sprites.C_CYAN, 1.5f, 0.4f, 0.08f);
        audio.play(AudioEngine.S_SHOOT);
    }

    public void update(float dt, GestureSystem.Result g, float px, float py, float aimDX, float aimDY) {
        shieldActive = (g.gesture == Gesture.OPEN_PALM);
        if (shieldActive) {
            player.shield = Math.max(0f, player.shield - 14f * dt);
        } else if (player.shield < 100f) {
            player.shield = Math.min(100f, player.shield + 10f * dt);
        }

        // enemy fire
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            boolean see = map.hasLineOfSight(e.x, e.y, px, py);
            float ddx = px - e.x, ddy = py - e.y;
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            e.update(dt, px, py, see, map);
            if (e.wantsToFire() && see && dist < 9f) {
                e.fireTimer = 1.6f + rng.nextFloat() * 1.2f;
                Projectile j = findProjectile();
                if (j != null) {
                    float sp = (e.type == Enemy.TYPE_GUARD) ? 3.6f : 5.2f;
                    j.fire(e.x, e.y, ddx, ddy, sp, 8f, false);
                    particles.burst(e.x, e.y, 0.5f, 4, Sprites.C_RED, 1.0f, 0.3f, 0.06f);
                    audio.play(AudioEngine.S_SHOOT);
                }
            }
        }

        // projectiles
        for (Projectile j : projectiles) {
            if (!j.active) continue;
            boolean alive = j.update(dt, map);
            if (!alive) {
                particles.burst(j.x, j.y, 0.4f, 6, Sprites.C_CYAN, 1.2f, 0.35f, 0.06f);
                audio.play(AudioEngine.S_IMPACT);
                continue;
            }
            if (j.ownerIsPlayer) {
                for (Enemy e : enemies) {
                    if (!e.alive) continue;
                    if (Physics.circlesHit(j.x, j.y, j.r, e.x, e.y, 0.42f)) {
                        damageEnemy(e, j.damage);
                        j.active = false;
                        break;
                    }
                }
            } else {
                if (Physics.circlesHit(j.x, j.y, j.r, px, py, 0.3f)) {
                    j.active = false;
                    onPlayerHit();
                }
            }
        }

        // thrown cores damage enemies
        for (Pickup k : pickups) {
            if (!k.active || k.state != Pickup.S_THROWN) continue;
            if (Math.abs(k.vx) + Math.abs(k.vy) < 1.5f) continue;
            for (Enemy e : enemies) {
                if (!e.alive) continue;
                if (Physics.circlesHit(k.x, k.y, k.radius, e.x, e.y, 0.45f)) {
                    damageEnemy(e, 30f);
                    k.vx *= 0.2f; k.vy *= 0.2f;
                    break;
                }
            }
        }
    }

    private void damageEnemy(Enemy e, float dmg) {
        e.hp -= dmg;
        e.hitFlash = 1f;
        particles.burst(e.x, e.y, 0.6f, 10, Sprites.C_MAGENTA, 2.2f, 0.5f, 0.09f);
        audio.play(AudioEngine.S_IMPACT);
        if (e.hp <= 0f) {
            e.alive = false;
            particles.burst(e.x, e.y, 0.7f, 30, Sprites.C_CYAN, 3.2f, 0.8f, 0.12f);
            particles.burst(e.x, e.y, 0.4f, 20, Sprites.C_MAGENTA, 2.5f, 0.6f, 0.1f);
            audio.play(AudioEngine.S_EXPLODE);
            player.score += (e.type == Enemy.TYPE_GUARD) ? 250 : 120;
        }
    }

    private void onPlayerHit() {
        if (shieldActive) {
            particles.burst(player.x, player.y, 0.5f, 14, Sprites.C_VIOLET, 2.5f, 0.4f, 0.1f);
            audio.play(AudioEngine.S_SHIELD);
            return;
        }
        player.health -= 9f;
        player.hitFlash = 1f;
        particles.burst(player.x, player.y, 0.5f, 12, Sprites.C_RED, 2.2f, 0.5f, 0.1f);
        audio.play(AudioEngine.S_HURT);
        if (player.health <= 0f) {
            player.health = 0f;
            player.alive = false;
        }
    }

    private Projectile findProjectile() {
        for (Projectile j : projectiles) if (!j.active) return j;
        return null;
    }

    public int aliveEnemies() {
        int n = 0;
        for (Enemy e : enemies) if (e.alive) n++;
        return n;
    }
}

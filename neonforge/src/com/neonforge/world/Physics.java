package com.neonforge.world;

/**
 * PhysicsSystem: integrates thrown/grabbed objects, resolves wall collisions,
 * applies gravity + friction and reports impacts. Kept independent so combat
 * and interaction share the same rules.
 */
public final class Physics {

    /** Circle vs circle in the ground plane. */
    public static boolean circlesHit(float ax, float ay, float ar, float bx, float by, float br) {
        float dx = ax - bx, dy = ay - by;
        float rr = ar + br;
        return dx * dx + dy * dy <= rr * rr;
    }

    /** Advance a body and bounce off walls (returns impact count). */
    public static int stepBody(float[] body, float radius, float dt, Map map, float gravity,
                               float bounce, float friction) {
        // body = {x, y, vx, vy, height, vz}
        body[0] += body[2] * dt;
        body[1] += body[3] * dt;
        int hits = 0;
        if (map.isSolidAt(body[0], body[1])) {
            body[0] -= body[2] * dt;
            body[1] -= body[3] * dt;
            if (!map.isSolidAt(body[0] + body[2] * dt, body[1])) {
                body[0] += body[2] * dt;
                body[2] = -body[2] * bounce;
                hits++;
            } else if (!map.isSolidAt(body[0], body[1] + body[3] * dt)) {
                body[1] += body[3] * dt;
                body[3] = -body[3] * bounce;
                hits++;
            } else {
                body[2] = -body[2] * bounce * 0.5f;
                body[3] = -body[3] * bounce * 0.5f;
                hits++;
            }
        }
        body[4] += body[5] * dt;            // height
        body[5] -= gravity * dt;            // vz
        if (body[4] < 0f) {
            body[4] = 0f;
            body[5] = -body[5] * bounce;
            if (Math.abs(body[5]) < 0.5f) body[5] = 0f;
        }
        float f = Math.max(0f, 1f - friction * dt);
        body[2] *= f;
        body[3] *= f;
        return hits;
    }
}

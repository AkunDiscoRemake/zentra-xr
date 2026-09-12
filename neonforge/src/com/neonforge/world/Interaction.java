package com.neonforge.world;

/**
 * InteractionSystem: ray-targeting for pickups and doors, pull/grab/throw
 * state machine helpers. Kept as pure functions over the world state.
 */
public final class Interaction {

    private Interaction() {}

    /** Find the nearest pickup along the aim direction within a cone. */
    public static Pickup findTarget(Pickup[] pickups, float px, float py, float dirX, float dirY,
                                    float maxDist, float cone) {
        Pickup best = null;
        float bestScore = 1e9f;
        for (Pickup k : pickups) {
            if (!k.active) continue;
            float dx = k.x - px, dy = k.y - py;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > maxDist) continue;
            float inv = 1f / Math.max(0.0001f, dist);
            float dot = (dx * inv) * dirX + (dy * inv) * dirY;
            if (dot < cone) continue;
            float score = dist - dot * 2f;   // prefer centered + near
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    /** Find the door cell in front of the player. */
    public static boolean findDoor(Map map, float px, float py, float dirX, float dirY, float maxDist) {
        for (int s = 1; s <= (int) (maxDist * 8); s++) {
            float t = s * 0.125f;
            int cx = (int) Math.floor(px + dirX * t);
            int cy = (int) Math.floor(py + dirY * t);
            if (map.typeAt(cx, cy) == Map.T_DOOR) return true;
            if (map.isSolidAt(px + dirX * t, py + dirY * t)) break;
        }
        return false;
    }

    /** Open every door cell near a world point (used by the AoE power). */
    public static void openDoorsNear(Map map, float x, float y, float radius) {
        for (int cy = 0; cy < Map.H; cy++) {
            for (int cx = 0; cx < Map.W; cx++) {
                if (map.typeAt(cx, cy) != Map.T_DOOR) continue;
                float dx = cx + 0.5f - x, dy = cy + 0.5f - y;
                if (dx * dx + dy * dy <= radius * radius) {
                    map.openDoor(cx, cy);
                }
            }
        }
    }
}

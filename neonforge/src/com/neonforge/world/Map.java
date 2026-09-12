package com.neonforge.world;

import java.util.Random;

/**
 * The abandoned neon installation: a grid map with corridors, a main room,
 * side rooms, sliding doors, energy sources, machines and spawn points.
 */
public final class Map {

    public static final int W = 24;
    public static final int H = 24;

    public static final int T_FLOOR = 0;
    public static final int T_WALL = 1;
    public static final int T_DOOR = 2;
    public static final int T_ENERGY = 3;   // emissive wall decor (solid)
    public static final int T_MACHINE = 4;  // glowing machine (floor, non-solid)

    public final int[][] grid = new int[H][W];
    public final float[] doorOpen = new float[W * H];     // 0..1 slide amount
    public final float[] shade = new float[W * H];        // deterministic 0..1 material variety

    // spawn points
    public float playerX = 12.5f, playerY = 12.5f;
    public float playerAngle = 0f;
    public final float[] enemyX = new float[16];
    public final float[] enemyY = new float[16];
    public final float[] coreX = new float[24];
    public final float[] coreY = new float[24];
    public final float[] batteryX = new float[12];
    public final float[] batteryY = new float[12];
    public int enemyCount = 0;
    public int coreCount = 0;
    public int batteryCount = 0;

    private static final String[] LAYOUT = {
        "########################",
        "#..........#...........#",
        "#..EE......#....EE.....#",
        "#..........D...........#",
        "#..........#...........#",
        "#......................#",
        "#..M.......EE.....M....#",
        "#......................#",
        "#......................#",
        "#......................#",
        "#......................#",
        "#.........P............#",
        "#......................#",
        "#......................#",
        "#......................#",
        "#..M.......EE.....M....#",
        "#......................#",
        "#..........D...........#",
        "#..........#....EE.....#",
        "#..........#...........#",
        "#..........#...........#",
        "#..EE......#....M......#",
        "#..........#...........#",
        "########################",
    };

    private final Random rng = new Random(1337);

    public Map() {
        for (int y = 0; y < H; y++) {
            String row = LAYOUT[y];
            for (int x = 0; x < W; x++) {
                char c = row.charAt(x);
                switch (c) {
                    case '#': grid[y][x] = T_WALL; break;
                    case 'D': grid[y][x] = T_DOOR; break;
                    case 'E': grid[y][x] = T_ENERGY; break;
                    case 'M': grid[y][x] = T_MACHINE; break;
                    case 'P': grid[y][x] = T_FLOOR; playerX = x + 0.5f; playerY = y + 0.5f; break;
                    default: grid[y][x] = T_FLOOR;
                }
            }
        }

        // scatter spawn points deterministically
        for (int y = 2; y < H - 2; y++) {
            for (int x = 2; x < W - 2; x++) {
                if (grid[y][x] != T_FLOOR) continue;
                int roll = rng.nextInt(1000);
                if (roll < 26 && enemyCount < enemyX.length) {
                    enemyX[enemyCount] = x + 0.5f; enemyY[enemyCount] = y + 0.5f; enemyCount++;
                } else if (roll < 96 && coreCount < coreX.length) {
                    coreX[coreCount] = x + 0.5f; coreY[coreCount] = y + 0.5f; coreCount++;
                } else if (roll < 118 && batteryCount < batteryX.length) {
                    batteryX[batteryCount] = x + 0.5f; batteryY[batteryCount] = y + 0.5f; batteryCount++;
                }
            }
        }

        for (int i = 0; i < W * H; i++) {
            shade[i] = rng.nextFloat();
        }
    }

    public int typeAt(int x, int y) {
        if (x < 0 || y < 0 || x >= W || y >= H) return T_WALL;
        return grid[y][x];
    }

    public boolean isSolid(int x, int y) {
        int t = typeAt(x, y);
        if (t == T_WALL || t == T_ENERGY) return true;
        if (t == T_DOOR) return doorOpen[y * W + x] < 0.55f;
        return false;
    }

    public boolean isSolidAt(float fx, float fy) {
        return isSolid((int) Math.floor(fx), (int) Math.floor(fy));
    }

    public void update(float dt) {
        // auto-close doors slowly; opening is triggered by proximity/interaction
        for (int i = 0; i < doorOpen.length; i++) {
            float o = doorOpen[i];
            if (o > 0.001f) {
                doorOpen[i] = Math.max(0f, o - dt * 0.25f);
            }
        }
    }

    public void openDoor(int x, int y) {
        if (typeAt(x, y) == T_DOOR) {
            doorOpen[y * W + x] = Math.min(1f, doorOpen[y * W + x] + 0.06f);
        }
    }

    public void slamDoor(int x, int y) {
        if (typeAt(x, y) == T_DOOR) {
            doorOpen[y * W + x] = 0f;
        }
    }

    /** DDA line-of-sight between two world points. */
    public boolean hasLineOfSight(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.001f) return true;
        int steps = (int) Math.ceil(dist * 4f);
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            if (isSolidAt(x0 + dx * t, y0 + dy * t)) return false;
        }
        return true;
    }

    /** Move an entity with wall sliding. Returns the new position via the out array. */
    public void collideMove(float x, float y, float radius, float dx, float dy, float[] out) {
        float nx = x + dx;
        float ny = y + dy;
        float r = radius;
        if (!isSolidAt(nx - r, y - r) && !isSolidAt(nx + r, y - r)
                && !isSolidAt(nx - r, y + r) && !isSolidAt(nx + r, y + r)) {
            x = nx;
        }
        if (!isSolidAt(x - r, ny - r) && !isSolidAt(x + r, ny - r)
                && !isSolidAt(x - r, ny + r) && !isSolidAt(x + r, ny + r)) {
            y = ny;
        }
        out[0] = x; out[1] = y;
    }
}

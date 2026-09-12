package com.neonforge.hand;

/**
 * Computer-vision hand tracker: skin-color segmentation + contour / convex
 * hull / convexity-defect fingertip detection on downsampled NV21 frames.
 *
 * Runs entirely on-device, no ML model download, low latency. Outputs up to
 * two HandState objects with fingertip / palm / wrist positions and a set of
 * continuous signals (pinch, fist, open) consumed by the GestureSystem.
 */
public final class HandTracker {

    private static final int GW = 80;
    private static final int GH = 60;
    private static final int N = GW * GH;

    private final byte[] mask = new byte[N];
    private final byte[] luma = new byte[N];
    private final int[] queue = new int[N];
    private final int[] label = new int[N];
    private final float[] px = new float[N];
    private final float[] py = new float[N];

    private final HandState handA = new HandState();
    private final HandState handB = new HandState();

    // smoothing filters
    private final OneEuroFilter fpX, fpY, fiX, fiY, ftX, ftY, fr, fpin, ffist, fopen;

    private int compCount = 0;

    public HandTracker() {
        // per-hand smoothing; timestamps in seconds
        float mc = 0.7f;
        fpX = new OneEuroFilter(mc, 0.6f, 1.0f);
        fpY = new OneEuroFilter(mc, 0.6f, 1.0f);
        fiX = new OneEuroFilter(mc, 0.6f, 1.0f);
        fiY = new OneEuroFilter(mc, 0.6f, 1.0f);
        ftX = new OneEuroFilter(mc, 0.6f, 1.0f);
        ftY = new OneEuroFilter(mc, 0.6f, 1.0f);
        fr = new OneEuroFilter(0.6f, 0.4f, 1.0f);
        fpin = new OneEuroFilter(0.5f, 0.3f, 1.0f);
        ffist = new OneEuroFilter(0.5f, 0.3f, 1.0f);
        fopen = new OneEuroFilter(0.5f, 0.3f, 1.0f);
    }

    public HandState dominantHand() { return handA; }
    public HandState secondHand() { return handB; }

    /** @return true if at least one hand is tracked this frame */
    public boolean processFrame(byte[] nv21, int w, int h, long timestampMs) {
        float t = timestampMs / 1000f;
        boolean any = processNV21(nv21, w, h);
        if (!any) {
            handA.tracked = false;
            handB.tracked = false;
            return false;
        }
        return true;
    }

    private boolean processNV21(byte[] nv21, int w, int h) {
        int yLen = w * h;
        if (nv21 == null || nv21.length < yLen + yLen / 2) return false;
        if (w <= 0 || h <= 0) return false;

        // 1) skin mask
        for (int gy = 0; gy < GH; gy++) {
            int srcY = gy * h / GH;
            int row = srcY * w;
            for (int gx = 0; gx < GW; gx++) {
                int srcX = gx * w / GW;
                int idx = gy * GW + gx;
                int yi = nv21[row + srcX] & 0xFF;
                int uvIdx = yLen + (srcY / 2) * w + (srcX & ~1);
                int v = nv21[uvIdx] & 0xFF;
                int u = nv21[uvIdx + 1] & 0xFF;
                luma[idx] = (byte) yi;
                mask[idx] = skin(yi, u, v) ? (byte) 1 : (byte) 0;
            }
        }

        cleanupMask();

        // 2) connected components (4-connectivity)
        int[] areas = new int[32];
        int[] compSeed = new int[32];
        compCount = 0;
        java.util.Arrays.fill(label, -1);
        for (int i = 0; i < N; i++) {
            if (mask[i] == 0 || label[i] != -1) continue;
            if (compCount >= 32) break;
            int area = flood(i, compCount);
            areas[compCount] = area;
            compSeed[compCount] = i;
            compCount++;
        }

        // find up to 2 largest components
        int best = -1, bestArea = 0, second = -1, secondArea = 0;
        for (int c = 0; c < compCount; c++) {
            if (areas[c] > bestArea) {
                second = best; secondArea = bestArea;
                best = c; bestArea = areas[c];
            } else if (areas[c] > secondArea) {
                second = c; secondArea = areas[c];
            }
        }

        // hand must be reasonably large
        if (best < 0 || bestArea < 18) {
            return false;
        }

        float t = System.nanoTime() / 1e9f;
        boolean ok = extractHand(best, bestArea, handA, t);
        if (ok && second >= 0 && secondArea >= 18) {
            extractHand(second, secondArea, handB, t);
        } else {
            handB.tracked = false;
        }
        return ok;
    }

    private boolean extractHand(int comp, int area, HandState hs, float t) {
        // gather boundary cells
        int bcount = 0;
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                int i = gy * GW + gx;
                if (label[i] != comp) continue;
                if (isBoundary(gx, gy, comp)) {
                    if (bcount < N) {
                        px[bcount] = gx;
                        py[bcount] = gy;
                        bcount++;
                    }
                }
            }
        }
        if (bcount < 8) return false;

        // centroid
        float cx = 0, cy = 0;
        for (int i = 0; i < N; i++) {
            if (label[i] == comp) {
                cx += (i % GW);
                cy += (i / GW);
            }
        }
        cx /= area; cy /= area;

        // convex hull over boundary points (Andrew monotone chain, points sorted by x)
        int[] hull = convexHull(bcount);

        // radii of hull vertices
        int hn = hullCount;
        float[] hr = new float[hn];
        float maxR = 0;
        for (int i = 0; i < hn; i++) {
            float dx = px[hull[i]] - cx;
            float dy = py[hull[i]] - cy;
            hr[i] = (float) Math.sqrt(dx * dx + dy * dy);
            if (hr[i] > maxR) maxR = hr[i];
        }
        if (maxR < 2f) return false;

        // fingertips = local maxima of radius along hull
        int tipCount = 0;
        float[] tipsX = new float[8];
        float[] tipsY = new float[8];
        for (int i = 0; i < hn; i++) {
            float prev = hr[(i + hn - 1) % hn];
            float cur = hr[i];
            float next = hr[(i + 1) % hn];
            if (cur >= prev && cur >= next && cur > 0.58f * maxR) {
                if (tipCount < 8) {
                    tipsX[tipCount] = px[hull[i]];
                    tipsY[tipCount] = py[hull[i]];
                    tipCount++;
                }
            }
        }

        // merge tips that are very close
        tipCount = mergeTips(tipsX, tipsY, tipCount, Math.max(2.5f, maxR * 0.5f));

        float openRatio = (float) area / (float) hullArea();
        int fingers = tipCount;
        if (openRatio > 0.84f && maxR < 6f && tipCount <= 1) {
            fingers = 0; // likely a fist: round, small
        }

        // index tip = topmost tip; thumb = closest other tip (pinch uses min pairwise)
        float ix = cx, iy = cy, tx = cx, ty = cy;
        if (tipCount >= 1) {
            int top = 0;
            for (int k = 1; k < tipCount; k++) if (tipsY[k] < tipsY[top]) top = k;
            ix = tipsX[top]; iy = tipsY[top];
        }
        float minPair = 1e9f;
        for (int a = 0; a < tipCount; a++) {
            for (int b = a + 1; b < tipCount; b++) {
                float dx = tipsX[a] - tipsX[b];
                float dy = tipsY[a] - tipsY[b];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < minPair) minPair = d;
            }
        }
        if (tipCount >= 2 && minPair < 1e8f) {
            // thumb = the tip closest to index
            float bx = ix, by = iy;
            int bestIdx = -1;
            float bd = 1e9f;
            for (int k = 0; k < tipCount; k++) {
                float dx = tipsX[k] - ix;
                float dy = tipsY[k] - iy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 0.001f && d < bd) { bd = d; bx = tipsX[k]; by = tipsY[k]; bestIdx = k; }
            }
            if (bestIdx >= 0) { tx = bx; ty = by; }
        } else if (tipCount == 1) {
            tx = ix; ty = iy;
        }

        // wrist: opposite the fingertip mean, beyond the centroid
        float mx = 0, my = 0;
        for (int k = 0; k < tipCount; k++) { mx += tipsX[k]; my += tipsY[k]; }
        float wx, wy;
        if (tipCount > 0) {
            mx /= tipCount; my /= tipCount;
            float ddx = cx - mx, ddy = cy - my;
            float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            if (len < 0.5f) { ddx = 0; ddy = 1; len = 1; }
            wx = cx + ddx / len * (maxR * 1.5f);
            wy = cy + ddy / len * (maxR * 1.5f);
        } else {
            wx = cx; wy = cy + maxR * 1.4f;
        }

        // continuous signals
        float radius = maxR;
        float pinch = 0f;
        if (tipCount >= 2 && minPair < 1e8f) {
            pinch = clamp(1f - minPair / Math.max(2.2f, radius * 2.2f), 0f, 1f);
        }
        float fist = clamp(1f - fingers / 5f, 0f, 1f) * 0.5f + clamp((openRatio - 0.6f) / 0.3f, 0f, 1f) * 0.5f;
        float open = clamp(fingers / 5f, 0f, 1f) * 0.6f + clamp((0.9f - openRatio) / 0.4f, 0f, 1f) * 0.4f;
        float quality = clamp(area / 900f, 0f, 1f);

        // normalize to 0..1 camera space (flip X for mirror view)
        float nx = 1f - (cx + 0.5f) / GW;
        float ny = (cy + 0.5f) / GH;

        hs.tracked = true;
        hs.palmX = fpX.filter(nx, t);
        hs.palmY = fpY.filter(ny, t);
        hs.indexX = fiX.filter(1f - (ix + 0.5f) / GW, t);
        hs.indexY = fiY.filter((iy + 0.5f) / GH, t);
        hs.thumbX = ftX.filter(1f - (tx + 0.5f) / GW, t);
        hs.thumbY = ftY.filter((ty + 0.5f) / GH, t);
        hs.wristX = 1f - (wx + 0.5f) / GW;
        hs.wristY = (wy + 0.5f) / GH;
        hs.radius = fr.filter(radius / GW, t);
        hs.fingers = fingers;
        hs.pinch = fpin.filter(pinch, t);
        hs.fist = ffist.filter(fist, t);
        hs.open = fopen.filter(open, t);
        hs.quality = quality;
        return true;
    }

    private int hullCount;
    private final int[] hullStack = new int[N];

    /** Returns hull vertex indices into px/py (points pre-sorted by x,y in place). */
    private int[] convexHull(int n) {
        // sort boundary points by x then y (selection sort ok for small n)
        for (int i = 0; i < n - 1; i++) {
            int m = i;
            for (int j = i + 1; j < n; j++) {
                if (px[j] < px[m] || (px[j] == px[m] && py[j] < py[m])) m = j;
            }
            float tx = px[i]; px[i] = px[m]; px[m] = tx;
            float ty = py[i]; py[i] = py[m]; py[m] = ty;
        }
        int k = 0;
        // lower hull
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hullStack[k - 2], hullStack[k - 1], i) <= 0) k--;
            hullStack[k++] = i;
        }
        int lower = k;
        // upper hull
        for (int i = n - 2; i >= 0; i--) {
            while (k > lower && cross(hullStack[k - 2], hullStack[k - 1], i) <= 0) k--;
            hullStack[k++] = i;
        }
        hullCount = k - 1; // exclude duplicated first point
        return hullStack;
    }

    private float cross(int o, int a, int b) {
        return (px[a] - px[o]) * (py[b] - py[o]) - (py[a] - py[o]) * (px[b] - px[o]);
    }

    private int hullArea() {
        // shoelace on hull vertices
        float s = 0;
        for (int i = 0; i < hullCount; i++) {
            int a = hullStack[i];
            int b = hullStack[(i + 1) % hullCount];
            s += px[a] * py[b] - px[b] * py[a];
        }
        return (int) (Math.abs(s) / 2f);
    }

    private int mergeTips(float[] xs, float[] ys, int n, float minDist) {
        boolean[] keep = new boolean[n];
        java.util.Arrays.fill(keep, true);
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!keep[j]) continue;
                float dx = xs[i] - xs[j];
                float dy = ys[i] - ys[j];
                if (Math.sqrt(dx * dx + dy * dy) < minDist) {
                    keep[j] = false;
                }
            }
        }
        int out = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) { xs[out] = xs[i]; ys[out] = ys[i]; out++; }
        }
        return out;
    }

    private int flood(int seed, int cid) {
        int head = 0, tail = 0;
        queue[tail++] = seed;
        label[seed] = cid;
        int area = 0;
        while (head < tail) {
            int i = queue[head++];
            area++;
            int x = i % GW, y = i / GW;
            int nb;
            if (x > 0) { nb = i - 1; if (mask[nb] != 0 && label[nb] == -1) { label[nb] = cid; queue[tail++] = nb; } }
            if (x < GW - 1) { nb = i + 1; if (mask[nb] != 0 && label[nb] == -1) { label[nb] = cid; queue[tail++] = nb; } }
            if (y > 0) { nb = i - GW; if (mask[nb] != 0 && label[nb] == -1) { label[nb] = cid; queue[tail++] = nb; } }
            if (y < GH - 1) { nb = i + GW; if (mask[nb] != 0 && label[nb] == -1) { label[nb] = cid; queue[tail++] = nb; } }
        }
        return area;
    }

    private boolean isBoundary(int x, int y, int cid) {
        int i = y * GW + x;
        if (x == 0 || x == GW - 1 || y == 0 || y == GH - 1) return true;
        return label[i - 1] != cid || label[i + 1] != cid || label[i - GW] != cid || label[i + GW] != cid;
    }

    private void cleanupMask() {
        // one erosion + one dilation (3x3) to remove speckle
        byte[] tmp = new byte[N];
        for (int y = 1; y < GH - 1; y++) {
            for (int x = 1; x < GW - 1; x++) {
                int i = y * GW + x;
                int s = mask[i] + mask[i - 1] + mask[i + 1] + mask[i - GW] + mask[i + GW]
                        + mask[i - GW - 1] + mask[i - GW + 1] + mask[i + GW - 1] + mask[i + GW + 1];
                tmp[i] = (byte) (s >= 7 ? 1 : 0);
            }
        }
        System.arraycopy(tmp, 0, mask, 0, N);
        for (int y = 1; y < GH - 1; y++) {
            for (int x = 1; x < GW - 1; x++) {
                int i = y * GW + x;
                int s = mask[i] + mask[i - 1] + mask[i + 1] + mask[i - GW] + mask[i + GW];
                tmp[i] = (byte) (s >= 1 ? 1 : 0);
            }
        }
        System.arraycopy(tmp, 0, mask, 0, N);
    }

    private static boolean skin(int y, int u, int v) {
        int c = y - 16;
        int d = u - 128;
        int e = v - 128;
        int r = clamp8((298 * c + 409 * e + 128) >> 8);
        int g = clamp8((298 * c - 100 * d - 208 * e + 128) >> 8);
        int b = clamp8((298 * c + 516 * d + 128) >> 8);
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return r > 95 && g > 40 && b > 20 && r > g && r > b
                && (mx - mn) > 15 && Math.abs(r - g) > 15 && y > 55;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

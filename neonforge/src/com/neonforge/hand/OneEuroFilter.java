package com.neonforge.hand;

/** One Euro Filter: low-latency smoothing for noisy hand/joint signals. */
public final class OneEuroFilter {
    private final float minCutoff;
    private final float beta;
    private final float dCutoff;
    private float xPrev;
    private float dxPrev;
    private boolean first = true;
    private float tPrev;

    public OneEuroFilter(float minCutoff, float beta, float dCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.dCutoff = dCutoff;
    }

    public float filter(float x, float t) {
        if (first) {
            first = false;
            xPrev = x;
            dxPrev = 0f;
            tPrev = t;
            return x;
        }
        float dt = t - tPrev;
        if (dt <= 0f) dt = 1f / 60f;
        tPrev = t;

        float dx = (x - xPrev) / dt;
        float edx = lowPass(dx, dxPrev, alpha(dCutoff, dt));
        dxPrev = edx;
        float cutoff = minCutoff + beta * Math.abs(edx);
        float xHat = lowPass(x, xPrev, alpha(cutoff, dt));
        xPrev = xHat;
        return xHat;
    }

    private float lowPass(float x, float prev, float a) {
        return a * x + (1f - a) * prev;
    }

    private float alpha(float cutoff, float dt) {
        float tau = 1f / (2f * 3.14159265f * cutoff);
        return 1f / (1f + tau / dt);
    }

    public void reset() {
        first = true;
    }
}

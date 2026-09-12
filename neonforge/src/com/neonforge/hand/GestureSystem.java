package com.neonforge.hand;

import com.neonforge.core.Settings;

/**
 * Stable gesture recognition with hysteresis + confidence gating, built on top
 * of the raw HandTracker output. Also produces aim coordinates and continuous
 * charge / pinch values for the game systems.
 */
public final class GestureSystem {

    public static final class Result {
        public Gesture gesture = Gesture.NONE;
        public float confidence = 0f;
        public float aimX = 0f;      // -1..1 (0 = screen center)
        public float aimY = 0f;
        public float pinch = 0f;     // 0..1
        public float charge = 0f;    // 0..1 (fist hold)
        public boolean twoHands = false;
        public HandState dominant;
        public HandState second;
        public boolean gestureDetected = false;  // one-frame edge (for UI feedback)
        public float trackingQuality = 0f;       // 0..1
        public boolean lowLight = false;
    }

    private final Result result = new Result();
    private final Settings settings;

    private Gesture candidate = Gesture.NONE;
    private int candidateFrames = 0;
    private Gesture stable = Gesture.NONE;
    private long chargeStart = -1;
    private float charge = 0f;
    private int noHandFrames = 0;
    private int lowQualityFrames = 0;

    public GestureSystem(Settings settings) {
        this.settings = settings;
    }

    /** Feed fresh tracker output each frame. */
    public Result update(HandTracker tracker, long nowMs) {
        HandState dom = tracker.dominantHand();
        HandState sec = tracker.secondHand();
        result.dominant = dom;
        result.second = sec;
        result.gestureDetected = false;

        boolean anyHand = dom != null && dom.tracked;
        boolean twoHands = anyHand && sec != null && sec.tracked;

        if (!anyHand) {
            noHandFrames++;
            lowQualityFrames = 0;
        } else {
            noHandFrames = 0;
            if (dom.quality < 0.35f) lowQualityFrames++; else lowQualityFrames = 0;
        }

        // raw classification
        Gesture raw = Gesture.NONE;
        float conf = 0f;
        if (anyHand) {
            if (twoHands) {
                raw = Gesture.TWO_HANDS;
                conf = 0.9f;
            } else if (dom.pinch > 0.55f) {
                raw = Gesture.PINCH;
                conf = dom.pinch;
            } else if (dom.fist > 0.6f && dom.open < 0.35f) {
                raw = Gesture.FIST;
                conf = dom.fist;
            } else if (dom.open > 0.55f && dom.fingers >= 4) {
                raw = Gesture.OPEN_PALM;
                conf = dom.open;
            } else if (dom.fingers >= 1) {
                raw = Gesture.POINT;
                conf = clamp(dom.quality, 0.4f, 1f);
            }
        }

        // hysteresis
        if (raw == candidate) {
            candidateFrames++;
        } else {
            candidate = raw;
            candidateFrames = 1;
        }
        int needed = (raw == Gesture.NONE) ? 4 : 3;
        if (candidateFrames >= needed && candidate != stable) {
            if (candidate != Gesture.NONE) {
                result.gestureDetected = true;
            }
            stable = candidate;
        } else if (candidate == Gesture.NONE && candidateFrames >= needed) {
            stable = Gesture.NONE;
        }

        result.gesture = stable;
        result.confidence = conf;
        result.twoHands = twoHands;

        // aim from index fingertip (fallback: palm)
        float ax, ay;
        if (anyHand && (stable == Gesture.POINT || stable == Gesture.PINCH || dom.fingers >= 1)) {
            ax = dom.indexX; ay = dom.indexY;
        } else {
            ax = dom != null ? dom.palmX : 0.5f;
            ay = dom != null ? dom.palmY : 0.5f;
        }
        result.aimX = (ax - 0.5f) * 2f * settings.trackingSensitivity;
        result.aimY = (ay - 0.5f) * 2f * settings.trackingSensitivity;
        result.pinch = dom != null ? dom.pinch : 0f;

        // charge while fist held
        if (stable == Gesture.FIST) {
            if (chargeStart < 0) chargeStart = nowMs;
            float t = (nowMs - chargeStart) / 1100f;
            charge = clamp(t, 0f, 1f);
        } else {
            if (chargeStart >= 0) {
                // fired: reset
                chargeStart = -1;
                charge = 0f;
            }
        }
        result.charge = charge;

        result.trackingQuality = anyHand ? dom.quality : 0f;
        result.lowLight = !anyHand && noHandFrames > 90 || lowQualityFrames > 90;
        return result;
    }

    /** Inject synthetic input when the camera is unavailable (touch fallback). */
    public Result synthesize(Gesture gesture, float aimX, float aimY, float pinch, boolean twoHands, long nowMs) {
        result.gesture = gesture;
        result.confidence = 1f;
        result.aimX = aimX;
        result.aimY = aimY;
        result.pinch = pinch;
        result.twoHands = twoHands;
        result.gestureDetected = false;
        result.trackingQuality = 0f;
        if (gesture == Gesture.FIST) {
            if (chargeStart < 0) chargeStart = nowMs;
            charge = clamp((nowMs - chargeStart) / 1100f, 0f, 1f);
        } else {
            chargeStart = -1;
            charge = 0f;
        }
        result.charge = charge;
        return result;
    }

    public void onGestureConsumed() {
        result.gestureDetected = false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

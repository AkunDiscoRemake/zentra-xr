package com.neonforge.hand;

/**
 * Per-hand tracking result, in normalized camera space (0..1, origin top-left,
 * y grows downward — matching the preview image).
 */
public final class HandState {
    public boolean tracked = false;
    public float palmX = 0.5f, palmY = 0.7f;
    public float indexX = 0.5f, indexY = 0.5f;
    public float thumbX = 0.5f, thumbY = 0.7f;
    public float wristX = 0.5f, wristY = 0.95f;
    public float radius = 0.12f;      // hand size (normalized)
    public int fingers = 0;           // estimated extended fingers 0..5
    public float pinch = 0f;          // 0..1
    public float fist = 0f;           // 0..1
    public float open = 0f;           // 0..1
    public float quality = 0f;        // 0..1 confidence for this hand

    public void copyFrom(HandState o) {
        tracked = o.tracked;
        palmX = o.palmX; palmY = o.palmY;
        indexX = o.indexX; indexY = o.indexY;
        thumbX = o.thumbX; thumbY = o.thumbY;
        wristX = o.wristX; wristY = o.wristY;
        radius = o.radius;
        fingers = o.fingers;
        pinch = o.pinch; fist = o.fist; open = o.open;
        quality = o.quality;
    }
}

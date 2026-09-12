package com.neonforge.hand;

/** Recognized player gestures. */
public enum Gesture {
    NONE,
    POINT,      // one finger extended -> aim
    PINCH,      // thumb + index close -> select / grab
    OPEN_PALM,  // all fingers extended -> shield
    FIST,       // closed hand -> charge energy
    TWO_HANDS   // both hands detected -> power interaction
}

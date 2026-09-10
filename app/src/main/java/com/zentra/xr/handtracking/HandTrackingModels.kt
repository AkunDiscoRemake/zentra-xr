package com.zentra.xr.handtracking

import android.graphics.PointF

/**
 * ZENTRA XR - Hand Tracking Data Models
 * Optimized for performance, minimal allocations
 */

enum class HandSide { LEFT, RIGHT, UNKNOWN }

data class JoyConPose(
    val side: HandSide,
    val center: PointF, // Normalized 0..1 screen coords
    val rotationDegrees: Float, // Wrist -> index angle
    val scale: Float, // Based on hand size / depth approx
    val confidence: Float,
    val isPinching: Boolean,
    val pinchStrength: Float, // 0..1
    val indexTip: PointF,
    val thumbTip: PointF,
    val wrist: PointF
)

data class HandLandmarks(
    val side: HandSide,
    val landmarks: List<PointF>, // 21 points normalized
    val worldLandmarks: List<PointF>?, // optional
    val confidence: Float
)

data class TrackingFrame(
    val hands: List<JoyConPose>,
    val timestampMs: Long,
    val fps: Float
)

// Smoothing utilities for low jitter, low latency

class OneEuroFilter(
    private var freq: Float = 30f,
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.007f,
    private var dCutoff: Float = 1.0f
) {
    private var xPrev: Float = 0f
    private var dxPrev: Float = 0f
    private var tPrev: Float = -1f
    private var initialized = false

    fun filter(value: Float, timestampSec: Float): Float {
        if (!initialized) {
            xPrev = value
            tPrev = timestampSec
            initialized = true
            return value
        }
        val dt = timestampSec - tPrev
        if (dt <= 0f) return xPrev

        val dValue = (value - xPrev) / dt
        val edValue = exponentialSmoothing(dValue, dxPrev, alpha(dt, dCutoff))
        val cutoff = minCutoff + beta * kotlin.math.abs(edValue)
        val result = exponentialSmoothing(value, xPrev, alpha(dt, cutoff))

        xPrev = result
        dxPrev = edValue
        tPrev = timestampSec
        return result
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * kotlin.math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun exponentialSmoothing(current: Float, prev: Float, alpha: Float): Float {
        return alpha * current + (1f - alpha) * prev
    }

    fun reset() {
        initialized = false
    }
}

class PointFilter2D {
    private val xFilter = OneEuroFilter(minCutoff = 1.2f, beta = 0.02f)
    private val yFilter = OneEuroFilter(minCutoff = 1.2f, beta = 0.02f)

    fun filter(point: PointF, timestampSec: Float): PointF {
        return PointF(
            xFilter.filter(point.x, timestampSec),
            yFilter.filter(point.y, timestampSec)
        )
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
    }
}

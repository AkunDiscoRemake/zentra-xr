package com.zentra.xr.input

import android.graphics.PointF
import android.util.Log
import com.zentra.xr.handtracking.HandSide
import com.zentra.xr.handtracking.JoyConPose
import com.zentra.xr.handtracking.TrackingFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ZENTRA XR - Input Module
 * Converts hand tracking into Pointer + Pinch (Click)
 * - Smoothing, jitter reduction, low latency
 * - Hover detection for UI
 */

data class PointerState(
    val id: String, // "left" or "right"
    val side: HandSide,
    val position: PointF, // Normalized screen 0..1, but with smoothing
    val rawPosition: PointF, // Raw index tip
    val isPinching: Boolean,
    val pinchStrength: Float,
    val confidence: Float,
    val isHovering: Boolean = false,
    val hoverTargetId: String? = null
)

data class ClickEvent(
    val pointer: PointerState,
    val targetId: String?,
    val timestampMs: Long
)

class InputManager {

    private val _pointers = MutableStateFlow<Map<String, PointerState>>(emptyMap())
    val pointers: StateFlow<Map<String, PointerState>> = _pointers

    private val _clickEvents = MutableStateFlow<ClickEvent?>(null)
    val clickEvents: StateFlow<ClickEvent?> = _clickEvents

    // Pinch state machine to avoid jitter clicks
    private val pinchState = mutableMapOf<String, Boolean>() // was pinching?
    private val pinchStartTime = mutableMapOf<String, Long>()
    private val pinchThresholdEnter = 0.05f
    private val pinchThresholdExit = 0.07f // Hysteresis
    private val minPinchDurationMs = 80L // Debounce
    private val maxPinchDurationMs = 2000L // Long pinch not considered click if too long? But we allow

    // Smoothing for pointer - extra layer over hand tracking smoothing
    private val pointerSmoothFactor = 0.35f // Lower = smoother but more latency. 0.35 good balance

    private var lastPositions = mutableMapOf<String, PointF>()

    // UI hit testing callback
    var hitTestCallback: ((PointF) -> String?)? = null

    fun updateFromTracking(frame: TrackingFrame) {
        val newPointers = mutableMapOf<String, PointerState>()
        val now = System.currentTimeMillis()

        frame.hands.forEach { joyCon ->
            val id = when (joyCon.side) {
                HandSide.LEFT -> "left"
                HandSide.RIGHT -> "right"
                else -> "unknown_${joyCon.center}"
            }

            // Apply additional smoothing
            val lastPos = lastPositions[id]
            val smoothedPos = if (lastPos != null) {
                PointF(
                    lastPos.x + (joyCon.indexTip.x - lastPos.x) * pointerSmoothFactor,
                    lastPos.y + (joyCon.indexTip.y - lastPos.y) * pointerSmoothFactor
                )
            } else {
                joyCon.indexTip
            }
            lastPositions[id] = smoothedPos

            // Pinch state machine with hysteresis
            val wasPinching = pinchState[id] ?: false
            val isPinchingNow = if (wasPinching) {
                // Need larger distance to exit pinch
                val dist = hypot(joyCon.thumbTip, joyCon.indexTip)
                dist < pinchThresholdExit
            } else {
                joyCon.isPinching || hypot(joyCon.thumbTip, joyCon.indexTip) < pinchThresholdEnter
            }

            // Detect pinch start/end for click
            if (!wasPinching && isPinchingNow) {
                pinchStartTime[id] = now
            } else if (wasPinching && !isPinchingNow) {
                val start = pinchStartTime[id] ?: now
                val duration = now - start
                if (duration in minPinchDurationMs..maxPinchDurationMs) {
                    // Valid click!
                    val hoverTarget = hitTestCallback?.invoke(smoothedPos)
                    val pointerForEvent = PointerState(
                        id = id,
                        side = joyCon.side,
                        position = smoothedPos,
                        rawPosition = joyCon.indexTip,
                        isPinching = false,
                        pinchStrength = 0f,
                        confidence = joyCon.confidence,
                        isHovering = hoverTarget != null,
                        hoverTargetId = hoverTarget
                    )
                    val event = ClickEvent(
                        pointer = pointerForEvent,
                        targetId = hoverTarget,
                        timestampMs = now
                    )
                    _clickEvents.value = event
                    Log.d(TAG, "Click detected $id target=$hoverTarget duration=$duration")
                }
                pinchStartTime.remove(id)
            }

            pinchState[id] = isPinchingNow

            val hoverTarget = hitTestCallback?.invoke(smoothedPos)

            val pointer = PointerState(
                id = id,
                side = joyCon.side,
                position = smoothedPos,
                rawPosition = joyCon.indexTip,
                isPinching = isPinchingNow,
                pinchStrength = joyCon.pinchStrength,
                confidence = joyCon.confidence,
                isHovering = hoverTarget != null,
                hoverTargetId = hoverTarget
            )
            newPointers[id] = pointer
        }

        _pointers.value = newPointers
    }

    fun clearClickEvent() {
        _clickEvents.value = null
    }

    fun reset() {
        _pointers.value = emptyMap()
        pinchState.clear()
        pinchStartTime.clear()
        lastPositions.clear()
    }

    private fun hypot(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val TAG = "ZentraInput"
    }
}

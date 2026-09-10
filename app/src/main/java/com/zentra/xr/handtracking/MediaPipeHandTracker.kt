package com.zentra.xr.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ZENTRA XR - Hand Tracking Module
 * Uses MediaPipe Hand Landmarker
 * - Runs locally, no server
 * - Async, low latency
 * - Joy-Con representation instead of human hands
 * - Optimized: downscaled input, reusable bitmap, throttling
 * - Compatible with MediaPipe 0.10.x API (landmarks + handedness)
 */
class MediaPipeHandTracker(
    private val context: Context
) {
    private var handLandmarker: HandLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val _trackingState = MutableStateFlow<TrackingFrame?>(null)
    val trackingState: StateFlow<TrackingFrame?> = _trackingState

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized

    // Smoothing filters per hand (max 2 hands)
    private val leftFilter = PointFilter2D()
    private val rightFilter = PointFilter2D()
    private val leftIndexFilter = PointFilter2D()
    private val rightIndexFilter = PointFilter2D()

    // FPS tracking
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fps = 0f

    // Reusable bitmap to avoid allocations
    private var reusableBitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    // Throttling - max 30 FPS tracking to save battery/heat
    private var lastProcessTimeMs = 0L
    private val minFrameIntervalMs = 33L // ~30fps

    fun initialize(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            _isInitialized.value = true
            Log.i(TAG, "MediaPipe HandLandmarker initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker, trying fallback without asset", e)
            try {
                val baseOptions = BaseOptions.builder().build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .build()
                handLandmarker = HandLandmarker.createFromOptions(context, options)
                _isInitialized.value = true
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback init also failed", e2)
                false
            }
        }
    }

    fun processImageProxy(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTimeMs < minFrameIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTimeMs = now

        executor.execute {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    processBitmap(bitmap, now)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val width = imageProxy.width
        val height = imageProxy.height

        if (reusableBitmap == null || bitmapWidth != width || bitmapHeight != height) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapWidth = width
            bitmapHeight = height
        }

        val bitmap = reusableBitmap ?: return null

        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
            val jpegBytes = out.toByteArray()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            val rotation = imageProxy.imageInfo.rotationDegrees
            val rotated = if (rotation != 0 && bmp != null) {
                val mat = Matrix()
                mat.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true).also {
                    if (it != bmp) bmp.recycle()
                }
            } else bmp

            if (rotated == null) return null

            if (rotated.width == bitmap.width && rotated.height == bitmap.height) {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawBitmap(rotated, 0f, 0f, null)
                if (rotated != bitmap) rotated.recycle()
                return bitmap
            } else {
                reusableBitmap = rotated
                bitmapWidth = rotated.width
                bitmapHeight = rotated.height
                return rotated
            }

        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            return null
        }
    }

    private fun processBitmap(bitmap: Bitmap, timestampMs: Long) {
        val landmarker = handLandmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            val hands = mutableListOf<JoyConPose>()
            val timestampSec = timestampMs / 1000f

            // FPS calculation
            frameCount++
            if (timestampMs - lastFrameTime > 1000) {
                fps = frameCount * 1000f / (timestampMs - lastFrameTime).coerceAtLeast(1)
                frameCount = 0
                lastFrameTime = timestampMs
            }

            // MediaPipe 0.10.x API: result.landmarks() and result.handedness()
            // Each is List<List<...>> where outer list = hands
            val allLandmarks = try {
                result.landmarks()
            } catch (e: Exception) {
                Log.w(TAG, "landmarks() failed, trying detections() fallback", e)
                // Fallback for older API that uses detections()
                try {
                    val detections = result.javaClass.getMethod("detections").invoke(result) as? List<*>
                    detections?.mapNotNull { det ->
                        try {
                            val m = det?.javaClass?.getMethod("landmarks")?.invoke(det) as? List<*>
                            m as? List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
                        } catch (_: Exception) { null }
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val allHandedness = try {
                result.handedness()
            } catch (e: Exception) {
                Log.w(TAG, "handedness() failed, trying handednesses() fallback", e)
                try {
                    result.javaClass.getMethod("handednesses").invoke(result) as? List<List<com.google.mediapipe.tasks.components.containers.Category>>
                } catch (_: Exception) {
                    emptyList()
                }
            }

            allLandmarks.forEachIndexed { idx, landmarks ->
                if (landmarks.size < 21) return@forEachIndexed

                val handednessList = allHandedness.getOrNull(idx)
                val handednessName = handednessList?.firstOrNull()?.let { it.categoryName() } ?: "Unknown"
                val side = when {
                    handednessName.contains("Left", true) -> HandSide.LEFT
                    handednessName.contains("Right", true) -> HandSide.RIGHT
                    else -> if (idx == 0) HandSide.LEFT else HandSide.RIGHT
                }

                // Extract key points (normalized 0..1)
                val wrist = PointF(landmarks[0].x(), landmarks[0].y())
                val thumbTip = PointF(landmarks[4].x(), landmarks[4].y())
                val indexTip = PointF(landmarks[8].x(), landmarks[8].y())
                val middleMcp = PointF(landmarks[9].x(), landmarks[9].y())

                val rawCenter = PointF(
                    (wrist.x + middleMcp.x) / 2f,
                    (wrist.y + middleMcp.y) / 2f
                )

                val smoothedCenter = when (side) {
                    HandSide.LEFT -> leftFilter.filter(rawCenter, timestampSec)
                    HandSide.RIGHT -> rightFilter.filter(rawCenter, timestampSec)
                    else -> rawCenter
                }
                val smoothedIndex = when (side) {
                    HandSide.LEFT -> leftIndexFilter.filter(indexTip, timestampSec)
                    HandSide.RIGHT -> rightIndexFilter.filter(indexTip, timestampSec)
                    else -> indexTip
                }

                val rot = atan2(
                    (middleMcp.y - wrist.y).toDouble(),
                    (middleMcp.x - wrist.x).toDouble()
                ).toFloat() * 57.2958f

                val pinchDist = hypot(
                    (thumbTip.x - indexTip.x).toDouble(),
                    (thumbTip.y - indexTip.y).toDouble()
                ).toFloat()

                val isPinching = pinchDist < 0.05f
                val pinchStrength = (1f - (pinchDist / 0.1f).coerceIn(0f, 1f))

                val middleTip = PointF(landmarks[12].x(), landmarks[12].y())
                val handSize = hypot(
                    (middleTip.x - wrist.x).toDouble(),
                    (middleTip.y - wrist.y).toDouble()
                ).toFloat()
                val scale = (handSize * 3f).coerceIn(0.6f, 1.4f)

                val confidence = handednessList?.firstOrNull()?.let { it.score() } ?: 0.8f

                val joyCon = JoyConPose(
                    side = side,
                    center = smoothedCenter,
                    rotationDegrees = rot,
                    scale = scale,
                    confidence = confidence,
                    isPinching = isPinching,
                    pinchStrength = pinchStrength,
                    indexTip = smoothedIndex,
                    thumbTip = thumbTip,
                    wrist = wrist
                )
                hands.add(joyCon)
            }

            val frame = TrackingFrame(
                hands = hands,
                timestampMs = timestampMs,
                fps = fps
            )
            _trackingState.value = frame

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
        }
    }

    fun shutdown() {
        try {
            handLandmarker?.close()
        } catch (_: Exception) {}
        executor.shutdown()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }

    companion object {
        private const val TAG = "ZentraHandTracking"
    }
}

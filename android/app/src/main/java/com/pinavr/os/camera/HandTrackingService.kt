package com.pinavr.os.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * PINA VR - Hand Tracking Service - Camera2 API + MediaPipe
 * Usa Camera2Manager puro (não CameraX) para controle total
 * - Câmera frontal 640x480 60fps
 * - YUV_420_888 -> RGB -> MediaPipe
 * - OneEuro + Kalman no JS, mas pré-filtragem aqui
 */

class HandTrackingService(private val context: Context) {

    private val camera2Manager = Camera2Manager(context)
    private var handLandmarker: HandLandmarker? = null
    private var isActive = false

    var onHandsDetected: ((handsJson: String) -> Unit)? = null
    var onFrameProcessed: ((bitmap: Bitmap) -> Unit)? = null

    fun start() {
        if (isActive) return

        try {
            // Inicializa MediaPipe
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ ->
                    val hands = result.landmarks()
                    if (hands.isNotEmpty()) {
                        val json = buildString {
                            append("[")
                            hands.forEachIndexed { idx, landmarks ->
                                if (idx > 0) append(",")
                                append("{\"index\":$idx,\"handedness\":\"${result.handednesses().getOrNull(idx)?.getOrNull(0)?.categoryName() ?: "Unknown"}\",\"keypoints\":[")
                                landmarks.forEachIndexed { lIdx, lm ->
                                    if (lIdx > 0) append(",")
                                    append("{\"x\":${lm.x()},\"y\":${lm.y()},\"z\":${lm.z()},\"visibility\":1}")
                                }
                                append("]}")
                            }
                            append("]")
                        }
                        onHandsDetected?.invoke(json)
                        Log.d("PinaHands", "Detected ${hands.size} hands")
                    } else {
                        onHandsDetected?.invoke("[]")
                    }
                }
                .setErrorListener { e -> Log.e("PinaHands", "MediaPipe error", e) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i("PinaHands", "MediaPipe HandLandmarker criado - Camera2 API")

        } catch (e: Exception) {
            Log.e("PinaHands", "Falha MediaPipe, usando fallback", e)
            // Fallback: sem MediaPipe nativo, JS fará via WebRTC
        }

        // Inicia câmera frontal via Camera2 API pura
        camera2Manager.startFrontCamera { image ->
            try {
                processImage(image)
            } catch (e: Exception) {
                Log.e("PinaHands", "processImage error", e)
            } finally {
                image.close()
            }
        }

        isActive = true
        Log.i("PinaHands", "HandTrackingService Camera2 iniciado")
    }

    private fun processImage(image: Image) {
        // YUV_420_888 -> Bitmap
        val bitmap = yuvToBitmap(image) ?: return

        // Rotaciona se necessário (front camera espelhada)
        // val matrix = android.graphics.Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        // val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        onFrameProcessed?.invoke(bitmap)

        // Envia para MediaPipe
        try {
            handLandmarker?.let { landmarker ->
                val mpImage = BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e("PinaHands", "detectAsync error", e)
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap? {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            // Simplificado: assume NV21 com VU intercalado
            // Para produção, usar libyuv ou RenderScript
            var pos = ySize
            val uvPixelStride = image.planes[1].pixelStride
            val uvRowStride = image.planes[1].rowStride

            // Intercala V e U
            for (row in 0 until image.height/2) {
                for (col in 0 until image.width/2) {
                    val vuPos = row * uvRowStride + col * uvPixelStride
                    if (vuPos < vSize && vuPos < uSize) {
                        nv21[pos++] = vBuffer.get(vuPos)
                        if (pos < nv21.size) nv21[pos++] = uBuffer.get(vuPos)
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0,0,image.width,image.height), 80, out)
            val bytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("PinaHands", "yuvToBitmap error", e)
            return null
        }
    }

    fun stop() {
        isActive = false
        camera2Manager.stopFront()
        handLandmarker?.close()
        handLandmarker = null
        Log.i("PinaHands", "HandTrackingService parado")
    }

    fun getCameraInfo(): String = camera2Manager.getCameraInfo()
}

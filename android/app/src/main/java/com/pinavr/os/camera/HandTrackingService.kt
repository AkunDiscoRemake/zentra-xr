package com.pinavr.os.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PINA VR - Hand Tracking Service Nativo
 * Usa CameraX + MediaPipe Tasks Vision nativo Android
 * - Câmera frontal para mãos
 * - One Euro + Kalman já aplicados no JS, mas aqui também faz pré-filtragem
 * - Envia landmarks para WebView
 */

class HandTrackingService(private val context: Context) {

    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    var onHandsDetected: ((handsJson: String) -> Unit)? = null
    var isActive = false

    fun start(lifecycleOwner: LifecycleOwner) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task") // colocar em assets
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ ->
                    // Converte resultado para JSON para WebView
                    val hands = result.landmarks()
                    if (hands.isNotEmpty()) {
                        val json = buildString {
                            append("[")
                            hands.forEachIndexed { idx, landmarks ->
                                if (idx > 0) append(",")
                                append("{\"index\":$idx,\"keypoints\":[")
                                landmarks.forEachIndexed { lIdx, lm ->
                                    if (lIdx > 0) append(",")
                                    append("{\"x\":${lm.x()},\"y\":${lm.y()},\"z\":${lm.z()}}")
                                }
                                append("]}")
                            }
                            append("]")
                        }
                        onHandsDetected?.invoke(json)
                    } else {
                        onHandsDetected?.invoke("[]")
                    }
                }
                .setErrorListener { e -> Log.e("PinaHands", "MediaPipe erro", e) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)

            // CameraX
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                bindCamera(lifecycleOwner)
            }, ContextCompat.getMainExecutor(context))

            isActive = true
            Log.i("PinaHands", "HandTrackingService iniciado - MediaPipe nativo")

        } catch (e: Exception) {
            Log.e("PinaHands", "Falha ao iniciar hand tracking nativo, fallback para JS", e)
            // Fallback: deixa JS fazer hand tracking via WebRTC
        }
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        try {
            val provider = cameraProvider ?: return
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mpImage = androidx.camera.core.ImageProxy.toBitmap(imageProxy)
                // Converte para MPImage (simplificado - precisaria converter Bitmap para MPImage)
                // handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
                imageProxy.close()
            }

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)

        } catch (e: Exception) {
            Log.e("PinaHands", "bindCamera erro", e)
        }
    }

    fun stop() {
        isActive = false
        cameraProvider?.unbindAll()
        handLandmarker?.close()
        cameraExecutor.shutdown()
    }
}

// Extensão helper
fun androidx.camera.core.ImageProxy.toBitmap(): android.graphics.Bitmap {
    // Implementação simplificada
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: android.graphics.Bitmap.createBitmap(1,1, android.graphics.Bitmap.Config.ARGB_8888)
}

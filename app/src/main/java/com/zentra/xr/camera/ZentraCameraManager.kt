package com.zentra.xr.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ZENTRA XR - Camera Module
 * - Lowest latency possible
 * - Preview for MR background
 * - ImageAnalysis for hand tracking (downscaled, async)
 * - Buffer reuse, no duplicate processing
 */
class ZentraCameraManager(
    private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

    // Dedicated executors for performance
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var isRunning = false
        private set

    interface FrameListener {
        fun onFrameForTracking(imageProxy: androidx.camera.core.ImageProxy)
    }

    private var frameListener: FrameListener? = null

    fun setFrameListener(listener: FrameListener?) {
        frameListener = listener
    }

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        enableAnalysis: Boolean = true
    ): Boolean = suspendCoroutine { cont ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // Preview - prioritize low latency, 1280x720 max for performance
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    ).build()

                previewUseCase = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val useCases = mutableListOf<androidx.camera.core.UseCase>(previewUseCase!!)

                if (enableAnalysis) {
                    // Analysis - aggressively downscaled for hand tracking performance
                    // 320x240 is enough for MediaPipe, reduces CPU/GPU load dramatically
                    analysisUseCase = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(320, 240),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                                    )
                                ).build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also { analysis ->
                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                // Reuse path - listener must close imageProxy
                                frameListener?.onFrameForTracking(imageProxy) ?: imageProxy.close()
                            }
                        }
                    useCases.add(analysisUseCase!!)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray()
                )
                isRunning = true
                Log.i(TAG, "Camera bound: preview=${previewUseCase != null} analysis=${analysisUseCase != null}")
                cont.resume(true)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                cont.resume(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        isRunning = false
    }

    fun shutdown() {
        unbind()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ZentraCamera"
    }
}

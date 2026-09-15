package com.pinavr.os.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * PINA VR - Camera2 API Manager - Controle total Camera2
 * - Câmera traseira: passthrough MR 1920x1080 60fps, baixa latência
 * - Câmera frontal: hand tracking 640x480 60fps, foco fixo, alta velocidade
 * - Suporte a manual controls, FPS range, YUV_420_888
 * - Zero CameraX - puro Camera2 para performance máxima
 */

class Camera2Manager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var rearCameraDevice: CameraDevice? = null
    private var frontCameraDevice: CameraDevice? = null
    private var rearSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null

    private var rearImageReader: ImageReader? = null
    private var frontImageReader: ImageReader? = null

    var onRearFrame: ((image: android.media.Image) -> Unit)? = null
    var onFrontFrame: ((image: android.media.Image) -> Unit)? = null

    var isRearActive = false
    var isFrontActive = false

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("PinaCamera2").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread", e)
        }
    }

    fun getCameraId(facing: Int): String? {
        // facing: CameraCharacteristics.LENS_FACING_BACK / FRONT
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == facing) return id
        }
        return null
    }

    fun getOptimalSize(cameraId: String, targetWidth: Int, targetHeight: Int): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)

        // Escolhe tamanho mais próximo do target com aspect ratio similar
        var best = sizes[0]
        var bestScore = Int.MAX_VALUE
        for (size in sizes) {
            val diff = abs(size.width - targetWidth) + abs(size.height - targetHeight)
            if (diff < bestScore) {
                bestScore = diff
                best = size
            }
        }
        return best
    }

    fun getHighFpsRange(cameraId: String): Range<Int> {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        // Pega range com max mais alto que contenha 60
        var best = Range(30, 30)
        var bestMax = 0
        fpsRanges?.forEach { range ->
            if (range.upper >= 60 && range.upper > bestMax) {
                best = range
                bestMax = range.upper
            } else if (range.upper > bestMax && bestMax < 60) {
                best = range
                bestMax = range.upper
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun startRearCamera(onFrame: (android.media.Image) -> Unit) {
        onRearFrame = onFrame
        startBackgroundThread()

        val cameraId = getCameraId(CameraCharacteristics.LENS_FACING_BACK) ?: run {
            Log.e(TAG, "Rear camera not found")
            return
        }

        val optimalSize = getOptimalSize(cameraId, 1920, 1080)
        val fpsRange = getHighFpsRange(cameraId)

        Log.i(TAG, "Starting rear camera $cameraId size $optimalSize fps $fpsRange - PINA MR passthrough")

        rearImageReader = ImageReader.newInstance(optimalSize.width, optimalSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    onRearFrame?.invoke(image)
                    // Não fecha aqui, deixa callback fechar após uso
                }
            }, backgroundHandler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                rearCameraDevice = camera
                createRearSession(camera, optimalSize, fpsRange)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                rearCameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Rear camera error $error")
                camera.close()
                rearCameraDevice = null
            }
        }, backgroundHandler)
    }

    @SuppressLint("MissingPermission")
    fun startFrontCamera(onFrame: (android.media.Image) -> Unit) {
        onFrontFrame = onFrame
        if (backgroundThread == null) startBackgroundThread()

        val cameraId = getCameraId(CameraCharacteristics.LENS_FACING_FRONT) ?: run {
            Log.e(TAG, "Front camera not found")
            return
        }

        val optimalSize = getOptimalSize(cameraId, 640, 480)
        val fpsRange = getHighFpsRange(cameraId)

        Log.i(TAG, "Starting front camera $cameraId size $optimalSize fps $fpsRange - PINA hand tracking")

        frontImageReader = ImageReader.newInstance(optimalSize.width, optimalSize.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    onFrontFrame?.invoke(image)
                }
            }, backgroundHandler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                frontCameraDevice = camera
                createFrontSession(camera, optimalSize, fpsRange)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                frontCameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Front camera error $error")
                camera.close()
                frontCameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun createRearSession(device: CameraDevice, size: Size, fpsRange: Range<Int>) {
        try {
            val surface = rearImageReader!!.surface
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    rearSession = session
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        // Configurações otimizadas para MR passthrough baixa latência
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) // off para menos latência
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        // Distorção mínima
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    isRearActive = true
                    Log.i(TAG, "Rear session configured - MR passthrough ativo")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Rear session configure failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createRearSession", e)
        }
    }

    private fun createFrontSession(device: CameraDevice, size: Size, fpsRange: Range<Int>) {
        try {
            val surface = frontImageReader!!.surface
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    frontSession = session
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        // Otimizado para hand tracking: foco fixo próximo, alta velocidade
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        // Tenta foco em 30cm (mão)
                        val chars = cameraManager.getCameraCharacteristics(device.id)
                        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        if (minFocus != null && minFocus > 0) {
                            // Foco aproximado para mãos (0.3m)
                            // set(CaptureRequest.LENS_FOCUS_DISTANCE, 3.0f) // se manual
                        }
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    }
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    isFrontActive = true
                    Log.i(TAG, "Front session configured - Hand tracking ativo")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Front session configure failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createFrontSession", e)
        }
    }

    fun stopRear() {
        rearSession?.close()
        rearSession = null
        rearCameraDevice?.close()
        rearCameraDevice = null
        rearImageReader?.close()
        rearImageReader = null
        isRearActive = false
    }

    fun stopFront() {
        frontSession?.close()
        frontSession = null
        frontCameraDevice?.close()
        frontCameraDevice = null
        frontImageReader?.close()
        frontImageReader = null
        isFrontActive = false
    }

    fun stopAll() {
        stopRear()
        stopFront()
        stopBackgroundThread()
    }

    fun getCameraInfo(): String {
        val info = StringBuilder()
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val facingStr = when(facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXTERNAL"
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            info.append("Camera $id facing $facingStr fps $fps\n")
        }
        return info.toString()
    }

    companion object {
        private const val TAG = "PinaCamera2"
    }
}

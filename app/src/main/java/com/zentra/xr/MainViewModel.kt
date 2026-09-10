package com.zentra.xr

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zentra.xr.browser.ZentraBrowserManager
import com.zentra.xr.camera.ZentraCameraManager
import com.zentra.xr.cardboard.CardboardManager
import com.zentra.xr.handtracking.MediaPipeHandTracker
import com.zentra.xr.input.InputManager
import com.zentra.xr.util.CompatibilityReport
import com.zentra.xr.util.DeviceCompatibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ZENTRA XR - Main ViewModel
 * Orchestrates all modules:
 * Camera, Cardboard, HandTracking, Input, Browser
 * - Optimized for low latency, stable FPS, low battery
 */

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Modules
    val cameraManager = ZentraCameraManager(context)
    val cardboardManager = CardboardManager(context)
    val handTracker = MediaPipeHandTracker(context)
    val inputManager = InputManager()
    val browserManager = ZentraBrowserManager()

    // State
    private val _compatibility = MutableStateFlow<CompatibilityReport?>(null)
    val compatibility: StateFlow<CompatibilityReport?> = _compatibility

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady

    private val _isHandTrackingReady = MutableStateFlow(false)
    val isHandTrackingReady: StateFlow<Boolean> = _isHandTrackingReady

    private val _isCardboardReady = MutableStateFlow(false)
    val isCardboardReady: StateFlow<Boolean> = _isCardboardReady

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        viewModelScope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        try {
            // 1. Compatibility check
            val report = DeviceCompatibility.check(context)
            _compatibility.value = report

            if (!report.isCompatible) {
                _errorMessage.value = report.errors.firstOrNull() ?: "Dispositivo incompatível"
                _isLoading.value = false
                return
            }

            // 2. Cardboard init (real SDK)
            val cardboardOk = cardboardManager.initialize()
            _isCardboardReady.value = cardboardOk
            if (!cardboardOk) {
                Log.w(TAG, "Cardboard init failed, continuing in mono mode")
            }

            // 3. Hand tracking init (MediaPipe)
            val handOk = handTracker.initialize()
            _isHandTrackingReady.value = handOk
            if (!handOk) {
                Log.w(TAG, "Hand tracking init failed, will retry")
                _errorMessage.value = "Hand tracking não inicializou. Verifique se hand_landmarker.task está em assets."
            }

            // Camera binding is done in Activity with PreviewView
            _isLoading.value = false
            Log.i(TAG, "ZENTRA XR initialized - cardboard=$cardboardOk hand=$handOk compat=$report")

        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            _errorMessage.value = "Falha ao inicializar: ${e.message}"
            _isLoading.value = false
        }
    }

    fun bindCamera(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        viewModelScope.launch {
            // Set frame listener for hand tracking
            cameraManager.setFrameListener(object : ZentraCameraManager.FrameListener {
                override fun onFrameForTracking(imageProxy: androidx.camera.core.ImageProxy) {
                    handTracker.processImageProxy(imageProxy)
                }
            })

            val ok = cameraManager.bind(lifecycleOwner, previewView, enableAnalysis = true)
            _isCameraReady.value = ok
            if (!ok) {
                _errorMessage.value = "Não foi possível iniciar a câmera"
            } else {
                cardboardManager.startTracking()
            }
        }
    }

    fun onPause() {
        cardboardManager.stopTracking()
    }

    fun onResume() {
        cardboardManager.startTracking()
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.shutdown()
        cardboardManager.shutdown()
        handTracker.shutdown()
        inputManager.reset()
        Log.i(TAG, "ViewModel cleared, modules shutdown")
    }

    companion object {
        private const val TAG = "ZentraViewModel"
    }
}

package com.pinavr.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pinavr.os.camera.Camera2Manager
import com.pinavr.os.camera.HandTrackingService
import com.pinavr.os.camera.PassthroughCameraService
import com.pinavr.os.sensors.SensorFusion

/**
 * PINA VR - MainActivity - Cardboard Mixed Reality OS com Camera2 API
 * - WebView imersivo com sensores nativos 200Hz
 * - Camera2 API pura: traseira passthrough MR + frontal hand tracking 60fps
 * - Mixed Reality como default
 * - Dev API injetada via JavascriptInterface
 * - Tudo 3D spatial, zero 2D nativo
 */

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sensorFusion: SensorFusion
    private lateinit var camera2Manager: Camera2Manager
    private var handTrackingService: HandTrackingService? = null
    private var passthroughService: PassthroughCameraService? = null

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val PERM_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen imersivo - essencial para Cardboard
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        setupWebView()
        checkPermissions()

        // Sensores - melhor giroscópio possível 200Hz nativo
        sensorFusion = SensorFusion(this)
        sensorFusion.onSensorsUpdate = { quat, gyro, accel, mag ->
            runOnUiThread {
                val json = sensorFusion.getJson()
                webView.evaluateJavascript(
                    "if(window.PinaNativeBridge) window.PinaNativeBridge.onSensorData('$json');" +
                    "if(window.PinaVR && window.PinaVR._native) window.PinaVR._native.onSensors([${quat[0]},${quat[1]},${quat[2]},${quat[3]}],[${gyro[0]},${gyro[1]},${gyro[2]}],[${accel[0]},${accel[1]},${accel[2]}],[${mag[0]},${mag[1]},${mag[2]}]);",
                    null
                )
            }
        }

        // Camera2 API Manager - controle total
        camera2Manager = Camera2Manager(this)

        // Hand tracking frontal - Camera2 API 640x480 60fps
        handTrackingService = HandTrackingService(this)
        handTrackingService?.onHandsDetected = { handsJson ->
            runOnUiThread {
                // Escapa JSON para JS
                val escaped = handsJson.replace("'", "\\'")
                webView.evaluateJavascript("if(window.PinaNativeBridge) window.PinaNativeBridge.onHandData('$escaped');", null)
            }
        }

        // Passthrough traseira - Camera2 API 1920x1080 60fps para MR
        passthroughService = PassthroughCameraService(this)
        passthroughService?.onFrameYuv = { yuv, w, h ->
            // Para futuro: enviar frame MR para processamento de profundidade
            // Por enquanto, o JS usa getUserMedia para passthrough, mas temos Camera2 nativo pronto
        }

        logCamera2Info()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("PinaVR Web", "${consoleMessage.message()} -- ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }

        webView.addJavascriptInterface(NativeBridgeInterface(), "PinaNative")

        try {
            webView.loadUrl("file:///android_asset/pina/index.html")
        } catch (e: Exception) {
            loadEmbeddedPina()
        }
    }

    private fun loadEmbeddedPina() {
        val html = """
            <html><body style="background:#000;color:#0f8;padding:20px;font-family:monospace">
            <h1>PINA VR OS - Camera2 API</h1>
            <p>Coloque web/ em android/app/src/main/assets/pina/</p>
            <p>Camera2 API: traseira 1080p60 MR + frontal 480p60 Hand Tracking</p>
            <script>
            if (window.location.hostname.includes('e2b.app')) {
                window.location.href = '/index.html';
            }
            </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun checkPermissions() {
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_CODE)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE) onPermissionsGranted()
    }

    private fun onPermissionsGranted() {
        sensorFusion.start()
        // Inicia Camera2 API
        try {
            handTrackingService?.start()
            passthroughService?.start()
            Toast.makeText(this, "PINA VR - Camera2 API ativa - MR + Hand Tracking 60fps", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("PinaVR", "Camera2 start error", e)
            Toast.makeText(this, "Camera2 erro: ${e.message}", Toast.LENGTH_LONG).show()
        }

        webView.postDelayed({
            try {
                webView.loadUrl("file:///android_asset/pina/index.html")
            } catch (e: Exception) {
                Toast.makeText(this, "PINA VR: coloque web/ em assets/pina/", Toast.LENGTH_LONG).show()
            }
        }, 500)
    }

    private fun logCamera2Info() {
        try {
            val info = camera2Manager.getCameraInfo()
            android.util.Log.i("PinaCamera2", "Cameras disponíveis:\n$info")
        } catch (e: Exception) {
            android.util.Log.e("PinaCamera2", "getCameraInfo error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        sensorFusion.start()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        sensorFusion.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorFusion.stop()
        handTrackingService?.stop()
        passthroughService?.stop()
        webView.destroy()
    }

    inner class NativeBridgeInterface {
        @JavascriptInterface
        fun getSensors(): String = sensorFusion.getJson()

        @JavascriptInterface
        fun getCamera2Info(): String {
            return try {
                camera2Manager.getCameraInfo()
            } catch (e: Exception) {
                "{\"error\":\"${e.message}\"}"
            }
        }

        @JavascriptInterface
        fun vibrate(patternJson: String) {
            try {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (patternJson.startsWith("[")) {
                    val cleaned = patternJson.replace("[","").replace("]","").split(",").map { it.trim().toLong() }.toLongArray()
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(cleaned, -1))
                } else {
                    val dur = patternJson.toLongOrNull() ?: 50L
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(dur, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun setMRMode(mode: String) {
            android.util.Log.i("PinaNative", "MR Mode $mode - Camera2 API")
            if (mode == "mixed") {
                passthroughService?.start()
            }
        }

        @JavascriptInterface
        fun log(msg: String) {
            android.util.Log.d("PinaVR Native", msg)
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return """{"model":"${android.os.Build.MODEL}","sdk":${android.os.Build.VERSION.SDK_INT},"isCardboard":true,"camera2":true,"rear":"1920x1080@60","front":"640x480@60"}"""
        }

        @JavascriptInterface
        fun startCamera2(facing: String): String {
            return try {
                if (facing == "front") {
                    handTrackingService?.start()
                    "{\"status\":\"front started\"}"
                } else {
                    passthroughService?.start()
                    "{\"status\":\"rear started\"}"
                }
            } catch (e: Exception) {
                "{\"error\":\"${e.message}\"}"
            }
        }

        @JavascriptInterface
        fun stopCamera2(facing: String) {
            if (facing == "front") handTrackingService?.stop() else passthroughService?.stop()
        }
    }
}

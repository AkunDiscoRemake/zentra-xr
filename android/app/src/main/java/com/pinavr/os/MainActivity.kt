package com.pinavr.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pinavr.os.sensors.SensorFusion

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var sensorFusion: SensorFusion
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val PERM_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()
        checkPermissions()
        sensorFusion = SensorFusion(this)
        sensorFusion.onSensorsUpdate = { quat, gyro, accel, mag ->
            runOnUiThread {
                val json = sensorFusion.getJson()
                webView.evaluateJavascript("if(window.PinaNativeBridge) window.PinaNativeBridge.onSensorData('$json');", null)
            }
        }
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
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(NativeBridgeInterface(), "PinaNative")
        try { webView.loadUrl("file:///android_asset/pina/index.html") } catch (e: Exception) {}
    }

    private fun checkPermissions() {
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_CODE) else onPermissionsGranted()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE) onPermissionsGranted()
    }

    private fun onPermissionsGranted() {
        sensorFusion.start()
        webView.postDelayed({ try { webView.loadUrl("file:///android_asset/pina/index.html") } catch (e: Exception) {} }, 500)
    }

    override fun onResume() { super.onResume(); webView.onResume(); sensorFusion.start() }
    override fun onPause() { super.onPause(); webView.onPause(); sensorFusion.stop() }
    override fun onDestroy() { super.onDestroy(); sensorFusion.stop(); webView.destroy() }

    inner class NativeBridgeInterface {
        @JavascriptInterface fun getSensors(): String = sensorFusion.getJson()
        @JavascriptInterface fun getCamera2Info(): String = "{\"camera2\":true}"
        @JavascriptInterface fun vibrate(p: String) {}
        @JavascriptInterface fun log(m: String) { android.util.Log.d("PinaVR", m) }
        @JavascriptInterface fun getDeviceInfo(): String = "{\"model\":\"${android.os.Build.MODEL}\"}"
    }
}

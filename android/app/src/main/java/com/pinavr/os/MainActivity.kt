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
import com.pinavr.os.camera.HandTrackingService
import com.pinavr.os.sensors.SensorFusion

/**
 * PINA VR - MainActivity - Cardboard Mixed Reality OS
 * - WebView imersivo com sensores nativos 200Hz
 * - Mixed Reality como default (câmera traseira via WebRTC no JS)
 * - Hand tracking nativo + fallback JS
 * - Dev API injetada via JavascriptInterface
 * - Tudo 3D spatial, zero 2D nativo (só WebView)
 */

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sensorFusion: SensorFusion
    private var handTrackingService: HandTrackingService? = null

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

        // Layout simples: só WebView
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        setupWebView()
        checkPermissions()

        sensorFusion = SensorFusion(this)
        sensorFusion.onSensorsUpdate = { quat, gyro, accel, mag ->
            // Envia para JS via evaluateJavascript (alta frequência)
            runOnUiThread {
                if (webView != null) {
                    val json = sensorFusion.getJson()
                    webView.evaluateJavascript("if(window.PinaNativeBridge) window.PinaNativeBridge.onSensorData('$json'); if(window.PinaVR && window.PinaVR._native) window.PinaVR._native.onSensors([${quat[0]},${quat[1]},${quat[2]},${quat[3]}],[${gyro[0]},${gyro[1]},${gyro[2]}],[${accel[0]},${accel[1]},${accel[2]}],[${mag[0]},${mag[1]},${mag[2]}]);", null)
                }
            }
        }

        // Hand tracking nativo (opcional, JS faz fallback)
        handTrackingService = HandTrackingService(this)
        handTrackingService?.onHandsDetected = { handsJson ->
            runOnUiThread {
                webView.evaluateJavascript("if(window.PinaNativeBridge) window.PinaNativeBridge.onHandData('$handsJson');", null)
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
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Auto concede câmera para MR passthrough + hand tracking
                request.grant(request.resources)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("PinaVR Web", "${consoleMessage.message()} -- ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // deixa navegar dentro do WebView (para browser espacial)
            }
        }

        // Native Bridge - Dev API para Android
        webView.addJavascriptInterface(NativeBridgeInterface(), "PinaNative")

        // Carrega PINA VR OS - tenta local primeiro, fallback para servidor dev
        // Em produção, coloque os arquivos web em android/app/src/main/assets/pina/
        // e use file:///android_asset/pina/index.html
        try {
            webView.loadUrl("file:///android_asset/pina/index.html")
        } catch (e: Exception) {
            // Fallback para teste local: carrega do localhost se estiver rodando python server
            // Para testar no Arena: use preview da web
            webView.loadUrl("https://pinavr.local") // será substituído
            // Tenta carregar index.html embutido como string se assets não existir
            loadEmbeddedPina()
        }
    }

    private fun loadEmbeddedPina() {
        // Se não tiver assets, carrega uma página que redireciona para o servidor de dev
        // ou mostra instruções
        val html = """
            <html><body style="background:#000;color:#0f8;padding:20px;font-family:monospace">
            <h1>PINA VR OS</h1>
            <p>Coloque os arquivos web em android/app/src/main/assets/pina/</p>
            <p>Ou rode: <code>python3 -m http.server 8000</code> em web/ e abra o IP no WebView</p>
            <p>Para testar agora, o app vai tentar carregar do preview.</p>
            <script>
            // Tenta detectar se está no Arena preview
            const host = window.location.hostname;
            if (host.includes('e2b.app')) {
                // Está no preview, recarrega para index real
                window.location.href = '/index.html';
            } else {
                // Fallback: cria um iframe para o servidor local
                document.body.innerHTML += '<p>Conecte o celular na mesma rede e acesse o IP do dev server</p>';
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
        if (requestCode == PERM_CODE) {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        // Inicia sensores nativos
        sensorFusion.start()
        // handTrackingService?.start(this) // opcional, comentado para usar JS MediaPipe que é mais estável no WebView

        // Carrega PINA VR se ainda não carregou
        if (webView.url == null || webView.url!!.contains("pinavr.local")) {
            // Tenta carregar do assets novamente
            webView.postDelayed({
                try {
                    webView.loadUrl("file:///android_asset/pina/index.html")
                } catch (e: Exception) {
                    Toast.makeText(this, "PINA VR: coloque web/ em assets/pina/", Toast.LENGTH_LONG).show()
                }
            }, 500)
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
        webView.destroy()
    }

    // Interface exposta para JavaScript - Dev API Nativa Android
    inner class NativeBridgeInterface {

        @JavascriptInterface
        fun getSensors(): String {
            return sensorFusion.getJson()
        }

        @JavascriptInterface
        fun vibrate(patternJson: String) {
            try {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                // patternJson: "[100,50,100]" ou número simples
                if (patternJson.startsWith("[")) {
                    // array
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
            // mode: "mixed" | "vr"
            android.util.Log.i("PinaNative", "MR Mode set to $mode")
        }

        @JavascriptInterface
        fun requestPermission(perm: String) {
            // Já tratado
        }

        @JavascriptInterface
        fun log(msg: String) {
            android.util.Log.d("PinaVR Native", msg)
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return """{"model":"${android.os.Build.MODEL}","sdk":${android.os.Build.VERSION.SDK_INT},"isCardboard":true}"""
        }
    }
}

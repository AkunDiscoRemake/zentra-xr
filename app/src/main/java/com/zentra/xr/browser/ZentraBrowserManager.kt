package com.zentra.xr.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ZENTRA XR - Browser Module
 * Minimal spatial browser
 * - Address bar, back, forward, reload, home
 * - Content inside virtual window
 * - Interaction via hand tracking (pinch = click)
 */
class ZentraBrowserManager {

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attachWebView(wv: WebView) {
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                _isLoading.value = true
                url?.let { _currentUrl.value = it }
                updateNavState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                _isLoading.value = false
                url?.let { _currentUrl.value = it }
                updateNavState()
            }
        }
        // Load home
        if (wv.url == null) {
            loadUrl(_currentUrl.value)
        }
    }

    fun detach() {
        webView = null
    }

    fun loadUrl(url: String) {
        var finalUrl = url.trim()
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            // If contains dot, treat as URL, else search
            if (finalUrl.contains(".") && !finalUrl.contains(" ")) {
                finalUrl = "https://$finalUrl"
            } else {
                finalUrl = "https://www.google.com/search?q=${java.net.URLEncoder.encode(finalUrl, "UTF-8")}"
            }
        }
        _currentUrl.value = finalUrl
        webView?.loadUrl(finalUrl)
    }

    fun goBack() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        }
    }

    fun goForward() {
        if (webView?.canGoForward() == true) {
            webView?.goForward()
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun goHome() {
        loadUrl("https://www.google.com")
    }

    private fun updateNavState() {
        _canGoBack.value = webView?.canGoBack() ?: false
        _canGoForward.value = webView?.canGoForward() ?: false
    }

    /**
     * For hand tracking: convert pointer position to MotionEvent for WebView
     * This allows pinch to click inside web content
     */
    fun simulateClickAt(normalizedX: Float, normalizedY: Float) {
        val wv = webView ?: return
        // Convert normalized 0..1 to view coords
        val x = normalizedX * wv.width
        val y = normalizedY * wv.height

        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = android.view.MotionEvent.obtain(downTime, downTime + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)

        wv.dispatchTouchEvent(downEvent)
        wv.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }
}

package com.zentra.xr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zentra.xr.browser.ZentraBrowserManager
import com.zentra.xr.camera.ZentraCameraManager
import com.zentra.xr.cardboard.CardboardManager
import com.zentra.xr.handtracking.MediaPipeHandTracker
import com.zentra.xr.input.InputManager
import com.zentra.xr.input.PointerState
import com.zentra.xr.ui.components.JoyConVirtual
import com.zentra.xr.ui.components.PointerDot
import com.zentra.xr.ui.screens.BrowserScreen
import com.zentra.xr.ui.screens.HomeScreen
import com.zentra.xr.ui.screens.SettingsScreen
import com.zentra.xr.util.CompatibilityReport
import kotlinx.coroutines.flow.collectLatest

/**
 * ZENTRA XR - MR Container
 * Composes:
 * - Camera preview as background (MR)
 * - Hand tracking overlay (Joy-Con virtual + Pointers)
 * - Spatial UI (Home / Browser / Settings)
 * - Cardboard stereo support
 */

enum class ZentraScreen { HOME, BROWSER, SETTINGS }

@Composable
fun MRContainer(
    cameraManager: ZentraCameraManager,
    handTracker: MediaPipeHandTracker,
    cardboardManager: CardboardManager,
    inputManager: InputManager,
    browserManager: ZentraBrowserManager,
    compatibility: CompatibilityReport?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentScreen by remember { mutableStateOf(ZentraScreen.HOME) }
    var trackingFrame by remember { mutableStateOf<com.zentra.xr.handtracking.TrackingFrame?>(null) }
    var pointers by remember { mutableStateOf<Map<String, PointerState>>(emptyMap()) }
    var isStereo by remember { mutableStateOf(false) }

    // Collect hand tracking
    LaunchedEffect(handTracker) {
        handTracker.trackingState.collectLatest { frame ->
            trackingFrame = frame
            if (frame != null) {
                inputManager.updateFromTracking(frame)
            }
        }
    }

    // Collect pointers
    LaunchedEffect(inputManager) {
        inputManager.pointers.collectLatest { map ->
            pointers = map
        }
    }

    // Collect clicks for UI interaction
    LaunchedEffect(inputManager) {
        inputManager.clickEvents.collectLatest { event ->
            if (event != null) {
                // Simple hit-test is done via callback, but we also handle global navigation
                // For now, UI buttons use regular clickable that will be triggered via pointer simulation
                // This is where we could forward click to WebView if in browser
                if (currentScreen == ZentraScreen.BROWSER) {
                    // If pointer over WebView, simulate click
                    // Normalized coords already
                    browserManager.simulateClickAt(
                        event.pointer.position.x,
                        event.pointer.position.y
                    )
                }
                inputManager.clearClickEvent()
            }
        }
    }

    LaunchedEffect(cardboardManager) {
        cardboardManager.stereoEnabled.collectLatest {
            isStereo = it
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. CAMERA BACKGROUND is handled by MainActivity's PreviewView underneath
        // This container only draws overlay UI to avoid duplicate camera binding

        // 2. JOY-CON VIRTUAL MODELS - positioned by hand tracking
        trackingFrame?.hands?.forEach { pose ->
            // Convert normalized 0..1 to screen position
            // We use BoxWithConstraints to position
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val x = (pose.center.x * maxWidth.value).dp
                val y = (pose.center.y * maxHeight.value).dp

                JoyConVirtual(
                    pose = pose,
                    modifier = Modifier
                        .offset(x = x - 28.dp, y = y - 40.dp) // center offset
                )
            }
        }

        // 3. POINTERS - index fingertip
        pointers.values.forEach { pointer ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val x = (pointer.position.x * maxWidth.value).dp
                val y = (pointer.position.y * maxHeight.value).dp

                PointerDot(
                    pointer = pointer,
                    modifier = Modifier.offset(x = x - 8.dp, y = y - 8.dp)
                )
            }
        }

        // 4. SPATIAL UI - Home / Browser / Settings
        // For stereo mode, we would render UI twice side-by-side. Beta 1: single centered.
        when (currentScreen) {
            ZentraScreen.HOME -> {
                HomeScreen(
                    onOpenBrowser = { currentScreen = ZentraScreen.BROWSER },
                    onOpenSettings = { currentScreen = ZentraScreen.SETTINGS },
                    fps = trackingFrame?.fps ?: 0f,
                    handCount = trackingFrame?.hands?.size ?: 0,
                    modifier = Modifier.fillMaxSize()
                )
            }
            ZentraScreen.BROWSER -> {
                BrowserScreen(
                    browserManager = browserManager,
                    onClose = { currentScreen = ZentraScreen.HOME },
                    modifier = Modifier.fillMaxSize()
                )
            }
            ZentraScreen.SETTINGS -> {
                SettingsScreen(
                    compatibility = compatibility,
                    isStereoEnabled = isStereo,
                    onToggleStereo = { enabled ->
                        cardboardManager.setStereoEnabled(enabled)
                    },
                    onConfigureCardboard = {
                        val intent = cardboardManager.openViewerProfileScanner()
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    },
                    onClose = { currentScreen = ZentraScreen.HOME },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 5. Stereo overlay guides (for VR Box)
        if (isStereo) {
            // Vertical divider line for stereo debugging
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .align(androidx.compose.ui.Alignment.Center)
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f))
                }
            }
        }
    }
}

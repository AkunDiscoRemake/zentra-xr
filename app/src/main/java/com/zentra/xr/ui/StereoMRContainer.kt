package com.zentra.xr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * ZENTRA XR Beta 2 - Stereo MR Container
 * REAL VR Box support:
 * - Side-by-side rendering (left/right eye)
 * - Camera background duplicated with IPD offset
 * - UI duplicated for each eye
 * - Joy-Con and pointers per eye
 * - Works in actual VR Box/Cardboard
 */

@Composable
fun StereoMRContainer(
    cameraManager: ZentraCameraManager,
    handTracker: MediaPipeHandTracker,
    cardboardManager: CardboardManager,
    inputManager: InputManager,
    browserManager: ZentraBrowserManager,
    compatibility: CompatibilityReport?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf(ZentraScreen.HOME) }
    var trackingFrame by remember { mutableStateOf<com.zentra.xr.handtracking.TrackingFrame?>(null) }
    var pointers by remember { mutableStateOf<Map<String, PointerState>>(emptyMap()) }
    var isStereo by remember { mutableStateOf(true) } // Beta 2 defaults to stereo for VR Box

    LaunchedEffect(handTracker) {
        handTracker.trackingState.collectLatest { frame ->
            trackingFrame = frame
            if (frame != null) {
                inputManager.updateFromTracking(frame)
            }
        }
    }

    LaunchedEffect(inputManager) {
        inputManager.pointers.collectLatest { map ->
            pointers = map
        }
    }

    LaunchedEffect(inputManager) {
        inputManager.clickEvents.collectLatest { event ->
            if (event != null) {
                if (currentScreen == ZentraScreen.BROWSER) {
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

    // Beta 2: Always stereo for VR Box, but allow toggle
    LaunchedEffect(Unit) {
        cardboardManager.setStereoEnabled(true)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (isStereo) {
            // STEREO MODE - Side by side for VR Box
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT EYE
                EyeView(
                    eye = 0,
                    trackingFrame = trackingFrame,
                    pointers = pointers,
                    currentScreen = currentScreen,
                    browserManager = browserManager,
                    compatibility = compatibility,
                    isStereoEnabled = isStereo,
                    cardboardManager = cardboardManager,
                    onScreenChange = { currentScreen = it },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )

                // Center divider (subtle, for debugging, invisible in VR)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.05f))
                )

                // RIGHT EYE
                EyeView(
                    eye = 1,
                    trackingFrame = trackingFrame,
                    pointers = pointers,
                    currentScreen = currentScreen,
                    browserManager = browserManager,
                    compatibility = compatibility,
                    isStereoEnabled = isStereo,
                    cardboardManager = cardboardManager,
                    onScreenChange = { currentScreen = it },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            // MONO fallback (for testing without VR Box)
            EyeView(
                eye = 0,
                trackingFrame = trackingFrame,
                pointers = pointers,
                currentScreen = currentScreen,
                browserManager = browserManager,
                compatibility = compatibility,
                isStereoEnabled = false,
                cardboardManager = cardboardManager,
                onScreenChange = { currentScreen = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun EyeView(
    eye: Int,
    trackingFrame: com.zentra.xr.handtracking.TrackingFrame?,
    pointers: Map<String, PointerState>,
    currentScreen: ZentraScreen,
    browserManager: ZentraBrowserManager,
    compatibility: CompatibilityReport?,
    isStereoEnabled: Boolean,
    cardboardManager: CardboardManager,
    onScreenChange: (ZentraScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ipdOffset = if (eye == 0) -0.02f else 0.02f // Slight offset for stereo parallax

    Box(modifier = modifier.background(Color(0xFF0A0A0F))) {
        // UI Content per eye - duplicated
        when (currentScreen) {
            ZentraScreen.HOME -> {
                HomeScreen(
                    onOpenBrowser = { onScreenChange(ZentraScreen.BROWSER) },
                    onOpenSettings = { onScreenChange(ZentraScreen.SETTINGS) },
                    fps = trackingFrame?.fps ?: 0f,
                    handCount = trackingFrame?.hands?.size ?: 0,
                    modifier = Modifier.fillMaxSize()
                )
            }
            ZentraScreen.BROWSER -> {
                BrowserScreen(
                    browserManager = browserManager,
                    onClose = { onScreenChange(ZentraScreen.HOME) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            ZentraScreen.SETTINGS -> {
                SettingsScreen(
                    compatibility = compatibility,
                    isStereoEnabled = isStereoEnabled,
                    onToggleStereo = { enabled ->
                        cardboardManager.setStereoEnabled(enabled)
                    },
                    onConfigureCardboard = {
                        val intent = cardboardManager.openViewerProfileScanner()
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    },
                    onClose = { onScreenChange(ZentraScreen.HOME) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Joy-Con Virtual Models - with IPD offset for stereo depth
        trackingFrame?.hands?.forEach { pose ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Apply IPD offset to create stereo depth illusion
                val baseX = pose.center.x
                val stereoX = (baseX + ipdOffset * pose.scale).coerceIn(0f, 1f)
                val x = (stereoX * maxWidth.value).dp
                val y = (pose.center.y * maxHeight.value).dp

                JoyConVirtual(
                    pose = pose,
                    modifier = Modifier.offset(x = x - 28.dp, y = y - 40.dp)
                )
            }
        }

        // Pointers - duplicated per eye with offset
        pointers.values.forEach { pointer ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val baseX = pointer.position.x
                val stereoX = (baseX + ipdOffset * 0.5f).coerceIn(0f, 1f)
                val x = (stereoX * maxWidth.value).dp
                val y = (pointer.position.y * maxHeight.value).dp

                PointerDot(
                    pointer = pointer,
                    modifier = Modifier.offset(x = x - 8.dp, y = y - 8.dp)
                )
            }
        }

        // Eye label for debugging (small L/R in corner)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            androidx.compose.material3.Text(
                text = if (eye == 0) "L" else "R",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}
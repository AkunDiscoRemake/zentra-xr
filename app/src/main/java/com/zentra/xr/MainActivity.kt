package com.zentra.xr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zentra.xr.ui.MRContainer
import com.zentra.xr.ui.StereoMRContainer
import com.zentra.xr.ui.screens.PermissionScreen
import com.zentra.xr.ui.theme.ZentraTheme

/**
 * ZENTRA XR Beta 1 - MainActivity
 * Flow:
 * 1. Check permissions
 * 2. Check compatibility
 * 3. Enter MR mode (camera + hand tracking + UI)
 * 4. Home -> Browser / Settings
 *
 * Privacy: no analytics, camera stays local
 */

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var hasCameraPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive for MR
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        // Initial permission check
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            ZentraTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                val isLoading by viewModel.isLoading.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val compatibility by viewModel.compatibility.collectAsState()
                val isCameraReady by viewModel.isCameraReady.collectAsState()

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
                    when {
                        isLoading -> {
                            LoadingScreen()
                        }
                        !hasCameraPermission -> {
                            PermissionScreen(
                                onRequestPermission = {
                                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            )
                        }
                        errorMessage != null && compatibility?.isCompatible == false -> {
                            ErrorScreen(message = errorMessage!!, compatibility = compatibility)
                        }
                        else -> {
                            // MR Container with camera background
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Camera PreviewView - background (MR base)
                                AndroidView(
                                    factory = { ctx ->
                                        PreviewView(ctx).apply {
                                            scaleType = PreviewView.ScaleType.FILL_CENTER
                                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                            // Bind camera when view is ready
                                            viewModel.bindCamera(this, lifecycleOwner)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                // MR UI overlay - Beta 2: Stereo for VR Box
                                StereoMRContainer(
                                    cameraManager = viewModel.cameraManager,
                                    handTracker = viewModel.handTracker,
                                    cardboardManager = viewModel.cardboardManager,
                                    inputManager = viewModel.inputManager,
                                    browserManager = viewModel.browserManager,
                                    compatibility = compatibility,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Camera not ready indicator
                                if (!isCameraReady) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 24.dp)
                                            .background(Color(0xCC1A1A24), shape = MaterialTheme.shapes.medium)
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text("Iniciando câmera MR...", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        // Re-enter immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
        // Re-check permission
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color(0xFF7C5CFF))
            Text("Inicializando ZENTRA XR...", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Câmera • Cardboard • Hand Tracking", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, compatibility: com.zentra.xr.util.CompatibilityReport?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚠️", style = MaterialTheme.typography.displayMedium)
            Text("ZENTRA XR não pôde iniciar", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(message, color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodyMedium)
            if (compatibility != null) {
                Spacer(Modifier.height(8.dp))
                compatibility.warnings.forEach {
                    Text("• $it", color = Color(0xFFFFC95C).copy(0.8f), style = MaterialTheme.typography.bodySmall)
                }
                compatibility.errors.forEach {
                    Text("• $it", color = Color(0xFFFF5C7C).copy(0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

package com.zentra.xr.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zentra.xr.browser.ZentraBrowserManager
import com.zentra.xr.ui.components.SpatialPanel
import com.zentra.xr.ui.components.TopBar

/**
 * ZENTRA XR - Browser Screen
 * Spatial browser panel inside MR
 * Interaction: indicator -> point, pinch -> click
 */

@Composable
fun BrowserScreen(
    browserManager: ZentraBrowserManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var addressText by remember { mutableStateOf(browserManager.currentUrl.value) }
    val currentUrl by browserManager.currentUrl.collectAsState()
    val canBack by browserManager.canGoBack.collectAsState()
    val canForward by browserManager.canGoForward.collectAsState()
    val isLoading by browserManager.isLoading.collectAsState()

    // Update text when url changes externally
    LaunchedEffect(currentUrl) {
        addressText = currentUrl
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SpatialPanel(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TopBar(
                    title = "ZENTRA Browser",
                    subtitle = "SPATIAL WEB • MR"
                )

                // Address bar + controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF23232F))
                        .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Nav buttons
                    IconButton(
                        onClick = { browserManager.goBack() },
                        enabled = canBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.ArrowBack, "Voltar", tint = if (canBack) Color.White else Color.White.copy(0.3f))
                    }
                    IconButton(
                        onClick = { browserManager.goForward() },
                        enabled = canForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.ArrowForward, "Avançar", tint = if (canForward) Color.White else Color.White.copy(0.3f))
                    }
                    IconButton(
                        onClick = { browserManager.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, "Recarregar", tint = Color.White)
                    }

                    // Address field
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A24))
                            .border(1.dp, Color(0xFF7C5CFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (addressText.isEmpty()) {
                                    Text("Digite URL ou pesquisa...", color = Color.White.copy(0.4f), fontSize = 13.sp)
                                }
                                inner()
                            }
                        )
                    }

                    // Go button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF7C5CFF))
                            .clickable { browserManager.loadUrl(addressText) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Search, "Ir", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    // Home & Close
                    IconButton(onClick = { browserManager.goHome() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Home, "Home", tint = Color.White.copy(0.8f))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "Fechar", tint = Color.White)
                    }
                }

                // Loading indicator
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFF7C5CFF),
                        trackColor = Color.Transparent
                    )
                }

                // WebView content - spatial window
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(18.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                browserManager.attachWebView(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            browserManager.detach()
                        }
                    )
                }
            }
        }
    }
}

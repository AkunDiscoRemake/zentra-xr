package com.zentra.xr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zentra.xr.ui.components.LargeAppButton
import com.zentra.xr.ui.components.SpatialPanel
import com.zentra.xr.ui.components.TopBar

/**
 * ZENTRA XR - Home Screen
 * Beta 1: Only Browser + Settings
 * Spatial floating panel design inspired by XR OS reference
 */

@Composable
fun HomeScreen(
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    fps: Float = 0f,
    handCount: Int = 0
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Central floating panel - main XR OS style
        SpatialPanel(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                // Top bar with ZENTRA branding
                TopBar(
                    title = "ZENTRA XR",
                    subtitle = "MIXED REALITY • BETA 1"
                )

                // Divider with glow
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Spacer(Modifier.height(24.dp))

                // Welcome text
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Bem-vindo ao futuro",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Seu espaço\nMixed Reality",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp
                    )
                }

                Spacer(Modifier.height(28.dp))

                // App grid - only 2 apps in Beta 1
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LargeAppButton(
                        icon = Icons.Filled.Language,
                        title = "ZENTRA Browser",
                        subtitle = "Navegador espacial MR",
                        accentColor = Color(0xFF7C5CFF),
                        onClick = onOpenBrowser,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LargeAppButton(
                        icon = Icons.Filled.Settings,
                        title = "Settings",
                        subtitle = "Cardboard • Câmera • Tracking",
                        accentColor = Color(0xFF3ABFFF),
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Status dock - hand tracking + fps
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (handCount > 0) Color(0xFF00FF88) else Color(0xFFFF5C5C))
                        )
                        Text(
                            text = if (handCount > 0) "$handCount mão(s) detectada(s)" else "Aguardando mãos...",
                            color = Color.White.copy(0.7f),
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "${fps.toInt()} FPS • MR Ativo",
                        color = Color.White.copy(0.4f),
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Instructions
                Row(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "👆 Aponte com indicador • 🤏 Pinch para clicar",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

package com.zentra.xr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zentra.xr.ui.components.SpatialPanel
import com.zentra.xr.ui.components.TopBar
import com.zentra.xr.util.CompatibilityReport

@Composable
fun SettingsScreen(
    compatibility: CompatibilityReport?,
    isStereoEnabled: Boolean,
    onToggleStereo: (Boolean) -> Unit,
    onConfigureCardboard: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SpatialPanel(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(title = "Settings", subtitle = "ZENTRA XR • BETA 1", actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "Fechar", tint = Color.White)
                    }
                })

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Cardboard Section
                    SettingsSection(title = "Cardboard / VR Box", icon = Icons.Filled.ViewInAr) {
                        SettingsItem(
                            icon = Icons.Filled.Videocam,
                            title = "Modo Estereoscópico",
                            subtitle = if (isStereoEnabled) "Ativo - Duplicado L/R para VR Box" else "Mono - Preview único",
                            hasSwitch = true,
                            switchChecked = isStereoEnabled,
                            onSwitchChange = onToggleStereo
                        )
                        SettingsItem(
                            icon = Icons.Filled.QrCodeScanner,
                            title = "Configurar Headset",
                            subtitle = "Escanear QR do seu VR Box (Cardboard oficial)",
                            onClick = onConfigureCardboard
                        )
                        SettingsInfoBox(
                            text = "O ZENTRA XR usa a API REAL do Google Cardboard. Compatível com qualquer VR Box/Cardboard. Use a configuração para calibrar lentes e IPD."
                        )
                    }

                    // Camera & Tracking
                    SettingsSection(title = "Mixed Reality", icon = Icons.Filled.CameraAlt) {
                        SettingsItem(
                            icon = Icons.Filled.Handyman,
                            title = "Hand Tracking - MediaPipe",
                            subtitle = "Processamento local • 30 FPS max • Baixa latência"
                        )
                        SettingsItem(
                            icon = Icons.Filled.PrivacyTip,
                            title = "Privacidade",
                            subtitle = "Câmera nunca enviada para servidores. Tudo local."
                        )
                    }

                    // Device Compatibility
                    SettingsSection(title = "Compatibilidade", icon = Icons.Filled.Memory) {
                        if (compatibility != null) {
                            CompatibilityRow(label = "Câmera", ok = compatibility.hasCamera)
                            CompatibilityRow(label = "Acelerômetro", ok = compatibility.hasAccelerometer)
                            CompatibilityRow(label = "Giroscópio", ok = compatibility.hasGyroscope)
                            CompatibilityRow(label = "Magnetômetro", ok = compatibility.hasMagnetometer)
                            CompatibilityRow(label = "Android 8.0+", ok = compatibility.androidVersionOk)
                            CompatibilityRow(label = "RAM suficiente", ok = compatibility.ramOk)

                            if (compatibility.warnings.isNotEmpty()) {
                                SettingsInfoBox(
                                    text = "⚠️ Avisos:\n" + compatibility.warnings.joinToString("\n• ", prefix = "• "),
                                    isWarning = true
                                )
                            }
                            if (compatibility.errors.isNotEmpty()) {
                                SettingsInfoBox(
                                    text = "❌ Erros:\n" + compatibility.errors.joinToString("\n• ", prefix = "• "),
                                    isError = true
                                )
                            }
                        } else {
                            Text("Verificando dispositivo...", color = Color.White.copy(0.6f), fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Footer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(0.04f))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ZENTRA XR Beta 1", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Build 1.0.0-beta1 • MR • Cardboard • Hand Tracking", color = Color.White.copy(0.4f), fontSize = 11.sp)
                            Text("Feito para VR Box - Não coleta dados", color = Color.White.copy(0.3f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF7C5CFF), modifier = Modifier.size(18.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF23232F))
                .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(16.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    hasSwitch: Boolean = false,
    switchChecked: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Color.White.copy(0.5f), fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
        if (hasSwitch) {
            Switch(
                checked = switchChecked,
                onCheckedChange = { onSwitchChange?.invoke(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF7C5CFF),
                    uncheckedThumbColor = Color.White.copy(0.6f),
                    uncheckedTrackColor = Color(0xFF2A2A35)
                )
            )
        } else if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsInfoBox(text: String, isWarning: Boolean = false, isError: Boolean = false) {
    val bg = when {
        isError -> Color(0xFF3A1F26)
        isWarning -> Color(0xFF2F2A1F)
        else -> Color(0xFF1F232F)
    }
    val border = when {
        isError -> Color(0xFFFF5C7C).copy(0.3f)
        isWarning -> Color(0xFFFFC95C).copy(0.3f)
        else -> Color(0xFF7C5CFF).copy(0.2f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(text, color = Color.White.copy(0.7f), fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun CompatibilityRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(0.7f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "OK" else "Falta", color = if (ok) Color(0xFF00FF88) else Color(0xFFFF5C5C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                null,
                tint = if (ok) Color(0xFF00FF88) else Color(0xFFFF5C5C),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

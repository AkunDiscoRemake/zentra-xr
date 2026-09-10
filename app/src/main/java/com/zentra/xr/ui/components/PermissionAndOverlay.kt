package com.zentra.xr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CameraPermissionOverlay(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentAlignment = Alignment.Center
    ) {
        SpatialPanel(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 400.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF7C5CFF).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color(0xFF7C5CFF), modifier = Modifier.size(36.dp))
                }
                Text("Acesso à câmera necessário", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "O ZENTRA XR precisa da câmera para criar a experiência de Mixed Reality. As imagens nunca são enviadas para servidores - tudo acontece localmente no seu dispositivo.",
                    color = Color.White.copy(0.6f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Permitir câmera", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun MRDebugOverlay(
    fps: Float,
    handCount: Int,
    isStereo: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${fps.toInt()} FPS", color = Color.White.copy(0.7f), fontSize = 10.sp)
        Text("•", color = Color.White.copy(0.3f), fontSize = 10.sp)
        Text("$handCount HANDS", color = if (handCount > 0) Color(0xFF00FF88) else Color.White.copy(0.5f), fontSize = 10.sp)
        Text("•", color = Color.White.copy(0.3f), fontSize = 10.sp)
        Text(if (isStereo) "STEREO" else "MONO", color = Color.White.copy(0.5f), fontSize = 10.sp)
    }
}

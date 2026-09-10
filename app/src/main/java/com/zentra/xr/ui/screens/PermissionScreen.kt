package com.zentra.xr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zentra.xr.ui.components.SpatialPanel

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit,
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
                .widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF7C5CFF).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color(0xFF7C5CFF), modifier = Modifier.size(40.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Acesso à câmera necessário", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "ZENTRA XR • MIXED REALITY",
                        color = Color.White.copy(0.4f),
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp
                    )
                }

                Text(
                    "O ZENTRA XR precisa da câmera para criar a experiência de Mixed Reality. Você verá o ambiente real através da câmera, com UI espacial e controladores virtuais.",
                    color = Color.White.copy(0.7f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1F232F))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Security, null, tint = Color(0xFF3ABFFF), modifier = Modifier.size(20.dp))
                    Column {
                        Text("Privacidade", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Imagens nunca enviadas para servidores. Tudo local.", color = Color.White.copy(0.5f), fontSize = 11.sp)
                    }
                }

                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Permitir câmera", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                Text(
                    "Você pode colocar o celular no VR Box/Cardboard após permitir.",
                    color = Color.White.copy(0.35f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

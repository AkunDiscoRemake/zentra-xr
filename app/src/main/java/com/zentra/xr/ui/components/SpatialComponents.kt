package com.zentra.xr.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zentra.xr.ui.theme.ZentraGlass

/**
 * ZENTRA XR - Spatial UI Components
 * Glassmorphism, rounded corners, XR OS aesthetic
 */

@Composable
fun SpatialPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = ZentraGlass.accentGlow,
                spotColor = ZentraGlass.accentGlow
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF252530),
                        Color(0xFF1C1C25)
                    )
                )
            )
            .background(ZentraGlass.panelBackground)
            .border(
                width = 1.dp,
                color = ZentraGlass.panelBorder,
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        content()
    }
}

@Composable
fun GlassButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true
) {
    val background = if (isPrimary) {
        Brush.horizontalGradient(
            listOf(Color(0xFF7C5CFF), Color(0xFF5A3FD4))
        )
    } else {
        Brush.horizontalGradient(
            listOf(Color(0xFF2A2A38), Color(0xFF222230))
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
fun LargeAppButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF7C5CFF)
) {
    var isHovered by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (isHovered) Color(0xFF2F2F40) else Color(0xFF23232F)
            )
            .border(
                width = if (isHovered) 1.5.dp else 1.dp,
                color = if (isHovered) accentColor.copy(alpha = 0.6f) else Color.White.copy(0.06f),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(0.2f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(subtitle, color = Color.White.copy(0.6f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C5CFF))
                    .border(1.dp, Color.White.copy(0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("Z", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.5.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Color.White.copy(0.5f), fontSize = 11.sp, letterSpacing = 1.2.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
fun DockBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(ZentraGlass.dockBackground)
            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
fun HoverIndicator(
    isHovering: Boolean,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isHovering) 1.3f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "hoverScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isHovering) 1f else 0.6f,
        label = "hoverAlpha"
    )
    Box(
        modifier = modifier
            .size((12 * scale).dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = alpha))
            .border(2.dp, Color(0xFF7C5CFF), CircleShape)
    )
}

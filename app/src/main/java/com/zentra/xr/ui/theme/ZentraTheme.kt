package com.zentra.xr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp

// ZENTRA XR Design System - inspired by VisionOS / XR OS
// Dark, glass, rounded, modern headset aesthetic

private val ZentraDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2A7A),
    secondary = Color(0xFF9AA0B0),
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF1C1C25),
    surfaceVariant = Color(0xFF2A2A35),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF9AA0B0),
    outline = Color(0x1AFFFFFF),
    error = Color(0xFFFF5C7C)
)

private val ZentraTypography = Typography(
    displayLarge = Typography().displayLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = Typography().headlineLarge.copy(
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.Medium
    )
)

private val ZentraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun ZentraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ZentraDarkColorScheme,
        typography = ZentraTypography,
        shapes = ZentraShapes,
        content = content
    )
}

// Glassmorphism colors
object ZentraGlass {
    val panelBackground = Color(0xCC1E1E2A) // semi-transparent dark
    val panelBorder = Color(0x1AFFFFFF)
    val dockBackground = Color(0xB312121A)
    val accentGlow = Color(0x667C5CFF)
    val joyConLeft = Color(0xFF3ABFFF)
    val joyConRight = Color(0xFFFF5C7C)
    val pointer = Color(0xFF7C5CFF)
    val pointerHover = Color.White
}

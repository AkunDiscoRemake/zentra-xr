package com.zentra.xr.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.zentra.xr.handtracking.HandSide
import com.zentra.xr.handtracking.JoyConPose
import com.zentra.xr.input.PointerState

/**
 * ZENTRA XR - Joy-Con Virtual Visualization
 * Represents hands as VR controllers, not human hands
 * Inspired by Joy-Con / VR controller aesthetic
 */

@Composable
fun JoyConVirtual(
    pose: JoyConPose,
    modifier: Modifier = Modifier
) {
    val isLeft = pose.side == HandSide.LEFT
    val baseColor = if (isLeft) Color(0xFF3ABFFF) else Color(0xFFFF5C7C)
    val glowColor = baseColor.copy(alpha = 0.3f)

    // Pinch animation
    val pinchScale by animateFloatAsState(
        targetValue = if (pose.isPinching) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 400f),
        label = "pinchScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pinchScale * pose.scale
                scaleY = pinchScale * pose.scale
                rotationZ = pose.rotationDegrees * 0.3f // Subtle rotation, not too much
                alpha = pose.confidence.coerceIn(0.5f, 1f)
            }
    ) {
        // Glow
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        radius = 80f
                    )
                )
        )

        // Joy-Con body
        Canvas(
            modifier = Modifier
                .size(56.dp, 80.dp)
                .clip(RoundedCornerShape(18.dp))
        ) {
            val w = size.width
            val h = size.height

            // Main body - dark with colored accent
            drawRoundRect(
                color = Color(0xFF1E1E28),
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.06f),
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
                cornerRadius = CornerRadius(17.dp.toPx(), 17.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )

            // Color accent strip
            drawRoundRect(
                color = baseColor,
                topLeft = Offset(w * 0.15f, h * 0.08f),
                size = Size(w * 0.7f, h * 0.12f),
                cornerRadius = CornerRadius(6.dp.toPx())
            )

            // Joystick (top)
            drawCircle(
                color = Color(0xFF2A2A35),
                radius = w * 0.22f,
                center = Offset(w * 0.5f, h * 0.32f)
            )
            drawCircle(
                color = baseColor.copy(alpha = 0.9f),
                radius = w * 0.16f,
                center = Offset(w * 0.5f, h * 0.32f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = w * 0.06f,
                center = Offset(w * 0.5f - 2.dp.toPx(), h * 0.32f - 2.dp.toPx())
            )

            // Buttons (middle)
            val btnY = h * 0.55f
            drawCircle(Color(0xFF2A2A35), radius = w * 0.09f, center = Offset(w * 0.32f, btnY))
            drawCircle(Color(0xFF2A2A35), radius = w * 0.09f, center = Offset(w * 0.68f, btnY))
            drawCircle(Color(0xFF2A2A35), radius = w * 0.09f, center = Offset(w * 0.5f, btnY - w * 0.14f))
            drawCircle(Color(0xFF2A2A35), radius = w * 0.09f, center = Offset(w * 0.5f, btnY + w * 0.14f))

            // Trigger indicator (bottom)
            drawRoundRect(
                color = if (pose.isPinching) baseColor else Color(0xFF3A3A4A),
                topLeft = Offset(w * 0.2f, h * 0.82f),
                size = Size(w * 0.6f, h * 0.08f),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
        }

        // Side label L/R
        Box(
            modifier = Modifier
                .offset(x = if (isLeft) (-6).dp else 44.dp, y = (-8).dp)
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(baseColor)
                .border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(6.dp)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = if (isLeft) "L" else "R",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
    }
}

@Composable
fun PointerDot(
    pointer: PointerState,
    modifier: Modifier = Modifier
) {
    val isHovering = pointer.isHovering
    val isPinching = pointer.isPinching

    val scale by animateFloatAsState(
        targetValue = when {
            isPinching -> 0.7f
            isHovering -> 1.5f
            else -> 1f
        },
        animationSpec = tween(120),
        label = "pointerScale"
    )

    val color = when {
        isPinching -> Color.White
        isHovering -> Color(0xFFFFFFFF)
        else -> Color(0xFF7C5CFF)
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (isHovering) 0.6f else 0.25f,
        label = "glowAlpha"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color.copy(alpha = glowAlpha))
        )
        // Core dot
        Box(
            modifier = Modifier
                .size(if (isHovering) 14.dp else 10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
                .border(2.dp, Color.White.copy(alpha = 0.9f), androidx.compose.foundation.shape.CircleShape)
        )
        // Pinch ring
        if (isPinching) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}



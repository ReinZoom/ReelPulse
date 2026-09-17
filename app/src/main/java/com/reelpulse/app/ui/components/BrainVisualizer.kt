package com.reelpulse.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun BrainVisualizer(
    usageCount: Int,
    modifier: Modifier = Modifier
) {
    val usageFactor = (usageCount.toFloat() / 100f).coerceIn(0f, 1f)
    
    // Animation speeds based on usage
    val duration = (2000 - (usageFactor * 1500).toInt()).coerceAtLeast(500)
    
    val infiniteTransition = rememberInfiniteTransition(label = "brain_breathing")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration * 4, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )

    val healthyColor = Color(0xFF4CAF50)
    val warningColor = Color(0xFFFFC107)
    val intenseColor = Color(0xFFFF5252)
    
    val currentPulseColor by animateColorAsState(
        targetValue = when {
            usageCount < 20 -> healthyColor
            usageCount < 50 -> warningColor
            else -> intenseColor
        },
        animationSpec = tween(1500),
        label = "color"
    )

    Canvas(modifier = modifier.size(240.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val brainWidth = size.width * 0.7f * pulseScale
        val brainHeight = size.height * 0.55f * pulseScale

        // Draw Stylized Brain Outline (Two Lobes)
        val brainPath = Path().apply {
            // Left Lobe
            addArc(
                oval = Rect(
                    center.x - brainWidth / 2,
                    center.y - brainHeight / 2,
                    center.x,
                    center.y + brainHeight / 2
                ),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 180f
            )
            // Right Lobe
            addArc(
                oval = Rect(
                    center.x,
                    center.y - brainHeight / 2,
                    center.x + brainWidth / 2,
                    center.y + brainHeight / 2
                ),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 180f
            )
        }

        // 1. Base Brain Glow
        drawPath(
            path = brainPath,
            brush = Brush.radialGradient(
                colors = listOf(currentPulseColor.copy(alpha = 0.3f), Color.Transparent),
                center = center,
                radius = brainWidth
            )
        )

        // 2. Outer Neural Shell
        drawPath(
            path = brainPath,
            color = currentPulseColor.copy(alpha = 0.7f),
            style = Stroke(width = 3.dp.toPx())
        )

        // 3. Dynamic Neural Pathways (Internal)
        val pathCount = 8 + (usageFactor * 12).toInt()
        for (i in 0 until pathCount) {
            val angle = (i * (360f / pathCount) + wavePhase) % 360f
            val rad = Math.toRadians(angle.toDouble())
            
            // Start from center, flow outwards to edge
            val endPos = Offset(
                center.x + (cos(rad) * brainWidth / 2).toFloat(),
                center.y + (sin(rad) * brainHeight / 2).toFloat()
            )

            drawLine(
                color = currentPulseColor.copy(alpha = 0.4f),
                start = center,
                end = endPos,
                strokeWidth = 1.dp.toPx()
            )
            
            // Moving Signal Pulse
            val pulseProgress = (wavePhase / 360f + (i.toFloat() / pathCount)) % 1f
            val signalPos = Offset(
                center.x + (cos(rad) * (brainWidth / 2) * pulseProgress).toFloat(),
                center.y + (sin(rad) * (brainHeight / 2) * pulseProgress).toFloat()
            )
            
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = 2.dp.toPx(),
                center = signalPos
            )
        }

        // 4. "Brain Rot" Fog / Neural Noise (High Usage Only)
        if (usageFactor > 0.6f) {
            val noiseCount = (usageFactor * 30).toInt()
            repeat(noiseCount) {
                val x = center.x + (Math.random() - 0.5) * brainWidth
                val y = center.y + (Math.random() - 0.5) * brainHeight
                
                drawCircle(
                    color = currentPulseColor.copy(alpha = 0.2f),
                    radius = (Math.random() * 10).toFloat().dp.toPx(),
                    center = Offset(x.toFloat(), y.toFloat())
                )
            }
        }
        
        // 5. Central Neural Hub
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = 15.dp.toPx(),
            center = center
        )
    }
}

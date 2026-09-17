package com.reelpulse.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingCounter(
    count: Int,
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .wrapContentSize()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .border(
                width = 0.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.2f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            MiniPulseIcon(count = count)
            
            Spacer(Modifier.width(8.dp))
            
            Text(
                text = count.toString(),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun MiniPulseIcon(count: Int) {
    val usageFactor = (count.toFloat() / 100f).coerceIn(0f, 1f)
    val baseColor = Color(0xFFFF4081)
    
    val color by animateColorAsState(
        targetValue = when {
            count < 20 -> baseColor
            count < 50 -> Color(0xFFFFC107) // Amber
            else -> Color(0xFF4CAF50) // Green
        },
        label = "pulse_color"
    )

    Canvas(modifier = Modifier.size(18.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        
        val path = Path().apply {
            addArc(Rect(0f, 0f, size.width / 2, size.height), 90f, 180f)
            addArc(Rect(size.width / 2, 0f, size.width, size.height), 270f, 180f)
        }

        drawPath(
            path = path,
            color = color,
            alpha = 0.8f
        )
        
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.3f),
            style = Stroke(width = 1.dp.toPx())
        )
        
        if (usageFactor > 0.4f) {
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                radius = 1.5.dp.toPx(),
                center = Offset(center.x - 2.dp.toPx(), center.y + 1.dp.toPx())
            )
        }
    }
}

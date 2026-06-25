package com.example.ble.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private data class NodeGradientSpec(
    val color1: Color,
    val color2: Color,
    val color3: Color,
    val color4: Color,
    val arcStartDeg: Float
)

private fun buildNodeGradientSpec(nodeId: String): NodeGradientSpec {
    val hex = nodeId.filter { it in "0123456789abcdefABCDEF" }
        .padEnd(8, '0').take(8).lowercase()
    fun pair(i: Int) = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0
    val hue1 = pair(0) * (360f / 255f)
    val hue2 = pair(1) * (360f / 255f)
    val hue3 = pair(2) * (360f / 255f)
    val hue4 = pair(3) * (360f / 255f)
    return NodeGradientSpec(
        color1 = Color.hsl(hue1, 0.70f, 0.55f),
        color2 = Color.hsl(hue2, 0.65f, 0.45f),
        color3 = Color.hsl(hue3, 0.75f, 0.60f),
        color4 = Color.hsl(hue4, 0.60f, 0.50f),
        arcStartDeg = hue3
    )
}

@Composable
fun NodeIdGradientAvatar(
    nodeId: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val spec = remember(nodeId) { buildNodeGradientSpec(nodeId) }

    Canvas(modifier = modifier.size(size).clip(CircleShape)) {
        val w = this.size.width
        val h = this.size.height

        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to spec.color1,
                    0.4f to spec.color2,
                    0.8f to spec.color3,
                    1.0f to spec.color4
                ),
                center = Offset(0.35f * w, 0.35f * h),
                radius = maxOf(w, h) * 1.5f
            )
        )

        val strokePx = 1.5.dp.toPx()
        listOf(0.30f, 0.55f, 0.80f).forEach { fraction ->
            val r = (w / 2f) * fraction
            drawArc(
                color = Color.White.copy(alpha = 0.15f),
                startAngle = spec.arcStartDeg,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = Offset(w / 2f - r, h / 2f - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

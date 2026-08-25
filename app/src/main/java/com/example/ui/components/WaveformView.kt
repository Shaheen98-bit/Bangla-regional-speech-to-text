package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.WaveformLavender
import com.example.ui.theme.WaveformPurple
import kotlin.math.sin

/**
 * Animated real-time waveform bars reflecting microphone RMS audio levels in Elegant Dark style.
 */
@Composable
fun WaveformView(
    isRecording: Boolean,
    rmsLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val barCount = 28
        val spacing = size.width / barCount
        val barWidth = spacing * 0.52f
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.88f
        val minBarHeight = 5f

        val gradient = Brush.verticalGradient(
            colors = if (isRecording) listOf(WaveformLavender, WaveformPurple)
            else listOf(Color(0xFF49454F).copy(alpha = 0.5f), Color(0xFF2B2930).copy(alpha = 0.3f))
        )

        for (i in 0 until barCount) {
            val x = i * spacing + spacing / 2f - barWidth / 2f

            val waveFactor = if (isRecording) {
                val sine = (sin((i.toFloat() / barCount * 3.0 * Math.PI + phase).toDouble())).toFloat()
                val dynamicHeight = (rmsLevel * 1.8f + 0.15f) * (0.5f + 0.5f * kotlin.math.abs(sine))
                dynamicHeight.coerceIn(0.08f, 1.0f)
            } else {
                0.08f
            }

            val barHeight = (maxBarHeight * waveFactor).coerceAtLeast(minBarHeight)
            val top = centerY - barHeight / 2f

            drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

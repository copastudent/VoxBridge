package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LiveAudioWaveform(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    activeColor: Color = MaterialTheme.colorScheme.secondary,
    inactiveColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    barCount: Int = 28
) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.08f) } } }
    val smoothAmp by animateFloatAsState(
        targetValue = if (isActive) amplitude.coerceIn(0.06f, 1f) else 0.06f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "waveformAmp"
    )

    LaunchedEffect(amplitude, isActive) {
        if (history.size >= barCount) {
            history.removeAt(0)
        }
        val next = if (isActive) amplitude.coerceIn(0.08f, 1f) else 0.06f
        history.add(next)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val totalWidth = size.width
        val totalHeight = size.height
        val gap = 4.dp.toPx()
        val barWidth = ((totalWidth - gap * (barCount - 1)) / barCount).coerceAtLeast(3f)

        for (i in 0 until barCount) {
            val sample = if (i == barCount - 1) smoothAmp else history.getOrElse(i) { 0.06f }
            val barHeight = (totalHeight * sample).coerceIn(6.dp.toPx(), totalHeight)
            val left = i * (barWidth + gap)
            val top = (totalHeight - barHeight) / 2f
            val color = if (isActive) {
                activeColor.copy(alpha = 0.45f + 0.55f * (i.toFloat() / barCount))
            } else {
                inactiveColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

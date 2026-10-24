package com.offgrid.bmsmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offgrid.bmsmonitor.ui.theme.Accent
import com.offgrid.bmsmonitor.ui.theme.BorderCol
import kotlin.math.max

/**
 * Minimal vertical bar chart drawn on a Canvas -- no external charting library.
 * Values auto-scale to the largest bar (and to [floor] as a baseline minimum).
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    barColors: List<Color>? = null,
    baseline: Float = 0f,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val top = max(values.max(), baseline + 0.001f)
        val bottom = minOf(baseline, values.min())
        val range = max(top - bottom, 0.001f)
        val n = values.size
        val slot = size.width / n
        val barW = slot * 0.6f
        val padX = (slot - barW) / 2f

        // baseline line
        val zeroY = size.height - ((0f - bottom) / range) * size.height
        drawLine(BorderCol, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)

        values.forEachIndexed { i, v ->
            val h = ((v - bottom) / range) * size.height
            val x = i * slot + padX
            val y = size.height - h
            val color = barColors?.getOrNull(i) ?: Accent
            drawRect(color = color, topLeft = Offset(x, y), size = Size(barW, h))
        }
    }
}

/** A simple line/area-less sparkline of points, for trends over time. */
@Composable
fun LineChart(
    series: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    color: Color = Accent,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (series.size < 2) return@Canvas
        val top = series.max()
        val bottom = series.min()
        val range = max(top - bottom, 0.001f)
        val dx = size.width / (series.size - 1)
        var prev = Offset(0f, size.height - ((series[0] - bottom) / range) * size.height)
        for (i in 1 until series.size) {
            val cur = Offset(i * dx, size.height - ((series[i] - bottom) / range) * size.height)
            drawLine(color, prev, cur, strokeWidth = 3f)
            prev = cur
        }
    }
}

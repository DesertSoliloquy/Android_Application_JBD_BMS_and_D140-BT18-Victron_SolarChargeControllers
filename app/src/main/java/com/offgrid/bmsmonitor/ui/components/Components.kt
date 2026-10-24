package com.offgrid.bmsmonitor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.offgrid.bmsmonitor.ui.theme.AmberCol
import com.offgrid.bmsmonitor.ui.theme.BorderCol
import com.offgrid.bmsmonitor.ui.theme.GreenCol
import com.offgrid.bmsmonitor.ui.theme.Muted
import com.offgrid.bmsmonitor.ui.theme.RedCol
import com.offgrid.bmsmonitor.ui.theme.TextCol
import kotlin.math.abs

/** Four signal bars filled by RSSI, like a phone indicator. */
@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier) {
    val level = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val color = if (level >= 3) GreenCol else if (level == 2) AmberCol else if (level <= 1) RedCol else Muted
    Canvas(modifier = modifier.size(26.dp, 18.dp)) {
        val barW = size.width / 6f
        val gap = barW / 2f
        for (i in 0 until 4) {
            val h = size.height * (0.3f + 0.23f * i)
            val x = i * (barW + gap)
            drawRect(
                color = if (i < level) color else BorderCol,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}

/**
 * Flashing directional chevrons: green and rightward when charging (current in),
 * amber and leftward when discharging (current out), speed scaled to magnitude.
 */
@Composable
fun CurrentFlow(amps: Double?, modifier: Modifier = Modifier) {
    val a = amps ?: 0.0
    val charging = a > 0.05
    val idle = abs(a) < 0.05
    val color = if (charging) GreenCol else AmberCol
    val durationMs = (1800.0 / (1.0 + abs(a) * 0.15)).toInt().coerceIn(350, 1800)

    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(30.dp)) {
        val mid = size.height / 2f
        if (idle) {
            drawLine(Muted, androidx.compose.ui.geometry.Offset(8f, mid),
                androidx.compose.ui.geometry.Offset(size.width - 8f, mid), strokeWidth = 3f)
            return@Canvas
        }
        val spacing = 46f
        val chevW = 12f
        val count = (size.width / spacing).toInt() + 2
        val offset = phase * spacing
        for (i in 0 until count) {
            var x = i * spacing + if (charging) offset else -offset
            x = ((x % (size.width + spacing)) + (size.width + spacing)) % (size.width + spacing)
            val path = Path()
            if (charging) {
                path.moveTo(x - chevW, mid - 8f); path.lineTo(x, mid); path.lineTo(x - chevW, mid + 8f)
            } else {
                path.moveTo(x + chevW, mid - 8f); path.lineTo(x, mid); path.lineTo(x + chevW, mid + 8f)
            }
            drawPath(path, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}

/** A small labelled readout used across cards and detail views. */
@Composable
fun StatTile(label: String, value: String, valueColor: Color = TextCol, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A row of evenly spaced stat tiles. */
@Composable
fun StatRow(vararg tiles: Pair<String, Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((label, vc) in tiles) StatTile(label, vc.first, vc.second)
    }
}

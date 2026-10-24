@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.offgrid.bmsmonitor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.ui.Fmt
import com.offgrid.bmsmonitor.ui.theme.Accent
import com.offgrid.bmsmonitor.ui.theme.AmberCol
import com.offgrid.bmsmonitor.ui.theme.BorderCol
import com.offgrid.bmsmonitor.ui.theme.GreenCol
import com.offgrid.bmsmonitor.ui.theme.Muted
import com.offgrid.bmsmonitor.ui.theme.RedCol
import com.offgrid.bmsmonitor.ui.theme.Surface
import com.offgrid.bmsmonitor.ui.theme.TextCol
import com.offgrid.bmsmonitor.ui.theme.socColor

@Composable
private fun CardHeader(name: String, sub: String, rssi: Int?) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = TextCol, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Muted, fontSize = 11.sp)
        }
        SignalBars(rssi)
    }
}

@Composable
private fun cardColors() = CardDefaults.cardColors(containerColor = Surface)

@Composable
fun BatteryCard(t: BatteryTelemetry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = cardColors(),
        border = BorderStroke(1.dp, BorderCol),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader(t.info.name, "${t.info.protocol.uppercase()}  ·  ${t.info.address}", t.info.rssi)
            if (t.error != null) {
                Text("offline — ${t.error}", color = RedCol, fontSize = 12.sp)
            } else {
                CurrentFlow(t.current)
                val cur = t.current
                val label = when {
                    cur == null || kotlin.math.abs(cur) < 0.05 -> "idle" to Muted
                    cur > 0 -> "charging  ${Fmt.a(cur, 1)}  in" to GreenCol
                    else -> "discharging  ${Fmt.a(kotlin.math.abs(cur), 1)}  out" to AmberCol
                }
                Text(label.first, color = label.second, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("State of charge", Fmt.pct(t.soc), socColor(t.soc?.toFloat()))
                    StatTile("Voltage", Fmt.v(t.voltage))
                    StatTile("Current", Fmt.aSigned(t.current))
                }
            }
        }
    }
}

@Composable
fun SolarCard(t: SolarTelemetry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = cardColors(),
        border = BorderStroke(1.dp, BorderCol),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader(t.info.name, "${t.info.protocol.uppercase()}  ·  ${t.info.address}", t.info.rssi)
            if (t.error != null) {
                Text(t.error, color = AmberCol, fontSize = 12.sp)
            } else {
                CurrentFlow(t.batteryCurrent)
                val state = t.chargeState ?: "--"
                val pvv = if (t.pvVoltage != null) "   ·   ${Fmt.v(t.pvVoltage, 0)} PV" else ""
                Text("$state$pvv", color = if ((t.pvPower ?: 0.0) > 5) GreenCol else Muted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("PV power", Fmt.w(t.pvPower), Accent)
                    StatTile("Battery", Fmt.v(t.batteryVoltage))
                    StatTile("Yield today", Fmt.wh(t.yieldTodayWh))
                }
            }
        }
    }
}

package com.offgrid.bmsmonitor.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.ui.Fmt
import com.offgrid.bmsmonitor.ui.MonitorViewModel
import com.offgrid.bmsmonitor.ui.components.BarChart
import com.offgrid.bmsmonitor.ui.components.BatteryCard
import com.offgrid.bmsmonitor.ui.components.CurrentFlow
import com.offgrid.bmsmonitor.ui.components.LineChart
import com.offgrid.bmsmonitor.ui.components.SignalBars
import com.offgrid.bmsmonitor.ui.components.SolarCard
import com.offgrid.bmsmonitor.ui.components.StatTile
import androidx.compose.ui.graphics.Color
import com.offgrid.bmsmonitor.ui.theme.Accent
import com.offgrid.bmsmonitor.ui.theme.AmberCol
import com.offgrid.bmsmonitor.ui.theme.BorderCol
import com.offgrid.bmsmonitor.ui.theme.GreenCol
import com.offgrid.bmsmonitor.ui.theme.Muted
import com.offgrid.bmsmonitor.ui.theme.Surface
import com.offgrid.bmsmonitor.ui.theme.TextCol
import com.offgrid.bmsmonitor.ui.theme.VioletCol
import com.offgrid.bmsmonitor.ui.theme.socColor

@Composable
private fun EmptyNote(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text(text, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ConnectingCard(info: DeviceInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), border = BorderStroke(1.dp, BorderCol),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(info.name, color = TextCol, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${info.protocol.uppercase()}  ·  connecting…", color = Muted, fontSize = 11.sp)
        }
    }
}

// ---- Batteries --------------------------------------------------------------

@Composable
fun BatteriesGrid(vm: MonitorViewModel, devices: List<DeviceInfo>, batteries: Map<String, BatteryTelemetry>, onOpen: (String) -> Unit) {
    val bms = devices.filter { it.kind == DeviceKind.BMS }
    if (bms.isEmpty()) { EmptyNote("Scanning for battery BMSs…\nTurn off Simulation in Settings once your hardware is paired."); return }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(bms, key = { it.key }) { info ->
            val t = batteries[info.key]
            if (t == null) ConnectingCard(info) else BatteryCard(t) { onOpen(info.key) }
        }
    }
}

@Composable
fun BatteryDetail(t: BatteryTelemetry) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.info.name, color = TextCol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${t.info.protocol.uppercase()}  ·  ${t.info.address}", color = Muted, fontSize = 12.sp)
        if (t.error != null) { Text("Last poll failed: ${t.error}", color = androidx.compose.ui.graphics.Color.Red); return@Column }

        CurrentFlow(t.current)
        FlowRowStats(
            "SoC" to Pair(Fmt.pct(t.soc), socColor(t.soc?.toFloat())),
            "Voltage" to Pair(Fmt.v(t.voltage), TextCol),
            "Current" to Pair(Fmt.aSigned(t.current), TextCol),
            "Power" to Pair(t.power?.let { "%+.0f W".format(it) } ?: "--", TextCol),
            "Remaining" to Pair(t.remainingAh?.let { "%.1f Ah".format(it) } ?: "--", TextCol),
            "Capacity" to Pair(t.capacityAh?.let { "%.0f Ah".format(it) } ?: "--", TextCol),
            "Cycles" to Pair(t.cycles?.toString() ?: "--", TextCol),
            "Cell Δ" to Pair(t.cellDeltaMv?.let { "%.0f mV".format(it) } ?: "--", TextCol),
            "Temp" to Pair(Fmt.temp(t.temperatures), TextCol),
            "Charge MOS" to Pair(mos(t.chargeMos), TextCol),
            "Discharge MOS" to Pair(mos(t.dischargeMos), TextCol),
            "Balancing" to Pair(mos(t.balancing), TextCol),
        )
        if (t.protectionFlags.isNotEmpty()) Text(t.protectionFlags.joinToString("  ·  "), color = androidx.compose.ui.graphics.Color.Red, fontSize = 12.sp)

        if (t.cells.isNotEmpty()) {
            Text("Cell voltages", color = Muted, fontSize = 12.sp)
            val lo = t.cells.minOf { it.voltage }
            BarChart(
                values = t.cells.map { it.voltage.toFloat() },
                baseline = (lo - 0.05).toFloat().coerceAtLeast(0f),
                barColors = t.cells.map { if (kotlin.math.abs(it.voltage - lo) < 0.003) GreenCol else Accent },
            )
        }
    }
}

private fun mos(b: Boolean?) = if (b == null) "--" else if (b) "on" else "off"

// ---- Solar ------------------------------------------------------------------

@Composable
fun SolarGrid(vm: MonitorViewModel, devices: List<DeviceInfo>, solar: Map<String, SolarTelemetry>, networkPv: Double, onOpen: (String) -> Unit) {
    val controllers = devices.filter { it.kind == DeviceKind.SOLAR }
    if (controllers.isEmpty()) { EmptyNote("Scanning for solar charge controllers…"); return }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text("Network PV total:  ${Fmt.w(networkPv)}", color = GreenCol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        items(controllers, key = { it.key }) { info ->
            val t = solar[info.key]
            if (t == null) ConnectingCard(info) else SolarCard(t) { onOpen(info.key) }
        }
    }
}

@Composable
fun SolarDetail(vm: MonitorViewModel, t: SolarTelemetry) {
    val pendingKeys by vm.pendingLoad.collectAsState()
    val loadBusy = pendingKeys.contains(t.info.key)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.info.name, color = TextCol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${t.info.protocol.uppercase()}  ·  ${t.info.address}", color = Muted, fontSize = 12.sp)
        if (t.error != null) { Text(t.error, color = androidx.compose.ui.graphics.Color.Red); return@Column }

        // D140 load output -- the only control the app sends (everything else is
        // read-only). Shown up top so it's the first thing on the D140 screen.
        if (t.info.protocol == "d140" || t.loadOn != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Load output", color = TextCol, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                loadBusy -> "working…"
                                t.loadOn == true -> "on"
                                t.loadOn == false -> "off"
                                else -> "tap to toggle"
                            },
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    if (loadBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = t.loadOn == true,
                            onCheckedChange = { vm.toggleLoad(t.info.key, it) },
                        )
                    }
                }
            }
        }

        FlowRowStats(
            "PV power" to Pair(Fmt.w(t.pvPower), Accent),
            "PV voltage" to Pair(Fmt.v(t.pvVoltage, 1), TextCol),
            "PV current" to Pair(Fmt.a(t.pvCurrent, 1), TextCol),
            "Battery V" to Pair(Fmt.v(t.batteryVoltage), TextCol),
            "Battery A" to Pair(Fmt.a(t.batteryCurrent, 1), TextCol),
            "SoC" to Pair(Fmt.pct(t.soc), socColor(t.soc?.toFloat())),
            "Yield today" to Pair(Fmt.wh(t.yieldTodayWh), TextCol),
            "Charge state" to Pair(t.chargeState ?: "--", TextCol),
            "Temp" to Pair(t.controllerTemp?.let { "%.0f°C".format(it) } ?: "--", TextCol),
            "Load" to Pair(t.loadOn?.let { if (it) "on" else "off" } ?: "--", TextCol),
        )

        Text("Live (PV W / Batt V / SoC %)", color = Muted, fontSize = 12.sp)
        BarChart(
            values = listOf((t.pvPower ?: 0.0).toFloat(), (t.batteryVoltage ?: 0.0).toFloat(), (t.soc ?: 0.0).toFloat()),
            barColors = listOf(Accent, GreenCol, VioletCol),
        )

        val trend = vm.recentSolar(t.info.key)
        if (trend.size >= 2) {
            Text("PV power trend (recent)", color = Muted, fontSize = 12.sp)
            LineChart(series = trend.map { it.pvW.toFloat() }, color = Accent)
        }
    }
}

// ---- History ----------------------------------------------------------------

@Composable
fun HistoryScreen(vm: MonitorViewModel, devices: List<DeviceInfo>) {
    if (devices.isEmpty()) { EmptyNote("No devices yet."); return }
    var selected by remember { mutableStateOf(devices.first().key) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Device", color = Muted, fontSize = 12.sp)
        devices.forEach { info ->
            val sel = info.key == selected
            Text(
                "${info.name}  (${info.kind.name.lowercase()})",
                color = if (sel) Accent else TextCol,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable { selected = info.key }.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val info = devices.first { it.key == selected }
        if (info.kind == DeviceKind.SOLAR) {
            val s = vm.recentSolar(selected)
            if (s.size >= 2) {
                Text("PV power (W)", color = Muted, fontSize = 12.sp)
                LineChart(s.map { it.pvW.toFloat() }, color = Accent)
                Text("Battery voltage (V)", color = Muted, fontSize = 12.sp)
                LineChart(s.map { it.battV.toFloat() }, color = GreenCol)
            } else EmptyNote("Collecting samples…")
        } else {
            val b = vm.recentBattery(selected)
            if (b.size >= 2) {
                Text("Voltage (V)", color = Muted, fontSize = 12.sp)
                LineChart(b.map { it.voltage.toFloat() }, color = GreenCol)
                Text("Current (A)", color = Muted, fontSize = 12.sp)
                LineChart(b.map { it.current.toFloat() }, color = Accent)
            } else EmptyNote("Collecting samples…")
        }
    }
}

// ---- shared -----------------------------------------------------------------

/** A simple two-column grid of stat tiles. */
@Composable
fun FlowRowStats(vararg stats: Pair<String, Pair<String, androidx.compose.ui.graphics.Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.toList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, vc) ->
                    StatTile(label, vc.first, vc.second, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---- Summary (compact dashboard) -------------------------------------------

/**
 * A dense, glanceable list -- one compact row per device. Designed to stay
 * readable in a small / split-screen window.
 */
@Composable
fun SummaryScreen(
    vm: MonitorViewModel,
    devices: List<DeviceInfo>,
    batteries: Map<String, BatteryTelemetry>,
    solar: Map<String, SolarTelemetry>,
) {
    if (devices.isEmpty()) { EmptyNote("No devices yet."); return }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        items(devices, key = { it.key }) { info ->
            when (info.kind) {
                DeviceKind.BMS -> {
                    val t = batteries[info.key]
                    SummaryRow(info, t?.error, listOfNotNull(
                        t?.soc?.let { Fmt.pct(it) to socColor(it.toFloat()) },
                        t?.voltage?.let { Fmt.v(it) to TextCol },
                        t?.current?.let { Fmt.aSigned(it) to (if (it > 0) GreenCol else AmberCol) },
                    ))
                }
                DeviceKind.SOLAR -> {
                    val t = solar[info.key]
                    SummaryRow(info, t?.error, listOfNotNull(
                        t?.pvPower?.let { Fmt.w(it) to Accent },
                        t?.batteryVoltage?.let { Fmt.v(it) to TextCol },
                        t?.chargeState?.let { it to Muted },
                    ))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(info: DeviceInfo, error: String?, stats: List<Pair<String, Color>>) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(info.name, color = TextCol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (error != null) {
                Text("offline", color = AmberCol, fontSize = 12.sp)
            } else {
                stats.forEach { (txt, col) ->
                    Text(txt, color = col, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            SignalBars(info.rssi)
        }
    }
}

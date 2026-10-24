package com.offgrid.bmsmonitor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.ui.MonitorViewModel
import com.offgrid.bmsmonitor.ui.theme.Bg
import com.offgrid.bmsmonitor.ui.theme.Muted
import com.offgrid.bmsmonitor.ui.theme.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MonitorViewModel, onRequestPermissions: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var batteryKey by remember { mutableStateOf<String?>(null) }
    var solarKey by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var keyDialogFor by remember { mutableStateOf<DeviceInfo?>(null) }

    val inDetail = (tab == 1 && batteryKey != null) || (tab == 2 && solarKey != null)
    BackHandler(enabled = inDetail) { batteryKey = null; solarKey = null }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                navigationIcon = {
                    if (inDetail) IconButton(onClick = { batteryKey = null; solarKey = null }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (inDetail) "Detail" else "Battery & Solar Monitor", fontSize = 18.sp) },
                actions = {
                    Text("Sim", color = Muted, fontSize = 12.sp)
                    Switch(
                        checked = settings.simulate,
                        onCheckedChange = { on ->
                            vm.setSimulation(on)
                            if (!on && !vm.hasPermissions()) onRequestPermissions()
                        },
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Dashboard, null) }, label = { Text("Summary") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.BatteryFull, null) }, label = { Text("Batteries") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.WbSunny, null) }, label = { Text("Solar") },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.ShowChart, null) }, label = { Text("History") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                0 -> SummaryScreen(vm, discovered, snapshot.batteries, snapshot.solar)
                1 -> {
                    val key = batteryKey
                    val t = key?.let { snapshot.batteries[it] }
                    if (t != null) BatteryDetail(t)
                    else BatteriesGrid(vm, discovered, snapshot.batteries) { batteryKey = it }
                }
                2 -> {
                    val key = solarKey
                    val t = key?.let { snapshot.solar[it] }
                    if (t != null) SolarDetail(vm, t)
                    else SolarGrid(vm, discovered, snapshot.solar, snapshot.networkPvPower) { solarKey = it }
                }
                else -> HistoryScreen(vm, discovered)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            vm = vm,
            pollIntervalS = settings.pollIntervalS,
            simulate = settings.simulate,
            solarDevices = discovered.filter { it.kind == DeviceKind.SOLAR },
            status = status,
            onSetKey = { keyDialogFor = it },
            onDismiss = { showSettings = false },
        )
    }

    keyDialogFor?.let { info ->
        VictronKeyDialog(
            info = info,
            existing = vm.hasKey(info.key),
            onSave = { vm.setVictronKey(info.key, it); keyDialogFor = null },
            onForget = { vm.forgetKey(info.key); keyDialogFor = null },
            onDismiss = { keyDialogFor = null },
        )
    }
}

@Composable
private fun SettingsDialog(
    vm: MonitorViewModel,
    pollIntervalS: Int,
    simulate: Boolean,
    solarDevices: List<DeviceInfo>,
    status: String,
    onSetKey: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var interval by remember { mutableStateOf(pollIntervalS.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Status: $status", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text("Poll every ${interval.toInt()} s", fontSize = 14.sp)
                Slider(
                    value = interval, onValueChange = { interval = it },
                    valueRange = 1f..120f,
                    onValueChangeFinished = { vm.setPollInterval(interval.toInt()) },
                )
                Text(
                    if (simulate) "Simulation mode is ON (synthetic devices)."
                    else "Live mode — scanning for real hardware.",
                    color = Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text("Controller keys", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (solarDevices.none { it.needsSecret }) {
                    Text("No controllers needing a key yet.", color = Muted, fontSize = 12.sp)
                } else {
                    solarDevices.filter { it.needsSecret }.forEach { info ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(info.name, fontSize = 13.sp)
                                Text(if (vm.hasKey(info.key)) "key saved" else "no key set", color = Muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { onSetKey(info) }) { Text("Set key") }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun VictronKeyDialog(
    info: DeviceInfo,
    existing: Boolean,
    onSave: (String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (existing) TextButton(onClick = onForget) { Text("Forget") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        title = { Text("Encryption key") },
        text = {
            Column {
                Text("${info.name}\n${info.address}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "VictronConnect → gear → Product info → enable Instant readout → " +
                        "Encryption data → Show, then paste the 32-character key.",
                    color = Muted, fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("32 hex characters") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

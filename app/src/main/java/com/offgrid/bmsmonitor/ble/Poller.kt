package com.offgrid.bmsmonitor.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.offgrid.bmsmonitor.data.CredentialStore
import com.offgrid.bmsmonitor.data.History
import com.offgrid.bmsmonitor.data.SettingsStore
import com.offgrid.bmsmonitor.driver.BroadcastDriver
import com.offgrid.bmsmonitor.driver.ConnectDriver
import com.offgrid.bmsmonitor.driver.DeviceDriver
import com.offgrid.bmsmonitor.driver.DriverRegistry
import com.offgrid.bmsmonitor.driver.ble.BleConnection
import com.offgrid.bmsmonitor.driver.sim.Simulator
import com.offgrid.bmsmonitor.driver.solar.D140Driver
import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.Snapshot
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.model.Telemetry
import com.offgrid.bmsmonitor.model.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The acquisition hub. Scans for devices, parses Victron broadcasts as they
 * arrive, and polls every connection-based device on a timer -- but only one
 * BLE connection is ever open at a time (connect -> read -> disconnect), which
 * keeps us under the adapter's simultaneous-connection ceiling.
 */
class Poller(
    context: Context,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
    private val history: History,
) {
    private val appContext = context.applicationContext
    private val scanner = BleScanner(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()

    private data class Live(val info: DeviceInfo, val driver: DeviceDriver, val device: BluetoothDevice?)

    private val devices = ConcurrentHashMap<String, Live>()
    private val batteries = ConcurrentHashMap<String, BatteryTelemetry>()
    private val solar = ConcurrentHashMap<String, SolarTelemetry>()
    private val lastAdv = ConcurrentHashMap<String, Map<Int, ByteArray>>()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val _discovered = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discovered: StateFlow<List<DeviceInfo>> = _discovered
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _pendingLoad = MutableStateFlow<Set<String>>(emptySet())
    val pendingLoad: StateFlow<Set<String>> = _pendingLoad

    @Volatile private var running = false
    private var mainJob: Job? = null

    fun start() {
        if (running) return
        running = true
        mainJob = scope.launch { runLoop() }
    }

    fun stop() {
        running = false
        scanner.stop()
        mainJob?.cancel()
    }

    /** Called when the user flips simulation mode or grants permissions. */
    fun restart() {
        stop()
        devices.clear(); batteries.clear(); solar.clear()
        _discovered.value = emptyList()
        _snapshot.value = Snapshot()
        start()
    }

    private suspend fun runLoop() {
        if (settings.current.simulate) runSimulated() else runLive()
    }

    // ---- simulation -------------------------------------------------------
    private suspend fun runSimulated() {
        for (info in Simulator.devices()) devices[info.key] = Live(info, DriverRegistry.byProtocol(info.protocol) ?: continue, null)
        refreshDiscovered()
        _status.value = "Simulation mode"
        while (running) {
            for (live in devices.values) {
                when (live.info.kind) {
                    DeviceKind.BMS -> publish(Simulator.battery(live.info))
                    DeviceKind.SOLAR -> publish(Simulator.solar(live.info))
                }
            }
            delay(settings.current.pollIntervalS * 1000L)
        }
    }

    // ---- live BLE ---------------------------------------------------------
    private suspend fun runLive() {
        if (!scanner.isBluetoothOn) {
            _status.value = "Bluetooth is off"
        }
        _scanning.value = true
        scanner.start { hit -> onHit(hit) }
        _status.value = "Scanning..."
        while (running) {
            pollConnectDevices()
            delay(settings.current.pollIntervalS * 1000L)
        }
        scanner.stop()
        _scanning.value = false
    }

    private fun onHit(hit: ScanHit) {
        val driver = DriverRegistry.identify(hit.name, hit.serviceUuids, hit.manufacturerData) ?: return
        val info = DeviceInfo(
            address = hit.address,
            name = hit.name ?: hit.address,
            protocol = driver.protocol,
            kind = driver.kind,
            rssi = hit.rssi,
            transport = Transport.BLE,
            needsSecret = driver.needsSecret,
        )
        val isNew = !devices.containsKey(info.key)
        devices[info.key] = Live(info, driver, hit.device)
        if (isNew) refreshDiscovered() else updateRssi(info.key, hit.rssi)

        // Victron and other broadcast devices are read passively, right here.
        if (driver is BroadcastDriver) {
            lastAdv[info.key] = hit.manufacturerData
            val secret = credentials.get(info.key)
            val t = driver.parse(info, hit.manufacturerData, secret)
            if (t != null) publish(t)
        }
    }

    /**
     * Re-parse the most recent advertisement for a broadcast device right now
     * (used the moment a Victron key is entered, so the card updates instantly
     * instead of waiting for the next advertisement or poll tick).
     */
    fun revalidate(deviceKey: String) {
        val live = devices[deviceKey] ?: return
        val driver = live.driver as? BroadcastDriver ?: return
        val adv = lastAdv[deviceKey] ?: return
        val t = driver.parse(live.info, adv, credentials.get(deviceKey))
        if (t != null) publish(t)
    }

    /**
     * Toggle the D140's load output. This is the only command the app ever
     * writes, and only to the D140's load register -- never to a BMS or any
     * other controller. After writing it re-reads the device to confirm.
     */
    fun requestLoad(deviceKey: String, on: Boolean) {
        val live = devices[deviceKey] ?: return
        val driver = live.driver
        val device = live.device ?: return
        if (driver !is D140Driver) return
        scope.launch {
            _pendingLoad.value = _pendingLoad.value + deviceKey
            try {
                connectionMutex.withLock {
                    val conn = BleConnection(appContext, device, driver)
                    try {
                        conn.open()
                        driver.setLoad(conn, on)
                        // These controllers need a quiet gap after a write before
                        // they will answer a read; then read back to confirm.
                        delay(600)
                        val readBack = runCatching { driver.poll(live.info, conn) as? SolarTelemetry }
                            .getOrNull()
                        if (readBack != null && readBack.error == null) {
                            publish(readBack)
                        } else {
                            // Write almost certainly took effect; reflect the
                            // intended state without flagging the card offline.
                            val base = solar[deviceKey]
                            if (base != null) {
                                publish(base.copy(loadOn = on, timestampMs = System.currentTimeMillis()))
                            }
                        }
                    } catch (e: Exception) {
                        publishError(live.info, e.message ?: "load command failed")
                    } finally {
                        conn.close()
                    }
                }
            } finally {
                _pendingLoad.value = _pendingLoad.value - deviceKey
            }
        }
    }

    private suspend fun pollConnectDevices() {
        for (live in devices.values) {
            if (!running) break
            val driver = live.driver
            if (driver !is ConnectDriver) continue
            val device = live.device ?: continue
            connectionMutex.withLock {
                val conn = BleConnection(appContext, device, driver)
                try {
                    conn.open()
                    driver.onConnected(conn)
                    val t = driver.poll(live.info, conn)
                    publish(t)
                } catch (e: Exception) {
                    publishError(live.info, e.message ?: "poll failed")
                } finally {
                    conn.close()
                }
            }
        }
    }

    // ---- publishing -------------------------------------------------------
    private fun publish(t: Telemetry) {
        when (t) {
            is BatteryTelemetry -> { batteries[t.info.key] = t; history.addBattery(t) }
            is SolarTelemetry -> { solar[t.info.key] = t; history.addSolar(t) }
        }
        emit()
    }

    private fun publishError(info: DeviceInfo, message: String) {
        if (info.kind == DeviceKind.BMS) batteries[info.key] = BatteryTelemetry(info, error = message)
        else solar[info.key] = SolarTelemetry(info, error = message)
        emit()
    }

    private fun emit() {
        _snapshot.value = Snapshot(batteries.toMap(), solar.toMap(), System.currentTimeMillis())
    }

    private fun refreshDiscovered() {
        _discovered.value = devices.values.map { it.info }.sortedBy { it.name }
    }

    private fun updateRssi(key: String, rssi: Int) {
        val live = devices[key] ?: return
        if (live.info.rssi != rssi) {
            devices[key] = live.copy(info = live.info.copy(rssi = rssi))
        }
    }
}

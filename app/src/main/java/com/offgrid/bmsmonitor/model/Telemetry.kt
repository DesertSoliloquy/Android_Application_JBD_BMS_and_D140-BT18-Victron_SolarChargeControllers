package com.offgrid.bmsmonitor.model

/**
 * Normalized telemetry models. Every driver converts its wire format into one
 * of these; the UI never touches a raw JBD frame or a Victron advertisement.
 *
 * Sign convention for current: positive = flowing INTO the battery (charging),
 * negative = flowing OUT (discharging).
 */

enum class DeviceKind { BMS, SOLAR }

enum class Transport { BLE, SIM }

data class DeviceInfo(
    val address: String,            // MAC, or a synthetic id for simulated devices
    val name: String = "",
    val protocol: String = "",
    val kind: DeviceKind = DeviceKind.BMS,
    val rssi: Int? = null,
    val transport: Transport = Transport.BLE,
    val needsSecret: Boolean = false,
) {
    val key: String get() = "${transport.name.lowercase()}:$address"
}

data class CellData(
    val index: Int,
    val voltage: Double,            // volts
    val balancing: Boolean = false,
)

/** Common supertype so a driver's poll() can return either kind. */
sealed interface Telemetry {
    val info: DeviceInfo
}

data class BatteryTelemetry(
    override val info: DeviceInfo,
    val timestampMs: Long = System.currentTimeMillis(),
    val voltage: Double? = null,
    val current: Double? = null,    // signed amps
    val soc: Double? = null,        // percent
    val remainingAh: Double? = null,
    val capacityAh: Double? = null,
    val cycles: Int? = null,
    val cells: List<CellData> = emptyList(),
    val temperatures: List<Double> = emptyList(),
    val chargeMos: Boolean? = null,
    val dischargeMos: Boolean? = null,
    val balancing: Boolean? = null,
    val protectionFlags: List<String> = emptyList(),
    val firmware: String? = null,
    val error: String? = null,
) : Telemetry {
    val power: Double?
        get() = if (voltage != null && current != null) voltage * current else null

    val cellDeltaMv: Double?
        get() {
            if (cells.size < 2) return null
            val v = cells.map { it.voltage }
            return (v.max() - v.min()) * 1000.0
        }

    val charging: Boolean get() = (current ?: 0.0) > 0.05
    val discharging: Boolean get() = (current ?: 0.0) < -0.05
}

data class SolarTelemetry(
    override val info: DeviceInfo,
    val timestampMs: Long = System.currentTimeMillis(),
    val pvVoltage: Double? = null,
    val pvCurrent: Double? = null,
    val pvPowerRaw: Double? = null,
    val batteryVoltage: Double? = null,
    val batteryCurrent: Double? = null,     // positive = charging
    val soc: Double? = null,
    val loadCurrent: Double? = null,
    val loadPower: Double? = null,
    val loadOn: Boolean? = null,
    val controllerTemp: Double? = null,
    val batteryTemp: Double? = null,
    val chargeState: String? = null,
    val yieldTodayWh: Double? = null,
    val pvPMaxTodayW: Double? = null,
    val batteryVMaxToday: Double? = null,
    val batteryVMinToday: Double? = null,
    val error: String? = null,
) : Telemetry {
    /** PV power, derived from V*I when the controller doesn't report it directly. */
    val pvPower: Double?
        get() = pvPowerRaw ?: if (pvVoltage != null && pvCurrent != null) pvVoltage * pvCurrent else null
}

/** What the poller publishes on every tick: the latest reading per device key. */
data class Snapshot(
    val batteries: Map<String, BatteryTelemetry> = emptyMap(),
    val solar: Map<String, SolarTelemetry> = emptyMap(),
    val scannedAtMs: Long? = null,
) {
    val networkPvPower: Double
        get() = solar.values.sumOf { it.pvPower ?: 0.0 }
}

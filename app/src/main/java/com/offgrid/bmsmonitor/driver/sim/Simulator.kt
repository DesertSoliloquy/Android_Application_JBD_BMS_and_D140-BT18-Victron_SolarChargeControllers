package com.offgrid.bmsmonitor.driver.sim

import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.CellData
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.model.Transport
import java.util.Calendar
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic devices so the whole UI runs with no hardware attached. The PV
 * curve follows the local clock: zero at night, peaking near solar noon;
 * batteries charge while the sun is up and discharge at night.
 */
object Simulator {

    private data class BSpec(val mac: String, val name: String, val proto: String, val cells: Int)
    private data class SSpec(val mac: String, val name: String, val proto: String, val ratedW: Double)

    private val BATTERIES = listOf(
        BSpec("AA:BB:CC:00:00:01", "LiFePO4 Bank A", "jbd", 16),
        BSpec("AA:BB:CC:00:00:02", "LiFePO4 Bank B", "jk", 16),
        BSpec("AA:BB:CC:00:00:03", "Starter 12V", "daly", 4),
        BSpec("AA:BB:CC:00:00:04", "Shed Pack", "ant", 8),
    )
    private val CONTROLLERS = listOf(
        SSpec("DD:EE:FF:00:00:01", "SmartSolar 100/30", "victron", 600.0),
        SSpec("DD:EE:FF:00:00:02", "SmartSolar 150/100", "victron", 3000.0),
        SSpec("DD:EE:FF:00:00:03", "D140", "d140", 400.0),
    )

    private val socState = HashMap<String, Double>()

    fun devices(): List<DeviceInfo> {
        val out = ArrayList<DeviceInfo>()
        for (b in BATTERIES) out.add(
            DeviceInfo(b.mac, b.name, b.proto, DeviceKind.BMS, Random.nextInt(-88, -46), Transport.SIM)
        )
        for (s in CONTROLLERS) out.add(
            DeviceInfo(s.mac, s.name, s.proto, DeviceKind.SOLAR, Random.nextInt(-90, -50), Transport.SIM)
        )
        return out
    }

    private fun solarFraction(): Double {
        val c = Calendar.getInstance()
        val hours = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        if (hours < 6.0 || hours > 19.0) return 0.0
        val phase = (hours - 6.0) / 13.0
        return max(0.0, sin(Math.PI * phase)).pow(1.6)
    }

    fun battery(info: DeviceInfo): BatteryTelemetry {
        val spec = BATTERIES.firstOrNull { it.mac == info.address } ?: BATTERIES[0]
        val sun = solarFraction()
        val current = sun * Random.nextDouble(8.0, 26.0) - Random.nextDouble(2.0, 9.0)
        var soc = socState.getOrPut(info.address) { Random.nextDouble(45.0, 92.0) }
        soc = (soc + current * 0.004).coerceIn(4.0, 100.0)
        socState[info.address] = soc
        val nominal = 3.30 + (soc / 100.0) * 0.28
        val cells = (0 until spec.cells).map {
            CellData(it + 1, (nominal + Random.nextDouble(-0.012, 0.012)))
        }
        val packV = cells.sumOf { it.voltage }
        val maxV = cells.maxOf { it.voltage }
        return BatteryTelemetry(
            info = info,
            voltage = packV,
            current = current,
            soc = soc,
            remainingAh = 100.0 * (spec.cells / 4) * soc / 100.0,
            capacityAh = 100.0 * (spec.cells / 4),
            cycles = 120,
            temperatures = listOf(Random.nextDouble(16.0, 31.0)),
            cells = cells.map { it.copy(balancing = it.voltage >= maxV - 0.002) },
            chargeMos = true, dischargeMos = true,
            balancing = true,
            firmware = "sim",
        )
    }

    fun solar(info: DeviceInfo): SolarTelemetry {
        val spec = CONTROLLERS.firstOrNull { it.mac == info.address } ?: CONTROLLERS[0]
        val sun = solarFraction()
        val pvW = spec.ratedW * sun * Random.nextDouble(0.82, 1.0)
        val pvV = if (pvW <= 1) 0.0 else Random.nextDouble(48.0, 96.0) * (0.6 + 0.4 * sun)
        val battV = 13.1 + 1.5 * sun + Random.nextDouble(-0.05, 0.05)
        val state = when {
            sun > 0.55 -> "Bulk"; sun > 0.12 -> "Absorption"; sun > 0.02 -> "Float"; else -> "Off"
        }
        return SolarTelemetry(
            info = info,
            pvVoltage = pvV,
            pvCurrent = if (pvV > 1) pvW / pvV else 0.0,
            pvPowerRaw = pvW,
            batteryVoltage = battV,
            batteryCurrent = if (pvW > 1) pvW / max(battV, 1.0) else 0.0,
            soc = (55 + 40 * sun).coerceAtMost(100.0),
            yieldTodayWh = spec.ratedW * 2.5 * sun,
            chargeState = state,
            controllerTemp = Random.nextDouble(18.0, 44.0),
        )
    }
}

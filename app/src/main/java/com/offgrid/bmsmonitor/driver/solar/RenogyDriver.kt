package com.offgrid.bmsmonitor.driver.solar

import com.offgrid.bmsmonitor.driver.B
import com.offgrid.bmsmonitor.driver.BleLink
import com.offgrid.bmsmonitor.driver.ByteBuf
import com.offgrid.bmsmonitor.driver.ConnectDriver
import com.offgrid.bmsmonitor.driver.DriverError
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.model.Telemetry

/**
 * Renogy BT-1 / BT-2 controllers: a transparent Modbus-RTU bridge over BLE.
 * Write a normal RTU read frame to the write characteristic; the reply comes
 * back on notify. Rich Solar is the same OEM board (see [RichSolarDriver]).
 *
 * Holding registers from base 0x0100: SOC, batt V (0.1), charge I (0.01),
 * temps, PV V (0.1), PV I (0.01), PV W, today's maxima, yield, charge state.
 */
open class RenogyDriverBase(
    override val protocol: String,
    override val displayName: String,
    private val nameHints: List<String>,
) : ConnectDriver {
    override val kind = DeviceKind.SOLAR

    override val serviceUuid = "0000ffd0-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000ffd1-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000fff1-0000-1000-8000-00805f9b34fb"

    private val chargeState = mapOf(
        0 to "Off", 1 to "Activated", 2 to "MPPT", 3 to "Equalizing",
        4 to "Boost", 5 to "Float", 6 to "Current limiting",
    )

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        return nameHints.any { low.contains(it) }
    }

    private fun readFrame(deviceId: Int, start: Int, count: Int): ByteArray {
        val body = B.bytesOf(deviceId, 0x03, (start ushr 8) and 0xFF, start and 0xFF, (count ushr 8) and 0xFF, count and 0xFF)
        return body + B.modbusCrc(body)
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        if (buf.size < 5) return emptyList()
        val byteCount = buf.byteAt(2)
        val total = 3 + byteCount + 2
        if (buf.size < total) return emptyList()
        val frame = buf.consume(total)
        val crc = B.modbusCrc(frame.copyOfRange(0, total - 2))
        return if (crc.contentEquals(frame.copyOfRange(total - 2, total))) listOf(frame) else emptyList()
    }

    private suspend fun readRegs(link: BleLink, deviceId: Int, start: Int, count: Int): IntArray {
        val frame = link.request(readFrame(deviceId, start, count))
        if (frame.size < 5 || (frame[1].toInt() and 0xFF) != 0x03) throw DriverError("bad modbus reply")
        val bc = frame[2].toInt() and 0xFF
        return IntArray(bc / 2) { B.u16be(frame, 3 + it * 2) }
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        val r = try {
            readRegs(link, 0xFF, 0x0100, 0x22)
        } catch (_: DriverError) {
            readRegs(link, 0x01, 0x0100, 0x22)
        }
        fun g(off: Int): Int? = if (off < r.size) r[off] else null

        val tempWord = g(0x03) ?: 0
        fun signedTemp(byte: Int): Double {
            val v = byte and 0x7F
            return if (byte and 0x80 != 0) (-v).toDouble() else v.toDouble()
        }
        return SolarTelemetry(
            info = info,
            soc = g(0x00)?.toDouble(),
            batteryVoltage = (g(0x01) ?: 0) / 10.0,
            batteryCurrent = (g(0x02) ?: 0) / 100.0,
            controllerTemp = signedTemp(tempWord ushr 8),
            batteryTemp = signedTemp(tempWord and 0xFF),
            pvVoltage = (g(0x07) ?: 0) / 10.0,
            pvCurrent = (g(0x08) ?: 0) / 100.0,
            pvPowerRaw = g(0x09)?.toDouble(),
            batteryVMaxToday = ((g(0x0B) ?: 0) / 10.0).takeIf { it > 0 },
            batteryVMinToday = ((g(0x0C) ?: 0) / 10.0).takeIf { it > 0 },
            pvPMaxTodayW = g(0x0F)?.takeIf { it > 0 }?.toDouble(),
            yieldTodayWh = g(0x13)?.toDouble(),
            chargeState = chargeState[(g(0x20) ?: 0) and 0xFF],
        )
    }
}

object RenogyDriver : RenogyDriverBase("renogy", "Renogy (BT-1 / BT-2)", listOf("bt-th", "bt-1", "bt-2", "renogy", "rng"))

object RichSolarDriver : RenogyDriverBase("richsolar", "Rich Solar", listOf("rich", "rs-", "richsolar"))

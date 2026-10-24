package com.offgrid.bmsmonitor.driver.bms

import com.offgrid.bmsmonitor.driver.B
import com.offgrid.bmsmonitor.driver.BleLink
import com.offgrid.bmsmonitor.driver.ByteBuf
import com.offgrid.bmsmonitor.driver.ConnectDriver
import com.offgrid.bmsmonitor.driver.DriverError
import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.CellData
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.Telemetry

/**
 * JBD BMS -- also sold as Jiabaida, Xiaoxiang, Overkill Solar, LLT Power.
 * All the same protocol; one driver covers them.
 *
 * Frame (BLE service FF00, write FF02, notify FF01; some units use FFF0/FFF2/FFF1):
 *   request : DD A5 <reg> 00 <chk_hi> <chk_lo> 77
 *   response: DD <reg> <status> <len> <payload...> <chk_hi> <chk_lo> 77
 *   checksum: 0x10000 - sum(status..payload)  over the length-prefixed body
 */
object JbdDriver : ConnectDriver {
    override val protocol = "jbd"
    override val kind = DeviceKind.BMS
    override val displayName = "JBD / Jiabaida / Xiaoxiang"

    override val serviceUuid = "0000ff00-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000ff02-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000ff01-0000-1000-8000-00805f9b34fb"
    override val altServiceUuid = "0000fff0-0000-1000-8000-00805f9b34fb"
    override val altWriteUuid = "0000fff2-0000-1000-8000-00805f9b34fb"
    override val altNotifyUuid = "0000fff1-0000-1000-8000-00805f9b34fb"

    private val NAME_HINTS = listOf("xiaoxiang", "jbd", "jiabaida", "smart bms", "libatt", "overkill")
    private val PROTECTION = listOf(
        "Cell overvoltage", "Cell undervoltage", "Pack overvoltage", "Pack undervoltage",
        "Charge over-temp", "Charge under-temp", "Discharge over-temp", "Discharge under-temp",
        "Charge overcurrent", "Discharge overcurrent", "Short circuit", "Front-end IC error",
        "Software lock MOS",
    )

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        if (NAME_HINTS.any { low.contains(it) }) return true
        return serviceUuids.map { it.lowercase() }.contains(serviceUuid)
    }

    private fun checksum(body: ByteArray): ByteArray {
        val sum = body.sumOf { it.toInt() and 0xFF }
        val v = (0x10000 - sum) and 0xFFFF
        return B.bytesOf((v ushr 8) and 0xFF, v and 0xFF)
    }

    fun buildRead(register: Int): ByteArray {
        val body = B.bytesOf(register, 0x00)
        return B.bytesOf(0xDD, 0xA5) + body + checksum(body) + B.bytesOf(0x77)
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val start = buf.indexOf(B.bytesOf(0xDD))
            if (start < 0) { buf.clear(); return out }
            if (start > 0) buf.dropFirst(start)
            if (buf.size < 7) return out
            val length = buf.byteAt(3)
            val total = length + 7
            if (buf.size < total) return out
            val frame = buf.consume(total)
            if ((frame[total - 1].toInt() and 0xFF) == 0x77) out.add(frame)
        }
    }

    private fun payload(frame: ByteArray, register: Int): ByteArray {
        if (frame.size < 7 || (frame[1].toInt() and 0xFF) != register)
            throw DriverError("unexpected frame")
        if ((frame[2].toInt() and 0xFF) != 0x00)
            throw DriverError("BMS error status 0x%02x".format(frame[2].toInt() and 0xFF))
        val length = frame[3].toInt() and 0xFF
        val body = frame.copyOfRange(2, 4 + length)
        val chk = checksum(body)
        if (!chk.contentEquals(frame.copyOfRange(4 + length, 6 + length)))
            throw DriverError("checksum mismatch")
        return frame.copyOfRange(4, 4 + length)
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        val basic = payload(link.request(buildRead(0x03)), 0x03)
        var t = parseBasic(basic, info)
        try {
            val cells = payload(link.request(buildRead(0x04)), 0x04)
            val balance = if (basic.size >= 16) B.u32le(basic, 12) else 0L
            val list = (0 until cells.size / 2).map { i ->
                CellData(i + 1, B.u16be(cells, i * 2) / 1000.0, (balance shr i) and 1L == 1L)
            }
            t = t.copy(cells = list, balancing = list.any { it.balancing })
        } catch (_: DriverError) { /* cell detail optional */ }
        return t
    }

    private fun parseBasic(p: ByteArray, info: DeviceInfo): BatteryTelemetry {
        if (p.size < 23) throw DriverError("short basic-info payload")
        val voltage = B.u16be(p, 0) / 100.0
        val current = B.s16be(p, 2) / 100.0
        val remaining = B.u16be(p, 4) / 100.0
        val capacity = B.u16be(p, 6) / 100.0
        val cycles = B.u16be(p, 8)
        val protection = B.u16be(p, 16)
        val sw = p[18].toInt() and 0xFF
        val soc = (p[19].toInt() and 0xFF).toDouble()
        val mos = p[20].toInt() and 0xFF
        val ntc = p[22].toInt() and 0xFF

        val temps = ArrayList<Double>()
        for (i in 0 until ntc) {
            val off = 23 + i * 2
            if (off + 2 <= p.size) temps.add((B.u16be(p, off) - 2731) / 10.0)
        }
        val flags = PROTECTION.filterIndexed { i, _ -> (protection shr i) and 1 == 1 }

        return BatteryTelemetry(
            info = info,
            voltage = voltage, current = current, soc = soc,
            remainingAh = remaining, capacityAh = capacity, cycles = cycles,
            temperatures = temps,
            chargeMos = (mos and 0x01) != 0, dischargeMos = (mos and 0x02) != 0,
            protectionFlags = flags,
            firmware = "${sw shr 4}.${sw and 0x0F}",
        )
    }
}

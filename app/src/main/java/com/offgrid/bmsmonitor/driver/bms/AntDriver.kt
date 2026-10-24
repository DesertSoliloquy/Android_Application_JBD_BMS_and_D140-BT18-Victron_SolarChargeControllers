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
 * ANT BMS (legacy "old" protocol): send DB DB 00 00 00 00, receive a 140-byte
 * reply starting AA 55 AA FF. Newer ANT firmware uses 7E A1 ... 7E framing and
 * will not answer this probe (the card then shows the error text).
 */
object AntDriver : ConnectDriver {
    override val protocol = "ant"
    override val kind = DeviceKind.BMS
    override val displayName = "ANT BMS"

    override val serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private const val REPLY_LEN = 140
    private val PROBE = B.fromHex("dbdb00000000")
    private val NAME_HINTS = listOf("ant-bms", "ant_bms", "antbms", "ant-b")

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        return NAME_HINTS.any { low.contains(it) }
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val head = B.bytesOf(0xAA, 0x55, 0xAA, 0xFF)
        while (true) {
            val start = buf.indexOf(head)
            if (start < 0) { buf.keepLast(4); return out }
            if (start > 0) buf.dropFirst(start)
            if (buf.size < REPLY_LEN) return out
            out.add(buf.consume(REPLY_LEN))
        }
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        val f = link.request(PROBE)
        if (f.size < REPLY_LEN) throw DriverError("short ANT reply")

        val cells = ArrayList<CellData>()
        for (i in 0 until 32) {
            val mv = B.u16be(f, 6 + i * 2)
            if (mv in 1001..4999) cells.add(CellData(i + 1, mv / 1000.0))
        }
        val temps = ArrayList<Double>()
        for (i in 0 until 4) {
            val t = B.s16be(f, 91 + i * 2)
            if (t in -49..149) temps.add(t.toDouble())
        }
        val capacity = B.u32be(f, 75) / 1_000_000.0
        return BatteryTelemetry(
            info = info,
            voltage = B.u16be(f, 4) / 10.0,
            current = B.s32be(f, 70) / 10.0,
            soc = (f[74].toInt() and 0xFF).toDouble(),
            remainingAh = B.u32be(f, 79) / 1_000_000.0,
            capacityAh = if (capacity != 0.0) capacity else null,
            cells = cells,
            temperatures = temps,
            chargeMos = (f[103].toInt() and 0xFF) != 0,
            dischargeMos = (f[104].toInt() and 0xFF) != 0,
            balancing = (f[105].toInt() and 0xFF) != 0,
        )
    }
}

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
 * Daly Smart BMS. Fixed 13-byte frames both directions:
 *   A5 <addr> <cmd> 08 <8 data bytes> <checksum>   checksum = sum(first 12) & 0xFF
 * Current is encoded as (raw + 30000) * 0.1 A.
 */
object DalyDriver : ConnectDriver {
    override val protocol = "daly"
    override val kind = DeviceKind.BMS
    override val displayName = "Daly Smart BMS"

    override val serviceUuid = "0000fff0-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000fff2-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000fff1-0000-1000-8000-00805f9b34fb"

    private const val HOST = 0x40
    private const val CURRENT_OFFSET = 30000
    private val NAME_HINTS = listOf("daly", "dl-", "smartbat")

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        return NAME_HINTS.any { low.contains(it) }
    }

    private fun build(cmd: Int): ByteArray {
        val f = ByteArray(13)
        f[0] = 0xA5.toByte(); f[1] = HOST.toByte(); f[2] = cmd.toByte(); f[3] = 0x08
        var sum = 0
        for (i in 0 until 12) sum += f[i].toInt() and 0xFF
        f[12] = (sum and 0xFF).toByte()
        return f
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val start = buf.indexOf(B.bytesOf(0xA5))
            if (start < 0) { buf.clear(); return out }
            if (start > 0) buf.dropFirst(start)
            if (buf.size < 13) return out
            val frame = buf.consume(13)
            var sum = 0
            for (i in 0 until 12) sum += frame[i].toInt() and 0xFF
            if ((sum and 0xFF) == (frame[12].toInt() and 0xFF)) out.add(frame)
        }
    }

    private suspend fun read(link: BleLink, cmd: Int): ByteArray {
        val frame = link.request(build(cmd))
        if ((frame[2].toInt() and 0xFF) != cmd) throw DriverError("expected cmd 0x%02x".format(cmd))
        return frame.copyOfRange(4, 12)
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        val d = read(link, 0x90)
        val voltage = B.u16be(d, 0) / 10.0
        var current = (B.u16be(d, 4) - CURRENT_OFFSET) / 10.0
        val soc = B.u16be(d, 6) / 10.0

        var temps = emptyList<Double>()
        var chargeMos: Boolean? = null
        var dischargeMos: Boolean? = null
        var cycles: Int? = null

        try {
            val td = read(link, 0x92)
            temps = listOf(((td[0].toInt() and 0xFF) - 40).toDouble(), ((td[2].toInt() and 0xFF) - 40).toDouble())
        } catch (_: DriverError) {}

        try {
            val sd = read(link, 0x93)
            val state = sd[0].toInt() and 0xFF
            chargeMos = (sd[1].toInt() and 0xFF) != 0
            dischargeMos = (sd[2].toInt() and 0xFF) != 0
            val c = (B.u32be(sd, 4) / 1000L).toInt()
            cycles = if (c != 0) c else null
            if (state == 1 && current < 0) current = kotlin.math.abs(current)
        } catch (_: DriverError) {}

        val cells = try { readCells(link) } catch (_: DriverError) { emptyList() }

        return BatteryTelemetry(
            info = info, voltage = voltage, current = current, soc = soc,
            temperatures = temps, chargeMos = chargeMos, dischargeMos = dischargeMos,
            cycles = cycles, cells = cells,
        )
    }

    /** 0x95 answers with N frames, each carrying a page byte + 3 cell voltages. */
    private suspend fun readCells(link: BleLink): List<CellData> {
        link.drain()
        link.writeRaw(build(0x95))
        val cells = HashMap<Int, Double>()
        try {
            while (cells.size < 48) {
                val frame = link.nextFrame(1500)
                if ((frame[2].toInt() and 0xFF) != 0x95) continue
                val d = frame.copyOfRange(4, 12)
                val page = d[0].toInt() and 0xFF
                for (i in 0 until 3) {
                    val mv = B.u16be(d, 1 + i * 2)
                    if (mv == 0) continue
                    cells[(page - 1) * 3 + i + 1] = mv / 1000.0
                }
            }
        } catch (_: DriverError) { /* timeout ends the page stream */ }
        if (cells.isEmpty()) throw DriverError("no cell frames")
        return cells.toSortedMap().map { CellData(it.key, it.value) }
    }
}

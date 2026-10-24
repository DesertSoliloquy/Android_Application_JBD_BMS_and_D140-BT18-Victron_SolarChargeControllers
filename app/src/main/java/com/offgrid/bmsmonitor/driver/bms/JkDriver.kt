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
import kotlinx.coroutines.delay

/**
 * JK-BMS (Jikong). Service FFE0, single characteristic FFE1 for notify + write.
 * 20-byte command, 300-byte response reassembled from 20-byte notifications,
 * starting 55 AA EB 90 <record type> (0x02 = cell info, the one we parse).
 *
 * Byte offsets inside the 0x02 record shift between hardware generations; this
 * map matches JK-B / JK-BD with firmware 11.x. If a pack reports nonsense,
 * adjust OFFSETS -- that's the one place to touch.
 */
object JkDriver : ConnectDriver {
    override val protocol = "jk"
    override val kind = DeviceKind.BMS
    override val displayName = "JK-BMS (Jikong)"

    override val serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    override val responseTimeoutMs = 6000L

    private const val FRAME_LEN = 300
    private val NAME_HINTS = listOf("jk-", "jk_", "jkbms", "jk bms")

    private object O {
        const val cellVoltages = 6      // 32 x uint16 mV LE
        const val packVoltage = 118     // uint32 mV LE
        const val packCurrent = 126     // int32 mA LE, + charging
        const val tempSensor1 = 130     // int16 0.1 C LE
        const val tempSensor2 = 132
        const val soc = 141             // uint8 %
        const val remainingAh = 142     // uint32 mAh LE
        const val capacityAh = 146      // uint32 mAh LE
        const val cycles = 150          // uint32 LE
        const val balanceCurrent = 138  // int16 mA LE
        const val chargeMos = 166
        const val dischargeMos = 167
    }

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        return NAME_HINTS.any { low.contains(it) }
    }

    private fun build(cmd: Int, value: Int = 0): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55; f[2] = 0x90.toByte(); f[3] = 0xEB.toByte()
        f[4] = cmd.toByte(); f[5] = 0x00
        f[6] = (value and 0xFF).toByte()
        f[7] = ((value ushr 8) and 0xFF).toByte()
        f[8] = ((value ushr 16) and 0xFF).toByte()
        f[9] = ((value ushr 24) and 0xFF).toByte()
        var sum = 0
        for (i in 0 until 19) sum += f[i].toInt() and 0xFF
        f[19] = (sum and 0xFF).toByte()
        return f
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val head = B.bytesOf(0x55, 0xAA, 0xEB, 0x90)
        while (true) {
            val start = buf.indexOf(head)
            if (start < 0) { buf.keepLast(4); return out }
            if (start > 0) buf.dropFirst(start)
            if (buf.size < FRAME_LEN) return out
            val frame = buf.consume(FRAME_LEN)
            var sum = 0
            for (i in 0 until FRAME_LEN - 1) sum += frame[i].toInt() and 0xFF
            if ((sum and 0xFF) == (frame[FRAME_LEN - 1].toInt() and 0xFF)) out.add(frame)
        }
    }

    override suspend fun onConnected(link: BleLink) {
        // JK wants a device-info handshake before it will stream cell info.
        try {
            link.writeRaw(build(0x97)); delay(400)
            link.writeRaw(build(0x96)); delay(400)
            link.drain()
        } catch (_: Exception) {}
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        link.drain()
        link.writeRaw(build(0x96))
        val deadline = System.currentTimeMillis() + responseTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = try { link.nextFrame(remaining) } catch (_: DriverError) { break }
            if ((frame[4].toInt() and 0xFF) == 0x02) return parseCellInfo(frame, info)
        }
        throw DriverError("no cell-info record received")
    }

    private fun parseCellInfo(f: ByteArray, info: DeviceInfo): BatteryTelemetry {
        val cells = ArrayList<CellData>()
        for (i in 0 until 32) {
            val mv = B.u16le(f, O.cellVoltages + i * 2)
            if (mv in 1001..4999) cells.add(CellData(i + 1, mv / 1000.0))
        }
        val temps = listOf(B.s16le(f, O.tempSensor1) / 10.0, B.s16le(f, O.tempSensor2) / 10.0)
            .filter { it > -50 && it < 150 }

        return BatteryTelemetry(
            info = info,
            voltage = B.u32le(f, O.packVoltage) / 1000.0,
            current = B.s32le(f, O.packCurrent) / 1000.0,
            soc = (f[O.soc].toInt() and 0xFF).toDouble(),
            remainingAh = B.u32le(f, O.remainingAh) / 1000.0,
            capacityAh = B.u32le(f, O.capacityAh) / 1000.0,
            cycles = B.u32le(f, O.cycles).toInt(),
            cells = cells,
            temperatures = temps,
            chargeMos = (f[O.chargeMos].toInt() and 0xFF) != 0,
            dischargeMos = (f[O.dischargeMos].toInt() and 0xFF) != 0,
            balancing = kotlin.math.abs(B.s16le(f, O.balanceCurrent)) > 5,
        )
    }
}

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sunthysis / DCMONT "D140"-class MPPT controller via its "BT18" BLE module.
 * Reverse-engineered from the SController app's own Bluetooth traffic.
 *
 * Transport: service FFE0, characteristic FFE1 for BOTH write and notify.
 * Framing: MODBUS TCP (MBAP header: transaction id, protocol id 0, length;
 * NO CRC) -- not RTU. One holding register per read (function 0x03, unit 0x01).
 *
 * Confirmed registers: 0x0001 PV V (x0.01), 0x0002 PV I (x0.01), 0x0005 batt V
 * (x0.01), 0x0008 batt A (x0.01, +charge/-load), 0x000A SoC %, 0x0006 temp,
 * 0x000B charge state (0 idle, 2/3/4 stages), 0x0036 load output (0 on, 8 off).
 */
object D140Driver : ConnectDriver {
    override val protocol = "d140"
    override val kind = DeviceKind.SOLAR
    override val displayName = "D140 (Sunthysis / BT18)"

    override val serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb"
    override val writeUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    override val notifyUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private const val COMPANY_ID = 0x050E
    private const val UNIT = 0x01
    private val tid = AtomicInteger(0)

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        val low = (name ?: "").lowercase()
        if (low.contains("bt18")) return true
        val uuids = serviceUuids.map { it.lowercase() }
        return manufacturerData.containsKey(COMPANY_ID) && uuids.contains(serviceUuid)
    }

    private fun readRequest(transactionId: Int, reg: Int): ByteArray = B.bytesOf(
        (transactionId ushr 8) and 0xFF, transactionId and 0xFF,  // transaction id
        0x00, 0x00,                                               // protocol id
        0x00, 0x06,                                               // length
        UNIT, 0x03,                                               // unit, function
        (reg ushr 8) and 0xFF, reg and 0xFF,                      // register
        0x00, 0x01,                                               // quantity = 1
    )

    /**
     * Load-output control -- the one place this app WRITES to a device, and
     * only to the D140's own load switch (register 0x0036), never to a BMS.
     * Modbus function 0x10 (write single register). Polarity confirmed against
     * the physical load: register value 0x0000 = ON, 0x0008 = OFF.
     */
    private fun loadCommand(on: Boolean): ByteArray {
        val transactionId = tid.incrementAndGet() and 0xFFFF
        val value = if (on) 0x0000 else 0x0008
        return B.bytesOf(
            (transactionId ushr 8) and 0xFF, transactionId and 0xFF, // transaction id
            0x00, 0x00,                                              // protocol id
            0x00, 0x09,                                              // length
            UNIT, 0x10,                                              // unit, function (write)
            0x00, 0x36,                                              // register 0x0036
            0x00, 0x01,                                              // quantity = 1
            0x02,                                                    // byte count
            (value ushr 8) and 0xFF, value and 0xFF,                 // value
        )
    }

    suspend fun setLoad(link: BleLink, on: Boolean) {
        link.writeRaw(loadCommand(on))
    }

    override fun extractFrames(buf: ByteBuf): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (buf.size >= 6) {
            val length = (buf.byteAt(4) shl 8) or buf.byteAt(5)
            val total = 6 + length
            if (buf.size < total) break
            out.add(buf.consume(total))
        }
        return out
    }

    private suspend fun readReg(link: BleLink, reg: Int): Int? {
        val want = tid.incrementAndGet() and 0xFFFF
        val frame = link.request(readRequest(want, reg))
        if (frame.size < 9 || (frame[7].toInt() and 0xFF) != 0x03) throw DriverError("bad Modbus-TCP reply")
        val bc = frame[8].toInt() and 0xFF
        if (bc < 2 || frame.size < 9 + bc) return null
        return B.u16be(frame, 9)
    }

    override suspend fun poll(info: DeviceInfo, link: BleLink): Telemetry {
        fun scaled(v: Int?, div: Double) = v?.let { it / div }

        val battV = scaled(readReg(link, 0x0005), 100.0)
        val pvV = scaled(readReg(link, 0x0001), 100.0)
        val pvA = scaled(readReg(link, 0x0002), 100.0)
        val battA = scaled(readReg(link, 0x0008), 100.0)
        val soc = readReg(link, 0x000A)?.toDouble()
        val temp = readReg(link, 0x0006)?.toDouble()
        val state = readReg(link, 0x000B)
        val load = readReg(link, 0x0036)

        return SolarTelemetry(
            info = info,
            batteryVoltage = battV,
            pvVoltage = pvV,
            pvCurrent = pvA,
            batteryCurrent = battA,
            soc = soc,
            controllerTemp = temp,
            chargeState = when (state) {
                null -> null
                0 -> "Idle"
                else -> "Charging"
            },
            loadOn = load?.let { it == 0 },   // 0 = on, 8 = off (confirmed on hardware)
        )
    }
}

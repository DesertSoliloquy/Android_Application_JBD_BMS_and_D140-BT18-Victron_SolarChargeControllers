package com.offgrid.bmsmonitor.driver.solar

import com.offgrid.bmsmonitor.driver.B
import com.offgrid.bmsmonitor.driver.BroadcastDriver
import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind
import com.offgrid.bmsmonitor.model.SolarTelemetry
import com.offgrid.bmsmonitor.model.Telemetry
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Victron SmartSolar via "Instant Readout" BLE advertisements (company 0x02E1).
 * No connection, no PIN -- the advertisement is AES-128-CTR encrypted with a
 * per-device key copied from VictronConnect (Product info -> Encryption data).
 *
 * Extra-Manufacturer-Data layout (this is the CORRECTED one -- the original
 * desktop build read the record type from the wrong byte):
 *   [0]    0x10  Instant Readout prefix
 *   [1:3]  model id (uint16 LE)
 *   [3]    read-out type
 *   [4]    record type: 0x01 = solar charger
 *   [5:7]  nonce / IV (uint16 LE) -> first 2 bytes of the AES-CTR IV
 *   [7]    first byte of the key (check byte)
 *   [8:]   ciphertext
 *
 * Decrypted solar-charger payload: state, error, batt V (x0.01), batt I (x0.1),
 * yield today (x0.01 kWh), PV power (W).
 */
object VictronDriver : BroadcastDriver {
    override val protocol = "victron"
    override val kind = DeviceKind.SOLAR
    override val displayName = "Victron (Instant Readout)"
    override val needsSecret = true
    override val secretLabel = "Encryption key (32 hex chars, from VictronConnect)"

    const val COMPANY_ID = 0x02E1

    private val STATE = mapOf(
        0 to "Off", 1 to "Low power", 2 to "Fault", 3 to "Bulk", 4 to "Absorption",
        5 to "Float", 6 to "Storage", 7 to "Equalize", 245 to "Wake-up",
        247 to "Auto equalize", 252 to "External control",
    )

    override fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean {
        if (manufacturerData.containsKey(COMPANY_ID)) return true
        val low = (name ?: "").lowercase()
        return low.startsWith("smartsolar") || low.startsWith("bluesolar")
    }

    override fun parse(info: DeviceInfo, manufacturerData: Map<Int, ByteArray>, secret: String?): Telemetry? {
        val raw = manufacturerData[COMPANY_ID] ?: return null
        if (raw.isEmpty() || (raw[0].toInt() and 0xFF) != 0x10) return null   // not an Instant Readout frame
        if (raw.size < 9) return null
        val recordType = raw[4].toInt() and 0xFF
        if (recordType != 0x01)
            return SolarTelemetry(info, error = "record type 0x%02x is not a solar charger".format(recordType))
        if (secret.isNullOrBlank())
            return SolarTelemetry(info, error = "Instant Readout key not set")

        return try {
            val d = decrypt(raw, secret)
            parseSolar(info, d)
        } catch (e: Exception) {
            SolarTelemetry(info, error = e.message ?: "decrypt failed")
        }
    }

    private fun decrypt(raw: ByteArray, secret: String): ByteArray {
        val key = B.fromHex(secret.trim())
        if (key.size != 16) throw IllegalArgumentException("key must be 32 hex characters")
        if ((key[0].toInt() and 0xFF) != (raw[7].toInt() and 0xFF))
            throw IllegalArgumentException("key does not match this device")
        val iv = raw.copyOfRange(5, 7) + ByteArray(14)
        val ct = raw.copyOfRange(8, raw.size)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun parseSolar(info: DeviceInfo, d: ByteArray): SolarTelemetry {
        if (d.size < 10) throw IllegalArgumentException("short solar record")
        val state = d[0].toInt() and 0xFF
        val err = d[1].toInt() and 0xFF
        val batV = B.s16le(d, 2) / 100.0
        val batI = B.s16le(d, 4) / 10.0
        val yieldWh = B.u16le(d, 6) * 10.0
        val pvW = B.u16le(d, 8).toDouble()
        return SolarTelemetry(
            info = info,
            pvPowerRaw = pvW,
            batteryVoltage = batV,
            batteryCurrent = batI,
            yieldTodayWh = yieldWh,
            chargeState = STATE[state] ?: "state $state",
            error = if (err == 0) null else "charger error $err",
        )
    }
}

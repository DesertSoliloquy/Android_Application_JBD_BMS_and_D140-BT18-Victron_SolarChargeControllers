package com.offgrid.bmsmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/** A single advertisement, normalized for the registry's matcher. */
data class ScanHit(
    val device: BluetoothDevice,
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerData: Map<Int, ByteArray>,
)

/** Thin wrapper over BluetoothLeScanner that normalizes each result. */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var callback: ScanCallback? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun start(onHit: (ScanHit) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result, onHit)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it, onHit) }
            }
        }
        callback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
    }

    private fun handle(result: ScanResult, onHit: (ScanHit) -> Unit) {
        val record = result.scanRecord
        val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        val mfg = HashMap<Int, ByteArray>()
        val msd = record?.manufacturerSpecificData
        if (msd != null) {
            for (i in 0 until msd.size()) {
                mfg[msd.keyAt(i)] = msd.valueAt(i)
            }
        }
        onHit(
            ScanHit(
                device = result.device,
                address = result.device.address,
                name = record?.deviceName ?: result.device.name,
                rssi = result.rssi,
                serviceUuids = uuids,
                manufacturerData = mfg,
            )
        )
    }

    fun stop() {
        val cb = callback ?: return
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        callback = null
    }
}

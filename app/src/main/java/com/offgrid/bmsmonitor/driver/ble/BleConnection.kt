package com.offgrid.bmsmonitor.driver.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.offgrid.bmsmonitor.driver.BleLink
import com.offgrid.bmsmonitor.driver.ByteBuf
import com.offgrid.bmsmonitor.driver.ConnectDriver
import com.offgrid.bmsmonitor.driver.DriverError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * One GATT connection to one device, exposed to drivers as a [BleLink].
 * Callback-based Android BLE is bridged to coroutines via CompletableDeferred
 * (one-shot operations) and a Channel (the stream of reassembled frames).
 *
 * Only one connection should be open at a time across the app; the Poller
 * enforces that. Within a connection, requests are serialized by a Mutex.
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val driver: ConnectDriver,
) : BleLink {

    override val responseTimeoutMs: Long get() = driver.responseTimeoutMs

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Chosen from the write characteristic's actual properties (see bind).
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    // Diagnostics surfaced in the timeout error so a one-way link is debuggable.
    @Volatile private var rxBytes = 0
    @Volatile private var lastWriteOk: Boolean? = null
    @Volatile private var notifyState = "none"

    private val buf = ByteBuf()
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val reqMutex = Mutex()

    private var connectResult: CompletableDeferred<Boolean>? = null
    private var servicesResult: CompletableDeferred<Boolean>? = null
    private var writeResult: CompletableDeferred<Boolean>? = null
    private var descResult: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectResult?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectResult?.complete(false)
                servicesResult?.complete(false)
                frames.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            ingest(value)
        }

        // Pre-33
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            ingest(c.value ?: ByteArray(0))
        }
    }

    private fun ingest(value: ByteArray) {
        if (value.isEmpty()) return
        rxBytes += value.size
        buf.append(value)
        for (f in driver.extractFrames(buf)) frames.trySend(f)
    }

    /** Connect, discover services, wire up the data characteristics. */
    suspend fun open() {
        connectResult = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val connected = try {
            withTimeout(15_000) { connectResult!!.await() }
        } catch (e: TimeoutCancellationException) {
            throw DriverError("connect timed out")
        }
        if (!connected) throw DriverError("connection failed")

        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }

        servicesResult = CompletableDeferred()
        if (gatt?.discoverServices() != true) throw DriverError("discoverServices failed to start")
        val ok = try {
            withTimeout(12_000) { servicesResult!!.await() }
        } catch (e: TimeoutCancellationException) {
            throw DriverError("service discovery timed out")
        }
        if (!ok) throw DriverError("service discovery failed")

        bindCharacteristics()
        enableNotifications()
        delay(250) // let the CCCD settle before the first command on strict stacks
    }

    private fun bindCharacteristics() {
        val g = gatt ?: throw DriverError("not connected")
        val candidates = buildList {
            add(Triple(driver.serviceUuid, driver.writeUuid, driver.notifyUuid))
            if (driver.altServiceUuid != null || driver.altWriteUuid != null) {
                add(
                    Triple(
                        driver.altServiceUuid ?: driver.serviceUuid,
                        driver.altWriteUuid ?: driver.writeUuid,
                        driver.altNotifyUuid ?: driver.notifyUuid,
                    )
                )
            }
        }
        for ((svc, wr, nf) in candidates) {
            val service = g.getService(UUID.fromString(svc)) ?: continue
            val w = service.getCharacteristic(UUID.fromString(wr))
            val n = service.getCharacteristic(UUID.fromString(nf))
            if (w != null && n != null) {
                writeChar = w
                notifyChar = n
                // Use write-without-response only when the characteristic offers it;
                // otherwise a strict Android stack silently drops the write.
                writeType = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                return
            }
        }
        throw DriverError("expected characteristics not found on device")
    }

    private suspend fun enableNotifications() {
        val g = gatt ?: throw DriverError("not connected")
        val n = notifyChar ?: throw DriverError("no notify characteristic")
        g.setCharacteristicNotification(n, true)
        val cccd = n.getDescriptor(CCCD_UUID)
        if (cccd == null) { notifyState = "no-cccd"; return }

        // Pick notify vs indicate based on what the characteristic supports.
        val value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descResult = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        notifyState = try {
            if (withTimeout(5_000) { descResult!!.await() }) "on" else "cccd-failed"
        } catch (e: TimeoutCancellationException) {
            "cccd-timeout"
        }
    }

    private suspend fun doWrite(payload: ByteArray) {
        val g = gatt ?: throw DriverError("not connected")
        val w = writeChar ?: throw DriverError("no write characteristic")
        writeResult = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(w, payload, writeType)
        } else {
            @Suppress("DEPRECATION")
            w.writeType = writeType
            @Suppress("DEPRECATION")
            w.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(w)
        }
        lastWriteOk = try {
            withTimeout(3_000) { writeResult!!.await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }

    override suspend fun request(payload: ByteArray, responseRequired: Boolean): ByteArray =
        reqMutex.withLock {
            drainInternal()
            doWrite(payload)
            if (!responseRequired) return@withLock ByteArray(0)
            nextFrame(responseTimeoutMs)
        }

    override suspend fun writeRaw(payload: ByteArray) = reqMutex.withLock { doWrite(payload) }

    override suspend fun nextFrame(timeoutMs: Long): ByteArray =
        try {
            withTimeout(timeoutMs) { frames.receive() }
        } catch (e: TimeoutCancellationException) {
            throw DriverError(
                "no response in ${timeoutMs}ms (wrote=${wroteStr()}, notify=$notifyState, rx=$rxBytes bytes)"
            )
        }

    private fun wroteStr() = when (lastWriteOk) {
        true -> "ok"
        false -> "failed"
        else -> "unacked"
    }

    override suspend fun drain() = drainInternal()

    private fun drainInternal() {
        buf.clear()
        while (true) {
            val r = frames.tryReceive()
            if (!r.isSuccess) break
        }
    }

    fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        frames.close()
    }
}

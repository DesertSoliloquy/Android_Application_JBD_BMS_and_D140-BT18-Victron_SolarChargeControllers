package com.offgrid.bmsmonitor.driver

import com.offgrid.bmsmonitor.model.DeviceInfo
import com.offgrid.bmsmonitor.model.DeviceKind

/**
 * A growable byte buffer that incoming BLE notifications append to, and that
 * each driver's frame extractor consumes complete frames from. Mirrors the
 * `bytearray` buffer in the Python drivers' `_extract_frames`.
 */
class ByteBuf {
    private var data = ByteArray(0)

    val size: Int get() = data.size

    @Synchronized fun append(bytes: ByteArray) { data += bytes }
    @Synchronized fun clear() { data = ByteArray(0) }
    @Synchronized fun snapshot(): ByteArray = data.copyOf()

    /** Remove and return the first n bytes. */
    @Synchronized fun consume(n: Int): ByteArray {
        val taken = data.copyOfRange(0, n)
        data = data.copyOfRange(n, data.size)
        return taken
    }

    /** Drop the first n bytes without returning them. */
    @Synchronized fun dropFirst(n: Int) {
        data = if (n >= data.size) ByteArray(0) else data.copyOfRange(n, data.size)
    }

    /** Keep only the last n bytes (used to cap runaway buffers). */
    @Synchronized fun keepLast(n: Int) {
        if (data.size > n) data = data.copyOfRange(data.size - n, data.size)
    }

    /** Index of the first occurrence of [pattern], or -1. */
    @Synchronized fun indexOf(pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    @Synchronized fun byteAt(i: Int): Int = data[i].toInt() and 0xFF
}

/**
 * What a connection-based driver uses to talk to a device: write a command,
 * await a reassembled frame. Implemented by the Android GATT wrapper.
 */
interface BleLink {
    /** Write [payload] to the write characteristic and await one frame back. */
    suspend fun request(payload: ByteArray, responseRequired: Boolean = true): ByteArray
    /** Write without waiting for a reply frame. */
    suspend fun writeRaw(payload: ByteArray)
    /** Await the next reassembled frame, or throw on timeout. */
    suspend fun nextFrame(timeoutMs: Long): ByteArray
    /** Discard any buffered/queued frames before a fresh request. */
    suspend fun drain()
    val responseTimeoutMs: Long
}

class DriverError(message: String) : Exception(message)

interface DeviceDriver {
    val protocol: String
    val kind: DeviceKind
    val displayName: String
    val needsSecret: Boolean get() = false
    val secretLabel: String get() = "PIN"

    /** Decide, from a scan advertisement, whether this driver owns the device. */
    fun matches(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): Boolean
}

/** A driver that connects over GATT and polls (all the BMS plus Renogy / D140). */
interface ConnectDriver : DeviceDriver {
    val serviceUuid: String
    val writeUuid: String
    val notifyUuid: String
    val altServiceUuid: String? get() = null
    val altWriteUuid: String? get() = null
    val altNotifyUuid: String? get() = null
    val responseTimeoutMs: Long get() = 4000L

    /** Pull complete frames out of the running buffer. */
    fun extractFrames(buf: ByteBuf): List<ByteArray>

    /** Optional handshake right after the link opens (e.g. JK device-info). */
    suspend fun onConnected(link: BleLink) {}

    /** Perform one read cycle and return telemetry. */
    suspend fun poll(info: DeviceInfo, link: BleLink): com.offgrid.bmsmonitor.model.Telemetry
}

/** A driver that reads passive advertisements, never connecting (Victron). */
interface BroadcastDriver : DeviceDriver {
    /** Parse one advertisement; return null if this frame carries no reading. */
    fun parse(
        info: DeviceInfo,
        manufacturerData: Map<Int, ByteArray>,
        secret: String?,
    ): com.offgrid.bmsmonitor.model.Telemetry?
}

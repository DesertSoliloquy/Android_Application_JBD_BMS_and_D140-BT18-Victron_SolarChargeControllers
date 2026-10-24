package com.offgrid.bmsmonitor.data

import com.offgrid.bmsmonitor.model.BatteryTelemetry
import com.offgrid.bmsmonitor.model.SolarTelemetry

/**
 * Lightweight in-memory history: a capped ring of recent samples per device,
 * enough to draw a live trend. Persistent on-disk history (a daily-rollup
 * database) is a planned addition -- see README "Known limitations".
 */
class History(private val capacity: Int = 720) {

    data class SolarSample(val t: Long, val pvW: Double, val battV: Double, val soc: Double?)
    data class BatterySample(val t: Long, val voltage: Double, val current: Double, val soc: Double?)

    private val solar = HashMap<String, ArrayDeque<SolarSample>>()
    private val battery = HashMap<String, ArrayDeque<BatterySample>>()

    @Synchronized
    fun addSolar(t: SolarTelemetry) {
        if (t.error != null) return
        val q = solar.getOrPut(t.info.key) { ArrayDeque() }
        q.addLast(SolarSample(t.timestampMs, t.pvPower ?: 0.0, t.batteryVoltage ?: 0.0, t.soc))
        while (q.size > capacity) q.removeFirst()
    }

    @Synchronized
    fun addBattery(t: BatteryTelemetry) {
        if (t.error != null) return
        val q = battery.getOrPut(t.info.key) { ArrayDeque() }
        q.addLast(BatterySample(t.timestampMs, t.voltage ?: 0.0, t.current ?: 0.0, t.soc))
        while (q.size > capacity) q.removeFirst()
    }

    @Synchronized fun recentSolar(key: String): List<SolarSample> = solar[key]?.toList() ?: emptyList()
    @Synchronized fun recentBattery(key: String): List<BatterySample> = battery[key]?.toList() ?: emptyList()
}

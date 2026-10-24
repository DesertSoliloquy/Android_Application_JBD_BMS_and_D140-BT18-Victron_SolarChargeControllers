package com.offgrid.bmsmonitor.driver

import com.offgrid.bmsmonitor.driver.bms.AntDriver
import com.offgrid.bmsmonitor.driver.bms.DalyDriver
import com.offgrid.bmsmonitor.driver.bms.JbdDriver
import com.offgrid.bmsmonitor.driver.bms.JkDriver
import com.offgrid.bmsmonitor.driver.solar.D140Driver
import com.offgrid.bmsmonitor.driver.solar.RenogyDriver
import com.offgrid.bmsmonitor.driver.solar.RichSolarDriver
import com.offgrid.bmsmonitor.driver.solar.VictronDriver

/**
 * Maps a BLE advertisement onto a driver. Add hardware by writing a driver and
 * appending it here -- nothing else changes.
 *
 * Order matters only where fingerprints overlap: the connect-based BMS drivers
 * that share service FFE0 (JK, ANT) are matched by advertised name, and the
 * D140 (also FFE0) requires its own name/manufacturer, so they don't collide.
 */
object DriverRegistry {
    val connectDrivers: List<ConnectDriver> = listOf(
        JbdDriver, JkDriver, DalyDriver, AntDriver,
        RenogyDriver, RichSolarDriver, D140Driver,
    )
    val broadcastDrivers: List<BroadcastDriver> = listOf(
        VictronDriver,
    )

    val all: List<DeviceDriver> = connectDrivers + broadcastDrivers

    fun identify(name: String?, serviceUuids: List<String>, manufacturerData: Map<Int, ByteArray>): DeviceDriver? {
        // Broadcast first: Victron is passive and unambiguous (company id).
        for (d in broadcastDrivers) {
            runCatching { if (d.matches(name, serviceUuids, manufacturerData)) return d }
        }
        for (d in connectDrivers) {
            runCatching { if (d.matches(name, serviceUuids, manufacturerData)) return d }
        }
        return null
    }

    fun byProtocol(protocol: String): DeviceDriver? = all.firstOrNull { it.protocol == protocol }
}

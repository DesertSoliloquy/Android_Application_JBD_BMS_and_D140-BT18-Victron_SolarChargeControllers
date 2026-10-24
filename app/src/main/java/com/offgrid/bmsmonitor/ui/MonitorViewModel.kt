package com.offgrid.bmsmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offgrid.bmsmonitor.BmsApp
import com.offgrid.bmsmonitor.ble.hasBlePermissions
import com.offgrid.bmsmonitor.data.History
import com.offgrid.bmsmonitor.service.MonitorService

/** Bridges the Poller / stores to Compose and owns the monitoring lifecycle. */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as BmsApp

    val snapshot = appRef.poller.snapshot
    val discovered = appRef.poller.discovered
    val status = appRef.poller.status
    val settings = appRef.settings.state
    val credentialKeys = appRef.credentials.keys
    val pendingLoad = appRef.poller.pendingLoad

    init {
        // Starts in whatever mode settings say (simulation by default, which
        // needs no permissions and no foreground service).
        appRef.poller.start()
    }

    fun onPermissionsResult(granted: Boolean) {
        if (granted) {
            appRef.poller.restart()
            runCatching { MonitorService.start(getApplication()) }
        }
    }

    fun setSimulation(on: Boolean) {
        appRef.settings.setSimulate(on)
        appRef.poller.restart()
        if (!on && hasBlePermissions(getApplication())) {
            runCatching { MonitorService.start(getApplication()) }
        }
    }

    fun setPollInterval(seconds: Int) = appRef.settings.setPollInterval(seconds)

    fun hasPermissions(): Boolean = hasBlePermissions(getApplication())

    fun hasKey(deviceKey: String): Boolean = appRef.credentials.has(deviceKey)

    fun setVictronKey(deviceKey: String, key: String) {
        appRef.credentials.set(deviceKey, key.trim())
        appRef.poller.revalidate(deviceKey)   // check the key immediately
    }

    fun forgetKey(deviceKey: String) = appRef.credentials.remove(deviceKey)

    /** Turn the D140 load output on/off (the only write the app performs). */
    fun toggleLoad(deviceKey: String, on: Boolean) = appRef.poller.requestLoad(deviceKey, on)

    fun recentSolar(key: String): List<History.SolarSample> = appRef.history.recentSolar(key)
    fun recentBattery(key: String): List<History.BatterySample> = appRef.history.recentBattery(key)
}

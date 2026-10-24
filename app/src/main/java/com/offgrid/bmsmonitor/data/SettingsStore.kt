package com.offgrid.bmsmonitor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsData(
    val pollIntervalS: Int = 10,
    val simulate: Boolean = true,
    val scanDurationMs: Long = 8_000,
)

/** Simple SharedPreferences-backed settings with a reactive snapshot. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsData> = _state

    private fun load() = SettingsData(
        pollIntervalS = prefs.getInt("pollIntervalS", 10),
        simulate = prefs.getBoolean("simulate", true),
        scanDurationMs = prefs.getLong("scanDurationMs", 8_000),
    )

    val current: SettingsData get() = _state.value

    fun setPollInterval(seconds: Int) {
        prefs.edit().putInt("pollIntervalS", seconds).apply()
        _state.value = _state.value.copy(pollIntervalS = seconds)
    }

    fun setSimulate(on: Boolean) {
        prefs.edit().putBoolean("simulate", on).apply()
        _state.value = _state.value.copy(simulate = on)
    }
}

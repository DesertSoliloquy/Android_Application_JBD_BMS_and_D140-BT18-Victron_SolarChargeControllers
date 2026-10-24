package com.offgrid.bmsmonitor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-device secrets -- currently the Victron Instant Readout key, one per
 * controller, keyed by device key ("ble:MAC"). Entered once, only re-entered
 * if it changes. Stored in SharedPreferences; these keys only decrypt the
 * user's own public broadcast, so they are low-sensitivity.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(prefs.all.keys.toSet())
    val keys: StateFlow<Set<String>> = _keys

    fun get(deviceKey: String): String? = prefs.getString(deviceKey, null)

    fun has(deviceKey: String): Boolean = !get(deviceKey).isNullOrBlank()

    fun set(deviceKey: String, value: String) {
        prefs.edit().putString(deviceKey, value).apply()
        _keys.value = prefs.all.keys.toSet()
    }

    fun remove(deviceKey: String) {
        prefs.edit().remove(deviceKey).apply()
        _keys.value = prefs.all.keys.toSet()
    }
}

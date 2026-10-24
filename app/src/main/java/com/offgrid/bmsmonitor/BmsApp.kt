package com.offgrid.bmsmonitor

import android.app.Application
import com.offgrid.bmsmonitor.ble.Poller
import com.offgrid.bmsmonitor.data.CredentialStore
import com.offgrid.bmsmonitor.data.History
import com.offgrid.bmsmonitor.data.SettingsStore

/**
 * Process-wide singletons. Kept deliberately simple (no DI framework) so the
 * project builds with nothing but AndroidX + Compose.
 */
class BmsApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var history: History
        private set
    lateinit var poller: Poller
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        credentials = CredentialStore(this)
        history = History()
        poller = Poller(this, settings, credentials, history)
    }

    companion object {
        lateinit var instance: BmsApp
            private set
    }
}

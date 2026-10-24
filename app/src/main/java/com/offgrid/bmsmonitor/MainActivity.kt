package com.offgrid.bmsmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offgrid.bmsmonitor.ble.rememberPermissionLauncher
import com.offgrid.bmsmonitor.ui.MonitorViewModel
import com.offgrid.bmsmonitor.ui.screens.MainScreen
import com.offgrid.bmsmonitor.ui.theme.BmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BmsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MonitorViewModel = viewModel()
                    // Ask for BLE permissions; once granted, start scanning.
                    val requestPermissions = rememberPermissionLauncher { granted ->
                        vm.onPermissionsResult(granted)
                    }
                    MainScreen(vm = vm, onRequestPermissions = requestPermissions)
                }
            }
        }
    }
}

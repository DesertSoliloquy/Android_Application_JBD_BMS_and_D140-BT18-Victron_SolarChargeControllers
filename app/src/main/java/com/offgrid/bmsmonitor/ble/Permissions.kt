package com.offgrid.bmsmonitor.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/** Permissions BLE needs, which differ sharply across Android versions. */
fun requiredBlePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    else -> arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
}

/** Notification permission for the foreground-service status (Android 13+). */
fun notificationPermission(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Returns a lambda that, when invoked, launches the runtime permission prompt
 * for all BLE (and notification) permissions, reporting whether BLE was granted.
 */
@Composable
fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val bleGranted = requiredBlePermissions().all { result[it] == true }
        onResult(bleGranted)
    }
    return { launcher.launch(requiredBlePermissions() + notificationPermission()) }
}

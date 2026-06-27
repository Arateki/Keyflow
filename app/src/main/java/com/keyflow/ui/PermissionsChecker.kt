package com.keyflow.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.keyflow.ime.InvisibleKeyboardService
import com.keyflow.overlay.OverlayPermission

data class PermissionStatus(
    val microphoneGranted: Boolean,
    val overlayGranted: Boolean,
    val keyboardEnabled: Boolean,
    val notificationsGranted: Boolean,
)

class PermissionsChecker {
    fun snapshot(context: Context): PermissionStatus = PermissionStatus(
        microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        overlayGranted = OverlayPermission.canDrawOverlays(context),
        keyboardEnabled = isKeyboardEnabled(context),
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        },
    )

    private fun isKeyboardEnabled(context: Context): Boolean {
        val enabledMethods = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            ).orEmpty()
        } catch (e: SecurityException) {
            return false
        }
        val component = ComponentName(context, InvisibleKeyboardService::class.java)
        val flattened = component.flattenToString()
        val shortFlattened = component.flattenToShortString()

        return enabledMethods
            .split(':')
            .any { it == flattened || it == shortFlattened || it.contains("InvisibleKeyboardService") }
    }
}

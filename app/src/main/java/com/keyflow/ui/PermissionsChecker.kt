package com.keyflow.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.keyflow.ime.KeyFlowKeyboardService
import com.keyflow.overlay.OverlayPermission

data class PermissionStatus(
    val microphoneGranted: Boolean,
    val overlayGranted: Boolean,
    val keyboardEnabled: Boolean,
    val notificationsGranted: Boolean,
    // KeyFlow é o teclado atualmente selecionado no sistema (DEFAULT_INPUT_METHOD), não só habilitado.
    val keyboardActive: Boolean = false,
)

class PermissionsChecker {
    fun snapshot(context: Context): PermissionStatus = PermissionStatus(
        microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        overlayGranted = OverlayPermission.canDrawOverlays(context),
        keyboardEnabled = isKeyboardEnabled(context),
        keyboardActive = isKeyboardActive(context),
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
        val component = ComponentName(context, KeyFlowKeyboardService::class.java)
        val flattened = component.flattenToString()
        val shortFlattened = component.flattenToShortString()

        return enabledMethods
            .split(':')
            .any { it == flattened || it == shortFlattened || it.contains("KeyFlowKeyboardService") }
    }

    /** KeyFlow é o teclado atualmente selecionado (DEFAULT_INPUT_METHOD)? */
    private fun isKeyboardActive(context: Context): Boolean {
        val current = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            ).orEmpty()
        } catch (e: SecurityException) {
            return false
        }
        val component = ComponentName(context, KeyFlowKeyboardService::class.java)
        return current == component.flattenToString() ||
            current == component.flattenToShortString() ||
            current.contains("KeyFlowKeyboardService")
    }
}

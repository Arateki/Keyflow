package com.keyflow.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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

    /**
     * Lê pelo [InputMethodManager], NÃO por `Settings.Secure.ENABLED_INPUT_METHODS`: no Android 14+ essa
     * chave é bloqueada para apps com `targetSdk >= 34` —
     * `SecurityException: Settings key: <enabled_input_methods> is only readable to apps with
     * targetSdkVersion lower than or equal to: 33`. Como a leitura antiga engolia a exceção e devolvia
     * `false`, o app dizia "teclado não habilitado" mesmo com o KeyFlow habilitado, em TODO aparelho com
     * Android 14+ (achado 2026-09-08; passava despercebido porque o A32 do Yan é Android 13).
     * `getEnabledInputMethodList()` é API pública e não tem essa restrição.
     */
    private fun isKeyboardEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val component = ComponentName(context, KeyFlowKeyboardService::class.java)
        return runCatching { imm.enabledInputMethodList }
            .getOrDefault(emptyList())
            .any { it.component == component || it.packageName == context.packageName }
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

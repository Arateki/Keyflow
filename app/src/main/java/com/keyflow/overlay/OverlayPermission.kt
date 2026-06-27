package com.keyflow.overlay

import android.content.Context
import android.os.Build
import android.provider.Settings

object OverlayPermission {
    fun canDrawOverlays(context: Context): Boolean =
        isGranted(Build.VERSION.SDK_INT) {
            Settings.canDrawOverlays(context)
        }

    fun isGranted(sdkInt: Int, canDrawOverlays: () -> Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.M || canDrawOverlays()
}

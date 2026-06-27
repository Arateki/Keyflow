package com.keyflow.overlay

import android.os.Build
import android.view.Gravity
import android.view.WindowManager

data class OverlayWindowConfig(
    val type: Int,
    val flags: Int,
    val gravity: Int = Gravity.TOP or Gravity.START,
) {
    companion object {
        @Suppress("DEPRECATION")
        fun forSdk(sdkInt: Int = Build.VERSION.SDK_INT): OverlayWindowConfig {
            val type = if (sdkInt >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            return OverlayWindowConfig(
                type = type,
                // NOT_TOUCH_MODAL: toques fora do botão passam para a janela de baixo
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            )
        }
    }
}

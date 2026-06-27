package com.keyflow.overlay

import android.os.Build
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayWindowConfigTest {

    @Test
    fun `uses application overlay type on api 26 and newer`() {
        val config = OverlayWindowConfig.forSdk(Build.VERSION_CODES.O)

        assertThat(config.type).isEqualTo(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        assertThat(config.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            .isEqualTo(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `uses phone type before api 26`() {
        val config = OverlayWindowConfig.forSdk(Build.VERSION_CODES.N_MR1)

        assertThat(config.type).isEqualTo(WindowManager.LayoutParams.TYPE_PHONE)
        assertThat(config.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            .isEqualTo(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}

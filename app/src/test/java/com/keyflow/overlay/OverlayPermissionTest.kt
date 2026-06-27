package com.keyflow.overlay

import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayPermissionTest {

    @Test
    fun `pre marshmallow devices do not need explicit overlay grant`() {
        assertThat(OverlayPermission.isGranted(Build.VERSION_CODES.LOLLIPOP) { false }).isTrue()
    }

    @Test
    fun `marshmallow and newer devices require overlay grant`() {
        assertThat(OverlayPermission.isGranted(Build.VERSION_CODES.M) { false }).isFalse()
        assertThat(OverlayPermission.isGranted(Build.VERSION_CODES.M) { true }).isTrue()
    }
}

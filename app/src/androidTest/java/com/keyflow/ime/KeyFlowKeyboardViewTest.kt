package com.keyflow.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardViewTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // Cobre a barra ANTIGA (mic/⚙/colar como TextViews), que deixou de ser o padrão em 2026-09-07
    // (ROL-02) — daí o `useBars = false` explícito. Migra para a BarView quando a antiga sair.
    private fun view(frozenSeconds: Int? = null) = KeyFlowKeyboardView(
        context = context,
        onKey = {},
        onMic = {},
        onHide = {},
        frozenSeconds = frozenSeconds,
        useBars = false,
    )

    @Test
    fun shrinkAndExpandSwapLayouts() {
        val view = view()
        assertTrue(view.expandedForTest) // começa expandido

        view.setExpanded(false)
        assertFalse(view.expandedForTest)

        view.setExpanded(true)
        assertTrue(view.expandedForTest)
    }

    @Test
    fun micShowsTimerWhileRecordingAndFreezesOnPause() {
        val view = view(frozenSeconds = 72)

        view.updateMicState(ButtonState.RECORDING)
        assertEquals("● 1:12", view.micLabelForTest)

        view.updateMicState(ButtonState.PAUSED)
        assertEquals("⏸ 1:12", view.micLabelForTest)
    }

    @Test
    fun micReturnsToGlyphWhenIdle() {
        val view = view()

        view.updateMicState(ButtonState.RECORDING)
        view.updateMicState(ButtonState.IDLE_READY)

        assertEquals("🎙", view.micLabelForTest)
    }
}

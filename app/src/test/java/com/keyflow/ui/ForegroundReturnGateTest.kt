package com.keyflow.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundReturnGateTest {
    @Test
    fun processesFirstForegroundReturnWithoutPreviousExternalSurface() {
        val gate = ForegroundReturnGate(debounceMs = 250)

        assertThat(gate.shouldProcessForegroundReturn(nowMs = 120)).isTrue()
    }

    @Test
    fun ignoresForegroundReturnImmediatelyAfterOpeningSystemSettings() {
        val gate = ForegroundReturnGate(debounceMs = 250)

        gate.markExternalSurfaceOpened(nowMs = 1_000)

        assertThat(gate.shouldProcessForegroundReturn(nowMs = 1_120)).isFalse()
    }

    @Test
    fun processesForegroundCallbackAfterDebounceWindow() {
        val gate = ForegroundReturnGate(debounceMs = 250)

        gate.markExternalSurfaceOpened(nowMs = 1_000)

        assertThat(gate.shouldProcessForegroundReturn(nowMs = 1_300)).isTrue()
    }

    @Test
    fun ignoresFocusSensitiveContinuationImmediatelyAfterOpeningSystemSettings() {
        val gate = ForegroundReturnGate(debounceMs = 250)

        gate.markExternalSurfaceOpened(nowMs = 1_000)

        assertThat(gate.shouldRunFocusSensitiveContinuation(nowMs = 1_120)).isFalse()
    }

    @Test
    fun allowsFocusSensitiveContinuationAfterRealReturnEvenWhenResumeAlreadyProcessedIt() {
        val gate = ForegroundReturnGate(debounceMs = 250)

        gate.markExternalSurfaceOpened(nowMs = 1_000)
        assertThat(gate.shouldProcessForegroundReturn(nowMs = 5_000)).isTrue()

        assertThat(gate.shouldRunFocusSensitiveContinuation(nowMs = 5_120)).isTrue()
    }
}

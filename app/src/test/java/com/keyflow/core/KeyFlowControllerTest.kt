package com.keyflow.core

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class KeyFlowControllerTest {

    @Before
    fun resetController() {
        KeyFlowController.registerInserter { true }
        KeyFlowController.flushPending()
        KeyFlowController.clearInserter()
    }

    @Test
    fun `insertOrHold inserts immediately when target is available`() {
        val inserted = mutableListOf<String>()
        KeyFlowController.registerInserter {
            inserted += it
            true
        }

        val outcome = KeyFlowController.insertOrHold("hello")

        assertThat(outcome).isEqualTo(InsertOutcome.Inserted)
        assertThat(inserted).containsExactly("hello")
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `insertOrHold stores pending text when no target is available`() {
        val outcome = KeyFlowController.insertOrHold("later")

        assertThat(outcome).isEqualTo(InsertOutcome.Held)
        assertThat(KeyFlowController.pending.value).isEqualTo("later")
    }

    @Test
    fun `insertOrHold stores pending text when target rejects insertion`() {
        KeyFlowController.registerInserter { false }

        val outcome = KeyFlowController.insertOrHold("retry")

        assertThat(outcome).isEqualTo(InsertOutcome.Held)
        assertThat(KeyFlowController.pending.value).isEqualTo("retry")
    }

    @Test
    fun `flushPending inserts and clears pending text when target is available`() {
        KeyFlowController.insertOrHold("pending")
        val inserted = mutableListOf<String>()
        KeyFlowController.registerInserter {
            inserted += it
            true
        }

        val flushed = KeyFlowController.flushPending()

        assertThat(flushed).isTrue()
        assertThat(inserted).containsExactly("pending")
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `flushPending returns false and keeps pending text when no target is available`() {
        KeyFlowController.insertOrHold("pending")

        val flushed = KeyFlowController.flushPending()

        assertThat(flushed).isFalse()
        assertThat(KeyFlowController.pending.value).isEqualTo("pending")
    }

    @Test
    fun `targetAvailable follows inserter registration lifecycle`() {
        assertThat(KeyFlowController.targetAvailable.value).isFalse()

        KeyFlowController.registerInserter { true }

        assertThat(KeyFlowController.targetAvailable.value).isTrue()

        KeyFlowController.clearInserter()

        assertThat(KeyFlowController.targetAvailable.value).isFalse()
    }
}

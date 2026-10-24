package com.keyflow.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioRecordingCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `startup cleanup removes only stale files from the recordings directory`() {
        val directory = temporaryFolder.newFolder("keyflow-recordings")
        val stale = temporaryFolder.newFile("keyflow-recordings/old.m4a")
        val recent = temporaryFolder.newFile("keyflow-recordings/recent.m4a")
        val unrelated = temporaryFolder.newFile("unrelated.m4a")
        val now = 10L * RECORDING_MAX_AGE_MS
        stale.setLastModified(now - RECORDING_MAX_AGE_MS - 1)
        recent.setLastModified(now - RECORDING_MAX_AGE_MS + 1)

        assertThat(cleanupStaleRecordings(directory, now)).isEqualTo(1)
        assertThat(stale.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
    }
}

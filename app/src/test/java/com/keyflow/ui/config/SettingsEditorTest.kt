package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import org.junit.Test

class SettingsEditorTest {
    @Test
    fun `commit de campo descartado preserva a nova origem e combina outros commits pendentes`() {
        val before = Settings(visionSource = VisionSource.CUSTOM)
        val commits = mutableListOf<Settings>()
        val editor = SettingsEditor(before, commits::add)
        val oldCommit = { value: String -> editor.edit { it.copy(visionCustomModel = value) } }
        val after = before.copy(visionSource = VisionSource.LOCAL, visionOpenAiModel = "preservado")
        editor.refresh(after, commits::add)
        oldCommit("modelo")
        editor.edit { it.copy(visionCustomEndpoint = "https://exemplo.test") }
        assertThat(commits.last()).isEqualTo(after.copy(visionCustomModel = "modelo", visionCustomEndpoint = "https://exemplo.test"))
        editor.edit { it }
        assertThat(commits).hasSize(2)
    }
}

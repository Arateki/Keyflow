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

    @Test
    fun `confirmar o pendente salva os campos na ordem e entrega as Settings resultantes`() {
        val before = Settings(visionSource = VisionSource.CUSTOM, visionCustomEndpoint = "https://a.test/v1")
        val commits = mutableListOf<Settings>()
        val editor = SettingsEditor(before, commits::add)
        editor.pending.track { editor.edit { it.copy(visionCustomEndpoint = "https://b.test/v1") } }
        editor.pending.track { editor.edit { it.copy(visionCustomModel = "modelo") } }

        assertThat(editor.confirmPending()).isTrue()

        assertThat(editor.settings).isEqualTo(before.copy(visionCustomEndpoint = "https://b.test/v1", visionCustomModel = "modelo"))
        assertThat(commits.last()).isEqualTo(editor.settings)
    }

    @Test
    fun `sem texto pendente confirmar não muda nada e diz que não mudou`() {
        val before = Settings(visionSource = VisionSource.CUSTOM)
        val commits = mutableListOf<Settings>()
        val editor = SettingsEditor(before, commits::add)
        editor.pending.track { }

        assertThat(editor.confirmPending()).isFalse()

        assertThat(editor.settings).isEqualTo(before)
        assertThat(commits).isEmpty()
    }

    @Test
    fun `campo que saiu da composição deixa de ser confirmado`() {
        val editor = SettingsEditor(Settings(), { })
        var calls = 0
        val untrack = editor.pending.track { calls++ }

        editor.confirmPending()
        untrack()
        editor.confirmPending()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `o editor lê as Settings novas da composição depois do refresh`() {
        val editor = SettingsEditor(Settings(), { })
        val newer = Settings(visionSource = VisionSource.CUSTOM)
        editor.refresh(newer) { }

        assertThat(editor.settings).isEqualTo(newer)
    }
}

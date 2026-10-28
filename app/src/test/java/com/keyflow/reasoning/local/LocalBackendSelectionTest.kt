package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalBackendSelectionTest {
    @Test
    fun `nova selecao conserva a GPU pronta e as preferencias dos outros modelos`() = runTest {
        val before = LocalBackendPrefs.EMPTY.choose("qwen", ComputeBackend.GPU, 1L).markGpuReady("qwen", 1L)
            .choose("gemma", ComputeBackend.GPU, 2L)
        var stored = before
        val choice = backendChoiceForSelection("qwen") { change -> change(stored).also { stored = it } }
        assertThat(choice.backend).isEqualTo(ComputeBackend.GPU)
        assertThat(choice.ticket).isNotEqualTo(1L)
        assertThat(choice.ticket).isNotEqualTo(LocalBackendPrefs.NO_TICKET)
        assertThat(stored.gpuReady("qwen")).isTrue()
        assertThat(stored.ticketFor("gemma")).isEqualTo(2L)
        assertThat(stored.fellBack("qwen", ComputeBackend.GPU, 1L)).isEqualTo(stored)
    }

    @Test
    fun `selecionar o card nao apaga o aviso de fallback nem troca a CPU gravada`() = runTest {
        var stored = LocalBackendPrefs.EMPTY.choose("qwen", ComputeBackend.GPU, 1L).fellBack("qwen", ComputeBackend.GPU, 1L)
        val choice = backendChoiceForSelection("qwen") { change -> change(stored).also { stored = it } }
        assertThat(choice.backend).isEqualTo(ComputeBackend.CPU)
        assertThat(choice.ticket).isNotEqualTo(1L)
        assertThat(stored.fellBackFrom("qwen")).isEqualTo(ComputeBackend.GPU)
        assertThat(stored.gpuReady("qwen")).isFalse()
    }

    @Test
    fun `a carga leva o snapshot devolvido pela edicao mesmo se outra escolha ocorre em seguida`() = runTest {
        var stored = LocalBackendPrefs.EMPTY.choose("qwen", ComputeBackend.GPU, 1L)
        lateinit var selected: LocalBackendPrefs
        val choice = backendChoiceForSelection("qwen") { change ->
            selected = change(stored)
            stored = selected.choose("qwen", ComputeBackend.CPU, 3L)
            selected
        }
        assertThat(choice.backend).isEqualTo(ComputeBackend.GPU)
        assertThat(choice.ticket).isEqualTo(selected.ticketFor("qwen"))
        assertThat(stored.backendFor("qwen")).isEqualTo(ComputeBackend.CPU)
        assertThat(stored.fellBack("qwen", ComputeBackend.GPU, choice.ticket)).isEqualTo(stored)
    }
}

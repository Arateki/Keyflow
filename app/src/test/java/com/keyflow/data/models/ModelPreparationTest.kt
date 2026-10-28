package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import com.keyflow.ui.config.LocalModelsUi
import org.junit.Test

class ModelPreparationTest {
    @Test
    fun `resposta antiga nao apaga nova selecao nem nova tentativa do mesmo modelo`() {
        val tracker = ModelPreparationTracker { 1234 }
        val old = tracker.begin("a")
        val current = tracker.begin("a")
        tracker.finish(old)
        assertThat(tracker.state.value).isSameInstanceAs(current)
        val next = tracker.begin("b")
        tracker.finish(current, "falha antiga")
        assertThat(tracker.state.value).isSameInstanceAs(next)
        tracker.finish(next)
        assertThat(tracker.state.value).isNull()
    }

    @Test
    fun `falha termina a espera e nova tentativa limpa o erro`() {
        val tracker = ModelPreparationTracker { 1234 }
        val request = tracker.begin("a")
        assertThat(request.startedAtMs).isEqualTo(1234)
        tracker.finish(request, "falha")
        assertThat(tracker.state.value!!.failure).isEqualTo("falha")
        val retry = tracker.begin("a")
        assertThat(retry.failure).isNull()
        tracker.clear()
        tracker.finish(retry, "resposta atrasada")
        assertThat(tracker.state.value).isNull()
    }

    @Test
    fun `preparacao so aparece no modelo instalado e selecionado de qualquer familia`() {
        listOf(ModelCatalog.PARAKEET_V3, ModelCatalog.WHISPER_BASE, ModelCatalog.WHISPER_SMALL,
            ModelCatalog.QWEN3_0_6B, ModelCatalog.QWEN2_5_1_5B, ModelCatalog.QWEN3_4B, ModelCatalog.GEMMA_4_E2B, ModelCatalog.SMOLLM2_1_7B, ModelCatalog.DEEPSEEK_R1_1_5B).forEach { spec ->
            fun compose(installed: Set<String>, selected: String?, preparing: String) = LocalModelsUi.compose(
                catalog = listOf(spec), installed = installed, selectedId = selected, active = null,
                failures = emptyMap(), deviceRamMb = 8192, preparation = ModelPreparation(preparing, 0),
            ).items.single()
            assertThat(compose(setOf(spec.id), spec.id, spec.id).preparation).isNotNull()
            assertThat(compose(emptySet(), spec.id, spec.id).preparation).isNull()
            assertThat(compose(setOf(spec.id), null, spec.id).preparation).isNull()
            assertThat(compose(setOf(spec.id), spec.id, "outro").preparation).isNull()
        }
    }
}

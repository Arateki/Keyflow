package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PRV-02: o seletor de cada provedor lista só modelos que leem imagem, por uma lista curada de ids. */
class VisionModelCatalogTest {

    @Test
    fun `every provider has a list with no repeated id and a default that is in it`() {
        VisionProvider.entries.forEach { provider ->
            val models = VisionModelCatalog.models(provider)
            assertThat(models).isNotEmpty()
            assertThat(models.map { it.id }).containsNoDuplicates()
            assertThat(models.map { it.displayName }).containsNoDuplicates()
            models.forEach {
                assertThat(it.id).isNotEmpty()
                assertThat(it.id).doesNotContain(" ")
                assertThat(it.displayName).isNotEmpty()
            }
            assertThat(VisionModelCatalog.default(provider)).isEqualTo(models.first().id)
            assertThat(VisionModelCatalog.isVision(provider, VisionModelCatalog.default(provider))).isTrue()
        }
    }

    /** Ids que a documentação marca como sem visão, desligados ou deprecados (conferido em 2026-10-28) não podem aparecer. */
    @Test
    fun `models without vision or retired by the provider are not offered`() {
        val all = VisionProvider.entries.flatMap { VisionModelCatalog.models(it) }.map { it.id }
        assertThat(all).containsNoneOf(
            "gpt-4o", "gpt-4o-mini", "o3-mini", "o1", "gpt-3.5-turbo", "gpt-realtime-2", "gpt-image-2.5-flare", "gpt-4o-transcribe",
            "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.1-flash-image",
            "claude-3-5-sonnet-latest", "claude-3-opus-latest",
        )
    }

    @Test
    fun `the ids the providers document as image capable are the ones listed`() {
        assertThat(VisionModelCatalog.models(VisionProvider.OPENAI).map { it.id })
            .containsExactly("gpt-6.1-sol", "gpt-6-astra", "gpt-6.1-terra", "gpt-6-luna")
        assertThat(VisionModelCatalog.models(VisionProvider.ANTHROPIC).map { it.id }.take(4))
            .containsExactly("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1").inOrder()
        assertThat(VisionModelCatalog.models(VisionProvider.GOOGLE).map { it.id }.take(2)).containsExactly("gemini-3.8-flash", "gemini-4-argon").inOrder()
    }

    /** O modelo guardado que deixou de ter visão (ou nunca foi escolhido) cai para o padrão da lista; um válido fica. */
    @Test
    fun `a stored model without vision resolves to the provider default`() {
        VisionProvider.entries.forEach { provider ->
            assertThat(VisionModelCatalog.resolve(provider, "")).isEqualTo(VisionModelCatalog.default(provider))
            assertThat(VisionModelCatalog.resolve(provider, "modelo-que-nao-existe")).isEqualTo(VisionModelCatalog.default(provider))
            val other = VisionModelCatalog.models(provider).last().id
            assertThat(VisionModelCatalog.resolve(provider, other)).isEqualTo(other)
        }
        assertThat(VisionModelCatalog.resolve(VisionProvider.OPENAI, "gpt-4o")).isEqualTo("gpt-6.1-sol")
        assertThat(VisionModelCatalog.resolve(VisionProvider.GOOGLE, "gemini-2.0-flash")).isEqualTo("gemini-3.8-flash")
        assertThat(VisionModelCatalog.resolve(VisionProvider.ANTHROPIC, "claude-3-5-sonnet-latest")).isEqualTo("claude-sonnet-5-5")
    }

    @Test
    fun `the selector offers only the vision models the account has, and the whole list when it cannot tell`() {
        // A conta devolve modelos com e sem visão e um que o app não conhece: só os da lista curada aparecem.
        val onlyVision = VisionModelCatalog.options(
            VisionProvider.OPENAI, listOf("gpt-3.5-turbo", "gpt-6-luna", "gpt-realtime-2", "gpt-6.1-sol", "modelo-novo"),
        )
        assertThat(onlyVision.map { it.id }).containsExactly("gpt-6.1-sol", "gpt-6-luna").inOrder()
        // A lista da conta não veio (rede) ou não tem nenhum modelo com visão: a lista curada inteira, nunca vazia.
        assertThat(VisionModelCatalog.options(VisionProvider.OPENAI, emptyList())).isEqualTo(VisionModelCatalog.models(VisionProvider.OPENAI))
        assertThat(VisionModelCatalog.options(VisionProvider.GOOGLE, listOf("gemini-1.5-pro"))).isEqualTo(VisionModelCatalog.models(VisionProvider.GOOGLE))
    }
}

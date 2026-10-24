package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `Settings` é uma data class: o `toString()` gerado imprimiria todos os campos, segredos incluídos, para qualquer
 * `Log`, mensagem de erro ou inspetor de layout que mostrasse as Settings. Lógica pura, sem Context.
 */
class SettingsToStringTest {

    @Test
    fun `Settings toString hides every secret and still shows the rest`() {
        val printed = settingsWithSentinels().toString()

        for (property in SettingsSecrets.BY_PROPERTY.keys) {
            assertThat(printed).doesNotContain(sentinel(property))
            assertThat(printed).contains("$property=***")
        }
        assertThat(printed).contains("computerSshHost=host.test")
        assertThat(printed).contains("providerAnthropicModel=claude-3-5-sonnet-latest")
    }

    @Test
    fun `Settings toString does not claim a secret that is not set`() {
        assertThat(Settings().toString()).doesNotContain("providerAnthropicApiKey")
        assertThat(Settings().toString()).doesNotContain("providerOpenAiApiKey")
        assertThat(Settings().toString()).doesNotContain("providerGoogleApiKey")
    }

    @Test
    fun `a copy keeps hiding the secrets`() {
        val printed = settingsWithSentinels().copy(computerSshPassword = "SENTINELA-NOVA").toString()

        assertThat(printed).doesNotContain("SENTINELA-NOVA")
        assertThat(printed).contains("computerSshPassword=***")
    }

    // ── Apoio ─────────────────────────────────────────────────────────────

    private fun sentinel(property: String) = "SENTINELA-$property"

    /** [Settings] com um valor único e reconhecível em cada campo secreto. */
    private fun settingsWithSentinels() = Settings(
        customHeaderValue = sentinel("customHeaderValue"),
        reasoningCustomHeaderValue = sentinel("reasoningCustomHeaderValue"),
        visionCustomHeaderValue = sentinel("visionCustomHeaderValue"),
        falaCustomHeaderValue = sentinel("falaCustomHeaderValue"),
        computerSshHost = "host.test",
        computerSshPassword = sentinel("computerSshPassword"),
        computerSshKey = sentinel("computerSshKey"),
    )
}

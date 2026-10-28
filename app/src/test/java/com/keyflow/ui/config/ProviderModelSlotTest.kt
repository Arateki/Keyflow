package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.vision.VisionModelCatalog
import com.keyflow.vision.VisionProvider
import org.junit.After
import org.junit.Test

/**
 * O painel de cada provedor é o mesmo no Raciocínio e na Visão; o [ProviderModelSlot] é o que difere. Estes testes travam que o Raciocínio
 * continua como era (tags e listas) e que a Visão oferece só modelos que leem imagem.
 */
class ProviderModelSlotTest {

    @After fun restoreLanguage() { AppLocale.language = AppLanguage.PT }

    /** As tags do Raciocínio são as que os testes e as galerias sempre usaram: o refator para `ProviderTags` não renomeia nada. */
    @Test
    fun `the reasoning slots keep the tags the tabs always had`() {
        val openAi = ReasoningOpenAiSlot.tags
        assertThat(openAi.prefix).isEqualTo("raciocinio-openai")
        assertThat(openAi.status).isEqualTo(OPENAI_STATUS_TAG)
        assertThat(openAi.error).isEqualTo(OPENAI_ERROR_TAG)
        assertThat(openAi.login).isEqualTo(OPENAI_LOGIN_TAG)
        assertThat(openAi.loginReason).isEqualTo(OPENAI_LOGIN_REASON_TAG)
        assertThat(openAi.cancel).isEqualTo(OPENAI_CANCEL_TAG)
        assertThat(openAi.key).isEqualTo(OPENAI_KEY_TAG)
        assertThat(openAi.disconnect).isEqualTo(OPENAI_DISCONNECT_TAG)
        assertThat(openAi.plan).isEqualTo(OPENAI_PLAN_TAG)
        assertThat(openAi.manageUsage).isEqualTo(OPENAI_MANAGE_USAGE_TAG)
        assertThat(openAi.planNote).isEqualTo(OPENAI_PLAN_NOTE_TAG)
        assertThat(openAi.planMissing).isEqualTo(OPENAI_PLAN_MISSING_TAG)
        assertThat(openAi.model).isEqualTo(OPENAI_MODEL_TAG)
        assertThat(openAi.modelsEmpty).isEqualTo(OPENAI_MODELS_EMPTY_TAG)
        assertThat(openAi.refresh).isEqualTo(OPENAI_MODELS_REFRESH_TAG)
        assertThat(openAi.welcome).isEqualTo(OPENAI_WELCOME_TAG)
        assertThat(openAi.welcomeClose).isEqualTo(OPENAI_WELCOME_CLOSE_TAG)
        assertThat(openAi.welcomeGotIt).isEqualTo(OPENAI_WELCOME_GOT_IT_TAG)
        assertThat(openAi.privacy).isEqualTo(RACIOCINIO_OPENAI_PRIVACY_TAG)
        assertThat(openAi.getKey).isEqualTo("raciocinio-openai-obter-chave")
        assertThat(openAi.limits).isEqualTo("raciocinio-openai-limites")

        val anthropic = ReasoningAnthropicSlot.tags
        assertThat(anthropic.status).isEqualTo(ANTHROPIC_STATUS_TAG)
        assertThat(anthropic.login).isEqualTo(ANTHROPIC_LOGIN_TAG)
        assertThat(anthropic.key).isEqualTo(ANTHROPIC_KEY_TAG)
        assertThat(anthropic.disconnect).isEqualTo(ANTHROPIC_DISCONNECT_TAG)
        assertThat(anthropic.model).isEqualTo(ANTHROPIC_MODEL_TAG)
        assertThat(anthropic.refresh).isEqualTo(ANTHROPIC_MODELS_REFRESH_TAG)
        assertThat(anthropic.privacy).isEqualTo(ANTHROPIC_PRIVACY_TAG)

        val google = ReasoningGoogleSlot.tags
        assertThat(google.status).isEqualTo(GOOGLE_STATUS_TAG)
        assertThat(google.login).isEqualTo(GOOGLE_LOGIN_TAG)
        assertThat(google.key).isEqualTo(GOOGLE_KEY_TAG)
        assertThat(google.disconnect).isEqualTo(GOOGLE_DISCONNECT_TAG)
        assertThat(google.model).isEqualTo(GOOGLE_MODEL_TAG)
        assertThat(google.refresh).isEqualTo(GOOGLE_MODELS_REFRESH_TAG)
        assertThat(google.privacy).isEqualTo(GOOGLE_PRIVACY_TAG)
    }

    @Test
    fun `the vision slots use their own tags so a test of one tab never meets the other's nodes`() {
        val reasoning = listOf(ReasoningOpenAiSlot, ReasoningAnthropicSlot, ReasoningGoogleSlot).map { it.tags.prefix }
        val vision = listOf(VisionCloudSlot.OpenAi, VisionCloudSlot.Anthropic, VisionCloudSlot.Google).map { it.tags.prefix }
        assertThat(vision).containsExactly("visao-openai", "visao-anthropic", "visao-google").inOrder()
        assertThat(reasoning + vision).containsNoDuplicates()
    }

    @Test
    fun `reasoning lists and choices behave as before`() {
        val account = listOf(AccountModel("a", "A"), AccountModel("b", "B"))
        // OpenAI: sem escolha fica vazio (RCL-06); o já escolhido continua na lista mesmo que a conta não o devolva.
        assertThat(ReasoningOpenAiSlot.chosen(Settings())).isEmpty()
        assertThat(ReasoningOpenAiSlot.ssoOptions(account, "")).isEqualTo(account)
        assertThat(ReasoningOpenAiSlot.ssoOptions(account, "c").map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(ReasoningOpenAiSlot.ssoOptions(emptyList(), "")).isEqualTo(OPENAI_SSO_MODELS)
        assertThat(ReasoningOpenAiSlot.apiKeyOptions(emptyList(), "")).isEqualTo(OPENAI_API_KEY_MODELS)
        assertThat(ReasoningOpenAiSlot.choose(Settings(), "x").reasoningOpenAiModel).isEqualTo("x")
        // Anthropic e Google: padrão próprio; a lista da chave de API não depende da conta.
        assertThat(ReasoningAnthropicSlot.chosen(Settings(reasoningAnthropicModel = ""))).isEqualTo("claude-sonnet-4-6")
        assertThat(ReasoningAnthropicSlot.ssoOptions(account, "x")).isEqualTo(account)
        assertThat(ReasoningAnthropicSlot.ssoOptions(emptyList(), "x")).isEqualTo(ANTHROPIC_SSO_MODELS)
        assertThat(ReasoningAnthropicSlot.apiKeyOptions(account, "x")).isEqualTo(ANTHROPIC_API_KEY_MODELS)
        assertThat(ReasoningGoogleSlot.chosen(Settings(reasoningGoogleModel = ""))).isEqualTo("gemini-2.0-flash")
        assertThat(ReasoningGoogleSlot.apiKeyOptions(account, "x")).isEqualTo(GOOGLE_API_KEY_MODELS)
        assertThat(ReasoningGoogleSlot.choose(Settings(), "g").reasoningGoogleModel).isEqualTo("g")
        // O Raciocínio segue com a nota e o bloco de limites de sempre.
        assertThat(ReasoningOpenAiSlot.privacyNote()).isNull()
        assertThat(ReasoningOpenAiSlot.showsUsageLimits).isTrue()
    }

    @Test
    fun `the vision slots offer only models that read images and fall back to the default`() {
        // Escolha nunca feita ou de um modelo que saiu da lista: vale o padrão da lista (PRV-02), e é ele que o seletor mostra.
        assertThat(VisionCloudSlot.OpenAi.chosen(Settings())).isEqualTo(VisionModelCatalog.default(VisionProvider.OPENAI))
        assertThat(VisionCloudSlot.OpenAi.chosen(Settings(visionOpenAiModel = "gpt-4o"))).isEqualTo(VisionModelCatalog.default(VisionProvider.OPENAI))
        assertThat(VisionCloudSlot.Anthropic.chosen(Settings(visionAnthropicModel = "claude-opus-5-5"))).isEqualTo("claude-opus-5-5")
        assertThat(VisionCloudSlot.Google.chosen(Settings(visionGoogleModel = "gemini-2.0-flash"))).isEqualTo("gemini-3.8-flash")
        // A conta devolve um modelo sem visão: ele não entra, nas duas sub-abas.
        val account = listOf(AccountModel("gpt-3.5-turbo", "GPT 3.5"), AccountModel("gpt-6-luna", "GPT-6-Luna"))
        assertThat(VisionCloudSlot.OpenAi.ssoOptions(account, "").map { it.id }).containsExactly("gpt-6-luna")
        assertThat(VisionCloudSlot.OpenAi.apiKeyOptions(account, "").map { it.id }).containsExactly("gpt-6-luna")
        // Sem lista da conta: a curada inteira.
        assertThat(VisionCloudSlot.OpenAi.ssoOptions(emptyList(), "").map { it.id })
            .containsExactlyElementsIn(VisionModelCatalog.models(VisionProvider.OPENAI).map { it.id }).inOrder()
        // Escolher grava no campo da Visão, não no do Raciocínio.
        val chosen = VisionCloudSlot.Anthropic.choose(Settings(), "claude-haiku-5-5")
        assertThat(chosen.visionAnthropicModel).isEqualTo("claude-haiku-5-5")
        assertThat(chosen.reasoningAnthropicModel).isEqualTo(Settings().reasoningAnthropicModel)
    }

    /** PRV-04: a nota diz que a IMAGEM sai, e o bloco de limites (que só tem valores de exemplo) não aparece na Visão. */
    @Test
    fun `the vision cards say the image leaves the device and show no sample usage numbers`() {
        assertThat(VisionCloudSlot.OpenAi.privacyNote()).isEqualTo("A imagem da captura é enviada à OpenAI; as credenciais ficam só neste aparelho.")
        assertThat(VisionCloudSlot.Anthropic.privacyNote()).contains("Anthropic")
        assertThat(VisionCloudSlot.Google.privacyNote()).contains("Google")
        listOf(VisionCloudSlot.OpenAi, VisionCloudSlot.Anthropic, VisionCloudSlot.Google).forEach {
            assertThat(it.showsUsageLimits).isFalse()
            assertThat(it.privacyNote()).contains("imagem")
        }
        AppLocale.language = AppLanguage.EN
        assertThat(VisionCloudSlot.OpenAi.privacyNote()).isEqualTo("The capture image is sent to OpenAI; credentials stay only on this device.")
    }

    @Test
    fun `deleting a Gemma 4 forgets it in both tabs that shared it`() {
        val both = Settings(visionLocalModelId = "gemma-4-e2b-litertlm", reasoningLocalModelId = "gemma-4-e2b-litertlm")
        val cleared = both.withoutLocalModel("gemma-4-e2b-litertlm")
        assertThat(cleared.visionLocalModelId).isNull()
        assertThat(cleared.reasoningLocalModelId).isNull()
        // Outro modelo continua escolhido.
        val mixed = Settings(visionLocalModelId = "ocr-paddle-latin", reasoningLocalModelId = "gemma-4-e2b-litertlm").withoutLocalModel("gemma-4-e2b-litertlm")
        assertThat(mixed.visionLocalModelId).isEqualTo("ocr-paddle-latin")
        assertThat(mixed.reasoningLocalModelId).isNull()
    }
}

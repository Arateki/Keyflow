package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialMask
import com.keyflow.auth.OAuthFlow
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.vision.ocr.OcrResult
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A aba Visão (VC-T11/T17, CFG-V01/V02): a origem, a lista local (PaddleOCR e Gemma 4, com as capacidades de cada um, baixar → progresso →
 * instalado → Usar → apagar), os provedores com as mesmas conexões do Raciocínio e só modelos que leem imagem, o Custom e o Testar.
 */
@RunWith(AndroidJUnit4::class)
class VisaoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val ocr = ModelCatalog.OCR_PADDLE_LATIN
    private val e2b = ModelCatalog.GEMMA_4_E2B
    private val e4b = ModelCatalog.GEMMA_4_E4B
    private val whisper = ModelCatalog.WHISPER_BASE
    private val qwen = ModelCatalog.QWEN3_0_6B

    /** O gerenciador de modelos falso: guarda o que foi pedido e muda o estado da lista como o disco mudaria. */
    private inner class FakeActions : LocalModelActions {
        val deletes = mutableListOf<String>()
        val prepares = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        var onDownload: (ModelSpec) -> Unit = {}
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult {
            downloads += spec.id
            onDownload(spec)
            return StartResult.Started
        }
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) { deletes += modelId }
        override fun prepare(modelId: String, language: String?) { prepares += modelId }
    }

    private class KeyFlow : ApiKeyFlow {
        override val method = AuthMethod.API_KEY
        override val availability: Availability = Availability.Available
        override suspend fun revoke(current: Credential) = Unit
    }

    private class LoginFlow : OAuthFlow {
        override val method = AuthMethod.OAUTH
        override val availability: Availability = Availability.Available
        override suspend fun begin(forceConsent: Boolean): AuthStart = error("não usado aqui")
        override suspend fun complete(redirect: URI): Credential.OAuth = error("não usado aqui")
        override suspend fun refresh(current: Credential.OAuth): Credential.OAuth = error("não usado aqui")
        override suspend fun revoke(current: Credential) = Unit
    }

    /** Uma conexão de provedor falsa: a mesma instância serve ao Raciocínio e à Visão, como no app (PRV-01). */
    private class FakeAuth(
        initial: ConnectionState = ConnectionState.Disconnected,
        var models: List<AccountModel> = emptyList(),
        storedKey: String? = null,
    ) : AuthActions {
        override val flows: List<AuthFlow> = listOf(KeyFlow(), LoginFlow())
        override val usageUrl: String? = null
        val connection = MutableStateFlow(initial)
        private val maskedKey = MutableStateFlow(CredentialMask.of(storedKey))
        val savedKeys = mutableListOf<String>()
        var modelCalls = 0
        override fun state(): Flow<ConnectionState> = connection
        override fun connect(method: AuthMethod, forceConsent: Boolean) = Unit
        override fun cancel() = Unit
        override fun disconnect() = Unit
        override fun saveApiKey(key: String) { savedKeys += key; maskedKey.value = CredentialMask.of(key) }
        override fun apiKey(): String? = maskedKey.value
        override fun apiKeyFlow(): Flow<String?> = maskedKey
        override suspend fun consumeNotice(key: String): Boolean = false
        override fun openUrl(url: String) = Unit
        override suspend fun listModels(): List<AccountModel> { modelCalls++; return models }
    }

    /** A lista local sai do estado (instalados, download em curso), como o `LocalModelsController` a monta. */
    private inner class Harness(
        initial: Settings = Settings(),
        installed: Set<String> = emptySet(),
        val actions: FakeActions = FakeActions(),
        val openAi: FakeAuth = FakeAuth(),
        val anthropic: FakeAuth = FakeAuth(),
        val google: FakeAuth = FakeAuth(),
        val tester: OcrTester = OcrTester.None,
        val visionTester: VisionTester? = null,
    ) {
        var settings by mutableStateOf(initial)
        var installed by mutableStateOf(installed)
        var active by mutableStateOf<DownloadProgress?>(null)
        var localSupported by mutableStateOf(true)
        var catalog by mutableStateOf(ModelCatalog.visionModels(debug = true))
        // Falso: o anfitrião não devolve as Settings novas à tela (o Testar não pode ficar sem resposta por isso).
        var acceptsChanges = true
        // O que o anfitrião recebeu e ainda não devolveu à tela (retorno atrasado, como na SetupActivity real).
        var held: Settings? = null
        var holdChanges = false

        val models: LocalModelsUi
            get() = LocalModelsUi.compose(
                catalog = catalog, installed = installed, active = active, failures = emptyMap(),
                selectedId = settings.visionLocalModelId, deviceRamMb = 8_000,
            )

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                VisaoPanel(
                    settings = settings,
                    onSettingsChange = { if (holdChanges) held = it else if (acceptsChanges) settings = it },
                    models = models,
                    actions = actions,
                    localSupported = localSupported,
                    tester = tester,
                    visionTester = visionTester,
                    openAiAuth = openAi,
                    anthropicAuth = anthropic,
                    googleAuth = google,
                )
            }
        }
    }

    private fun show(h: Harness) = rule.setContent { h.Content() }

    private fun node(tag: String) = rule.onNodeWithTag(tag).performScrollTo()

    private fun model(id: String) = "visao-local-model-$id"

    private fun tag(id: String, key: String) = rule.onNodeWithTag("${model(id)}-tag-$key")

    // ── origem Local ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun opensOnTheLocalSourceWithTheOcrAndTheTwoGemmaModelsListed() {
        show(Harness())

        rule.onNodeWithTag(visionSourceTag(VisionSource.LOCAL)).assertIsDisplayed()
        node(model(ocr.id)).assertIsDisplayed()
        node(model(e2b.id)).assertIsDisplayed()
        node(model(e4b.id)).assertIsDisplayed()
        node(VISAO_LOCAL_PRIVACY_TAG).assertIsDisplayed()
        node(VISAO_LOCAL_DOWNLOAD_NOTE_TAG).assertIsDisplayed()
        rule.onNodeWithTag(VISAO_LOCAL_EMPTY_TAG).assertDoesNotExist()
        rule.onNodeWithText("Origem da visão").assertExists()
        node(VISAO_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    /** O painel antigo tinha o destino da tradução e o "Testar OCR" de mentira: nenhum dos dois volta (a Visão não traduz). */
    @Test
    fun noTranslationControlAndNoFakeOcrTest() {
        show(Harness())

        rule.onNodeWithText("Tradução e captura").assertDoesNotExist()
        rule.onNodeWithText("Idioma de destino da tradução").assertDoesNotExist()
        rule.onNodeWithText("Testar OCR").assertDoesNotExist()
    }

    /** MDL-06: cada card diz o que o modelo faz, vindo do dado do catálogo — o OCR lê texto; o Gemma 4 lê, descreve e responde. */
    @Test
    fun eachCardListsItsCapabilities() {
        show(Harness())

        tag(ocr.id, "capability_read_text").performScrollTo().assertTextEquals("Ler texto da tela")
        tag(ocr.id, "capability_describe_image").assertDoesNotExist()
        tag(ocr.id, "capability_answer_about_screen").assertDoesNotExist()
        listOf(e2b, e4b).forEach {
            tag(it.id, "capability_read_text").performScrollTo().assertTextEquals("Ler texto da tela")
            tag(it.id, "capability_describe_image").assertTextEquals("Descrever imagem")
            tag(it.id, "capability_answer_about_screen").assertTextEquals("Responder sobre a tela")
            // O que é do Raciocínio fica lá: nem pensamento nem ferramentas na Visão.
            tag(it.id, "thinking").assertDoesNotExist()
            tag(it.id, "capability_tools").assertDoesNotExist()
        }
        tag(e2b.id, "ram").performScrollTo().assertTextEquals("≥ 4 GB de RAM")
    }

    @Test
    fun theOcrCardShowsThePaddlePaddleLogo() {
        show(Harness())

        rule.onNodeWithTag("model-vendor-paddlepaddle").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("PaddlePaddle").assertExists()
    }

    @Test
    fun theVisionListNeverShowsSpeechOrTextOnlyModels() {
        show(Harness())

        rule.onNodeWithTag(model(whisper.id)).assertDoesNotExist()
        rule.onNodeWithTag(model(qwen.id)).assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-model-${ocr.id}").assertDoesNotExist()
        rule.onNodeWithTag("voz-local-model-${ocr.id}").assertDoesNotExist()
    }

    /** T11: baixar → progresso (com Cancelar) → instalado → "Usar" persiste → apagar volta a "Baixar". */
    @Test
    fun downloadThenProgressThenInstalledThenUseThenDeleteGoesBackToDownload() {
        val h = Harness()
        h.actions.onDownload = { spec ->
            h.active = DownloadProgress(spec.id, DownloadState.RUNNING, bytesDone = spec.totalBytes * 42 / 100, totalBytes = spec.totalBytes)
        }
        show(h)

        node("visao-local-download-${ocr.id}").assertTextEquals("Baixar").performClick()
        rule.waitUntil(3_000) { h.actions.downloads == listOf(ocr.id) }
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("visao-local-progress-${ocr.id}").fetchSemanticsNodes().isNotEmpty() }
        node("visao-local-progress-${ocr.id}").assertIsDisplayed()
        node("visao-local-cancel-${ocr.id}").assertTextEquals("Cancelar")
        // Com um download em curso os outros "Baixar" ficam indisponíveis (DLM-06).
        node("visao-local-download-${e2b.id}").assertIsNotEnabled()

        // O download termina: instalado, ainda não escolhido.
        h.active = null
        h.installed = setOf(ocr.id)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Apagar").fetchSemanticsNodes().isNotEmpty() }
        node("visao-local-delete-${ocr.id}").assertTextEquals("Apagar")
        assertNull(h.settings.visionLocalModelId)

        // Usar: tocar no card instalado grava a escolha — e só isso (carregar o modelo é do motor, não da aba).
        node(model(ocr.id)).performClick()
        rule.runOnIdle {
            assertEquals(ocr.id, h.settings.visionLocalModelId)
            assertEquals(emptyList<String>(), h.actions.prepares)
        }

        // Apagar: confirma, o arquivo vai embora, a escolha também, e o card volta a "Baixar".
        node("visao-local-delete-${ocr.id}").performClick()
        node("visao-local-confirm-yes").performClick()
        rule.runOnIdle {
            assertEquals(listOf(ocr.id), h.actions.deletes)
            assertNull(h.settings.visionLocalModelId)
            h.installed = emptySet()
        }
        node("visao-local-download-${ocr.id}").assertTextEquals("Baixar")
    }

    @Test
    fun aGemmaInstalledFromTheReasoningTabAlreadyShowsInstalledHere() {
        val h = Harness(installed = setOf(e2b.id))
        show(h)

        rule.onNodeWithTag("${model(e2b.id)}-downloaded", useUnmergedTree = true).performScrollTo().assertExists()
        node("visao-local-delete-${e2b.id}").assertTextEquals("Apagar")
        node("visao-local-download-${e4b.id}").assertTextEquals("Baixar")
    }

    /** O Gemma 4 é o mesmo arquivo do Raciocínio: apagar daqui tira a escolha dos dois lados, senão o Raciocínio apontaria para um modelo que sumiu. */
    @Test
    fun deletingASharedGemmaForgetsItInBothTabs() {
        val h = Harness(Settings(visionLocalModelId = e2b.id, reasoningLocalModelId = e2b.id), installed = setOf(e2b.id))
        show(h)

        node("visao-local-delete-${e2b.id}").performClick()
        node("visao-local-confirm-yes").performClick()
        rule.runOnIdle {
            assertEquals(listOf(e2b.id), h.actions.deletes)
            assertNull(h.settings.visionLocalModelId)
            assertNull(h.settings.reasoningLocalModelId)
        }
    }

    @Test
    fun deletingTheSelectedOcrKeepsTheReasoningChoice() {
        val h = Harness(Settings(visionLocalModelId = ocr.id, reasoningLocalModelId = qwen.id), installed = setOf(ocr.id))
        show(h)

        node("visao-local-delete-${ocr.id}").performClick()
        node("visao-local-confirm-yes").performClick()
        rule.runOnIdle {
            assertNull(h.settings.visionLocalModelId)
            assertEquals(qwen.id, h.settings.reasoningLocalModelId)
        }
    }

    @Test
    fun aDeviceWithoutTheLocalEngineShowsNoDownloadsAndSaysWhy() {
        val h = Harness()
        h.localSupported = false
        show(h)

        node(VISAO_LOCAL_UNSUPPORTED_TAG).assertIsDisplayed()
        rule.onNodeWithTag(model(ocr.id)).assertDoesNotExist()
        rule.onNodeWithTag(VISAO_LOCAL_PRIVACY_TAG).assertDoesNotExist()
    }

    /** O release não tem modelo de visão provado ainda: a lista vazia diz isso em vez de ficar em branco. */
    @Test
    fun anEmptyLocalListSaysTheModelsAreComing() {
        val h = Harness()
        h.catalog = emptyList()
        show(h)

        node(VISAO_LOCAL_EMPTY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(model(ocr.id)).assertDoesNotExist()
    }

    @Test
    fun legacyCreditsFallsBackToLocalAndTheOptionIsDisabled() {
        show(Harness(Settings(visionSource = VisionSource.CREDITS)))

        rule.onNodeWithTag(visionSourceTag(VisionSource.LOCAL)).assertIsDisplayed()
        rule.onNodeWithTag(visionSourceTag(VisionSource.CREDITS)).assertIsNotEnabled().assertTextContains("Em breve", substring = true)
        node(model(ocr.id)).assertIsDisplayed()
    }

    @Test
    fun theSourcesAreLocalOpenAiAnthropicGoogleAndCustom() {
        val h = Harness()
        show(h)

        listOf(VisionSource.OPENAI, VisionSource.ANTHROPIC, VisionSource.GOOGLE, VisionSource.CUSTOM, VisionSource.LOCAL).forEach { source ->
            node(visionSourceTag(source)).performClick()
            rule.runOnIdle { assertEquals(source, h.settings.visionSource) }
        }
    }

    // ── provedores ────────────────────────────────────────────────────────────────────────────────

    /** PRV-01: o card é o mesmo das outras abas, ligado à MESMA conexão: o que está conectado no Raciocínio aparece conectado aqui. */
    @Test
    fun aProviderConnectedElsewhereShowsConnectedHere() {
        val openAi = FakeAuth(ConnectionState.Connected("voce@exemplo.com", AuthMethod.OAUTH, planUsage = true))
        show(Harness(Settings(visionSource = VisionSource.OPENAI), openAi = openAi))

        node("visao-openai-card").assertIsDisplayed()
        node("visao-openai-status").assertTextEquals("Conectado como voce@exemplo.com")
        node("visao-openai-desconectar").assertIsDisplayed()
        // É o card da Visão, com as tags da Visão — nada do Raciocínio no quadro.
        rule.onNodeWithTag("raciocinio-openai-card").assertDoesNotExist()
        // O bloco de limites traz só valores de exemplo: a Visão não o mostra.
        rule.onNodeWithTag("visao-openai-limites").assertDoesNotExist()
    }

    /** PRV-02: a conta devolve modelos com e sem visão; o seletor mostra só os que leem imagem. */
    @Test
    fun theSelectorOffersOnlyTheModelsThatReadImages() {
        val openAi = FakeAuth(
            ConnectionState.Connected("voce@exemplo.com", AuthMethod.OAUTH, planUsage = true),
            models = listOf(
                AccountModel("gpt-3.5-turbo", "GPT 3.5 Turbo"), AccountModel("gpt-6-luna", "GPT-6-Luna"),
                AccountModel("gpt-realtime-2", "GPT Realtime 2"), AccountModel("gpt-6.1-sol", "GPT-6.1-Sol"),
            ),
        )
        val h = Harness(Settings(visionSource = VisionSource.OPENAI), openAi = openAi)
        show(h)

        rule.waitUntil(3_000) { openAi.modelCalls >= 1 }
        // Nada escolhido ainda: o seletor mostra o padrão da lista curada.
        node("visao-openai-modelo").assertTextContains("GPT-6.1-Sol", substring = true).performClick()
        rule.onNodeWithText("GPT-6-Luna").assertIsDisplayed()
        rule.onNodeWithText("GPT 3.5 Turbo").assertDoesNotExist()
        rule.onNodeWithText("GPT Realtime 2").assertDoesNotExist()

        rule.onNodeWithText("GPT-6-Luna").performClick()
        rule.waitUntil(3_000) { h.settings.visionOpenAiModel == "gpt-6-luna" }
        node("visao-openai-modelo").assertTextContains("GPT-6-Luna", substring = true)
        // Escolher na Visão não mexe no modelo do Raciocínio.
        assertEquals(Settings().reasoningOpenAiModel, h.settings.reasoningOpenAiModel)
    }

    /** PRV-02: o modelo guardado que deixou de ter visão (o `gpt-4o` das versões antigas) cai para o padrão da lista. */
    @Test
    fun aStoredModelWithoutVisionFallsBackToTheListDefault() {
        show(Harness(Settings(visionSource = VisionSource.OPENAI, visionOpenAiModel = "gpt-4o")))

        node("visao-openai-modelo").assertTextContains("GPT-6.1-Sol", substring = true)
    }

    @Test
    fun anthropicAndGoogleShowTheirOwnCardsWithTheVisionList() {
        val h = Harness(Settings(visionSource = VisionSource.ANTHROPIC, visionAnthropicModel = "claude-opus-5-5"))
        show(h)

        node("visao-anthropic-card").assertIsDisplayed()
        node("visao-anthropic-modelo").assertTextContains("Claude Opus 5.5", substring = true)
        node("visao-anthropic-privacy").assertTextContains("A imagem da captura é enviada à Anthropic", substring = true)

        node(visionSourceTag(VisionSource.GOOGLE)).performClick()
        node("visao-google-card").assertIsDisplayed()
        node("visao-google-modelo").assertTextContains("Gemini 3.8 Flash", substring = true)
        node("visao-google-privacy").assertTextContains("A imagem da captura é enviada ao Google", substring = true)
        rule.onNodeWithTag("visao-anthropic-card").assertDoesNotExist()
    }

    /** PRV-04: a nota está ao lado da escolha de nuvem, e diz que é a IMAGEM que sai. */
    @Test
    fun theCloudCardSaysTheImageIsSentToTheProvider() {
        show(Harness(Settings(visionSource = VisionSource.OPENAI)))

        node("visao-openai-privacy").assertTextEquals("A imagem da captura é enviada à OpenAI; as credenciais ficam só neste aparelho.")
    }

    @Test
    fun theApiKeyTabSavesTheKeyThroughTheSharedConnection() {
        val openAi = FakeAuth()
        show(Harness(Settings(visionSource = VisionSource.OPENAI), openAi = openAi))

        node("visao-openai-tab-api").performClick()
        node("visao-openai-chave").performTextReplacement("sk-visao-123")
        node("visao-openai-chave").performImeAction()
        rule.runOnIdle { assertEquals(listOf("sk-visao-123"), openAi.savedKeys) }
    }

    // ── Custom ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun customPersistsItsFieldsAndSaysTheImageLeavesTheDevice() {
        val h = Harness(Settings(visionSource = VisionSource.CUSTOM))
        show(h)

        node("visao-custom-endpoint").performTextReplacement("https://self.host/v1/chat/completions")
        node("visao-custom-endpoint").performImeAction()
        node("visao-custom-model").performTextReplacement("llama-3.2-vision")
        node("visao-custom-model").performImeAction()
        rule.runOnIdle {
            assertEquals("https://self.host/v1/chat/completions", h.settings.visionCustomEndpoint)
            assertEquals("llama-3.2-vision", h.settings.visionCustomModel)
        }
        node("visao-custom-header-name").assertIsDisplayed()
        node("visao-custom-header-value").assertIsDisplayed()
        node(VISAO_CUSTOM_PRIVACY_TAG).assertTextContains("A imagem da captura é enviada ao endpoint", substring = true)
        rule.onNodeWithText("Padrão compatível com OpenAI Chat Completions com imagem", substring = true).assertExists()
    }

    @Test
    fun sairDoCustomSalvaAEdicaoPendenteSemRestaurarAOrigemAntiga() {
        val h = Harness(Settings(visionSource = VisionSource.CUSTOM))
        show(h)
        // Um caractere não aciona o salvamento de colagem: fica pendente até perder foco ou sair da composição.
        node("visao-custom-model").performTextInput("x")
        node(visionSourceTag(VisionSource.LOCAL)).performClick()
        rule.runOnIdle {
            assertEquals(VisionSource.LOCAL, h.settings.visionSource)
            assertEquals("x", h.settings.visionCustomModel)
        }
        node(model(ocr.id)).assertIsDisplayed()
    }

    @Test
    fun contaSemOPadraoMostraEPersisteSeuModeloTantoNaAssinaturaQuantoNaApi() {
        val auth = FakeAuth(
            ConnectionState.Connected("voce@exemplo.com", AuthMethod.OAUTH, planUsage = true),
            models = listOf(AccountModel("gpt-6-luna", "GPT-6-Luna")),
        )
        val h = Harness(Settings(visionSource = VisionSource.OPENAI, visionOpenAiModel = "gpt-6.1-sol"), openAi = auth)
        show(h)
        rule.waitUntil(3_000) { h.settings.visionOpenAiModel == "gpt-6-luna" }
        node("visao-openai-modelo").assertTextContains("GPT-6-Luna", substring = true)
        node("visao-openai-tab-api").performClick()
        node("visao-openai-modelo").assertTextContains("GPT-6-Luna", substring = true)
        rule.runOnIdle { assertEquals("gpt-6-luna", h.settings.visionOpenAiModel) }
    }

    // ── Testar ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun testAreaRequiresInstalledModelOnLocal() {
        show(Harness())

        node(VISAO_TEST_BUTTON_TAG).assertIsNotEnabled()
        node(VISAO_TEST_REASON_TAG).assertTextEquals("Baixe e selecione um modelo para testar.")
        rule.onNodeWithTag(VISAO_TEST_IMAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun theTestReadsTheSampleImageWithTheChosenOcrAndShowsTheTextAndTheTime() {
        var runs = 0
        val tester = OcrTester { runs++; OcrResult.Text("${VisionTestImage.LINE_1}\n${VisionTestImage.LINE_2}") }
        show(Harness(Settings(visionLocalModelId = ocr.id), installed = setOf(ocr.id), tester = tester))

        node(VISAO_TEST_IMAGE_TAG).assertIsDisplayed()
        node(VISAO_TEST_BUTTON_TAG).assertIsEnabled().assertTextEquals("Testar").performClick()

        rule.waitUntil(3_000) { rule.onAllNodesWithText(VisionTestImage.LINE_1, substring = true).fetchSemanticsNodes().isNotEmpty() }
        node(VISAO_TEST_RESPONSE_TAG).assertTextContains(VisionTestImage.LINE_1, substring = true)
        node(VISAO_TEST_TIMES_TAG).assertTextContains("Tempo de reconhecimento:", substring = true)
        rule.runOnIdle { assertEquals(1, runs) }
        rule.onNodeWithTag(VISAO_TEST_REASON_TAG).assertDoesNotExist()
        rule.onNodeWithTag(VISAO_TEST_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun anOcrFailureShowsAPlainMessageAndNothingElse() {
        val tester = OcrTester { OcrResult.Failed(com.keyflow.vision.ocr.OcrFailure.OOM) }
        show(Harness(Settings(visionLocalModelId = ocr.id), installed = setOf(ocr.id), tester = tester))

        node(VISAO_TEST_BUTTON_TAG).performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Memória insuficiente", substring = true).fetchSemanticsNodes().isNotEmpty() }
        node(VISAO_TEST_ERROR_TAG).assertTextContains("Memória insuficiente", substring = true)
        rule.onNodeWithTag(VISAO_TEST_RESPONSE_TAG).assertDoesNotExist()
        node(VISAO_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    @Test
    fun GemmaInstaladoPodeTestarEProvedorDesconectadoPedeCredencial() {
        val h = Harness(Settings(visionLocalModelId = e2b.id), installed = setOf(e2b.id))
        show(h)
        node(VISAO_TEST_BUTTON_TAG).assertIsEnabled()
        rule.onNodeWithTag(VISAO_TEST_REASON_TAG).assertDoesNotExist()
        node(visionSourceTag(VisionSource.OPENAI)).performClick()
        node(VISAO_TEST_BUTTON_TAG).assertIsNotEnabled()
        node(VISAO_TEST_REASON_TAG).assertTextContains("Conecte a conta", substring = true)
    }

    @Test
    fun testeDoProvedorRecebeASelacaoMostraRespostaTempoENotaDeEnvio() {
        val seen = mutableListOf<Settings>()
        val auth = FakeAuth(ConnectionState.Connected("conta falsa", AuthMethod.API_KEY))
        val h = Harness(Settings(visionSource = VisionSource.OPENAI), openAi = auth,
            visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("Texto da imagem") })
        show(h)
        node(VISAO_TEST_BUTTON_TAG).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        node(VISAO_TEST_RESPONSE_TAG).assertTextContains("Texto da imagem", substring = true)
        node(VISAO_TEST_TIMES_TAG).assertTextContains("Tempo de reconhecimento", substring = true)
        rule.runOnIdle { assertEquals(VisionSource.OPENAI, seen.single().visionSource) }
        rule.onNodeWithText("O teste envia esta imagem de exemplo ao provedor escolhido.").performScrollTo().assertIsDisplayed()
    }

    private val customSaved = Settings(
        visionSource = VisionSource.CUSTOM, visionCustomEndpoint = "https://a.test/v1", visionCustomModel = "modelo-salvo",
        visionCustomHeaderName = "H", visionCustomHeaderValue = "V",
    )

    /** Seleciona só o "a" de `https://a.test/v1` e digita "b": o campo mostra o endpoint novo, que ainda não foi salvo (sem Done nem desfoque). */
    @OptIn(ExperimentalTestApi::class)
    private fun typeBInsteadOfAInTheEndpoint() {
        val endpoint = node("visao-custom-endpoint")
        endpoint.performTextInputSelection(TextRange(8, 9))
        endpoint.performTextInput("b")
        endpoint.assertTextContains("https://b.test/v1")
    }

    @Test
    fun tocarEmTestarConfirmaOEndpointPendenteDoCustomSemDoneEUsaAsSettingsResultantes() {
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("Texto do Custom") })
        show(h)
        typeBInsteadOfAInTheEndpoint()
        rule.runOnIdle { assertEquals("https://a.test/v1", h.settings.visionCustomEndpoint) }

        node(VISAO_TEST_BUTTON_TAG).assertIsEnabled().performClick()

        // O teste parte uma vez só, já com o endpoint novo, e o resultado aparece: confirmar as Settings não cancela o teste que acabou de partir.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        node(VISAO_TEST_RESPONSE_TAG).assertTextContains("Texto do Custom", substring = true)
        rule.runOnIdle {
            assertEquals("https://b.test/v1", seen.single().visionCustomEndpoint)
            assertEquals("modelo-salvo", seen.single().visionCustomModel)
            assertEquals("https://b.test/v1", h.settings.visionCustomEndpoint)
        }
        node(VISAO_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    @Test
    fun tocarEmTestarConfirmaTodosOsCamposPendentesDoCustomJuntos() {
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("ok") })
        show(h)
        typeBInsteadOfAInTheEndpoint()
        // Um caractere a mais por campo: acrescentar mais que isso é colagem, que salva na hora e não fica pendente.
        node("visao-custom-header-name").performTextReplacement("HX")
        node("visao-custom-header-value").performTextReplacement("Vs")
        node("visao-custom-model").performTextReplacement("modelo-salvo!")

        node(VISAO_TEST_BUTTON_TAG).performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle {
            val tested = seen.single()
            assertEquals("https://b.test/v1", tested.visionCustomEndpoint)
            assertEquals("HX", tested.visionCustomHeaderName)
            assertEquals("Vs", tested.visionCustomHeaderValue)
            assertEquals("modelo-salvo!", tested.visionCustomModel)
            assertEquals(tested, h.settings)
        }
    }

    @Test
    fun testarDepoisDoDoneUsaAsSettingsJaSalvas() {
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("ok") })
        show(h)
        typeBInsteadOfAInTheEndpoint()
        node("visao-custom-endpoint").performImeAction()
        rule.runOnIdle { assertEquals("https://b.test/v1", h.settings.visionCustomEndpoint) }

        node(VISAO_TEST_BUTTON_TAG).performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertEquals("https://b.test/v1", seen.single().visionCustomEndpoint) }
    }

    @Test
    fun seOAnfitriaoNaoDevolveAsSettingsOTesteParteNoToqueComOQueFoiConfirmado() {
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("ok") })
        h.acceptsChanges = false
        show(h)
        typeBInsteadOfAInTheEndpoint()

        node(VISAO_TEST_BUTTON_TAG).performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle {
            assertEquals("https://b.test/v1", seen.single().visionCustomEndpoint)
            assertEquals("https://a.test/v1", h.settings.visionCustomEndpoint)
        }
    }

    /** Revisão 2, P2 nº 1: a chegada tardia das próprias Settings confirmadas não cancela nem apaga o teste que já partiu. */
    @Test
    fun aChegadaTardiaDasSettingsConfirmadasPreservaOTesteEmAndamento() {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; entered.complete(Unit); gate.await(); com.keyflow.vision.VisionResult.Text("Texto tardio") })
        h.holdChanges = true
        show(h)
        typeBInsteadOfAInTheEndpoint()

        node(VISAO_TEST_BUTTON_TAG).performClick()
        rule.waitUntil(5_000) { entered.isCompleted }
        // Passado o prazo antigo de 2 s, o retorno finalmente chega.
        rule.mainClock.advanceTimeBy(3_000)
        rule.runOnIdle { h.settings = checkNotNull(h.held) }
        rule.waitForIdle()
        gate.complete(Unit)

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        node(VISAO_TEST_RESPONSE_TAG).assertTextContains("Texto tardio", substring = true)
        rule.runOnIdle {
            assertEquals("https://b.test/v1", seen.single().visionCustomEndpoint)
        }
    }

    /** Revisão 2, P2 nº 2: um refresh com outra preferência que ainda tem o endpoint antigo não troca o pedido confirmado. */
    @Test
    fun umRefreshComOEndpointAntigoNaoTrocaOPedidoConfirmado() {
        val seen = mutableListOf<Settings>()
        val h = Harness(customSaved, visionTester = VisionTester { seen += it; com.keyflow.vision.VisionResult.Text("ok") })
        h.holdChanges = true
        show(h)
        typeBInsteadOfAInTheEndpoint()

        node(VISAO_TEST_BUTTON_TAG).performClick()
        // Outra preferência muda e a tela volta a receber o endpoint antigo.
        rule.runOnIdle { h.settings = h.settings.copy(autoPasteOnFocus = false) }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(VISAO_TEST_RESPONSE_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertEquals("https://b.test/v1", seen.single().visionCustomEndpoint) }
    }

    @Test
    fun trocarAOrigemCancelaOTesteEmAndamento() {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness(Settings(visionLocalModelId = e2b.id), installed = setOf(e2b.id),
            visionTester = VisionTester {
                try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                finally { stopped.complete(Unit) }
            })
        show(h)
        node(VISAO_TEST_BUTTON_TAG).performClick()
        rule.waitUntil(5_000) { entered.isCompleted }
        node(visionSourceTag(VisionSource.GOOGLE)).performClick()
        rule.waitUntil(5_000) { stopped.isCompleted }
        node(VISAO_TEST_BUTTON_TAG).assertTextEquals("Testar").assertIsNotEnabled()
        rule.onNodeWithTag(VISAO_TEST_RESPONSE_TAG).assertDoesNotExist()
    }
}

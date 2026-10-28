package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keyflow.auth.ConnectionState
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette

fun visionSourceTag(source: VisionSource): String = "visao-source-${source.name.lowercase()}"

const val VISAO_LOCAL_PRIVACY_TAG = "visao-local-privacy"
const val VISAO_LOCAL_DOWNLOAD_NOTE_TAG = "visao-local-download-note"
const val VISAO_LOCAL_UNSUPPORTED_TAG = "visao-local-unsupported"
const val VISAO_LOCAL_EMPTY_TAG = "visao-local-empty"
const val VISAO_CUSTOM_PRIVACY_TAG = "visao-custom-privacy"

/**
 * Aba Visão (CFG-V01/V02, vision-capture): a origem dos modelos que leem a captura da tela — **Local** (PaddleOCR e Gemma 4 no aparelho,
 * lista de download no mesmo molde da Transcrição e do Raciocínio), os provedores **OpenAI**, **Anthropic** e **Google** (login ou chave,
 * as mesmas conexões do Raciocínio, só com modelos que leem imagem) ou **Custom**. Créditos fica desabilitado e marcado como "Em breve";
 * configurações legadas caem no Local. É o desenho do [ReasoningPanel] com a lista e o seletor de modelos da Visão.
 *
 * [models] já vem composto com o catálogo da Visão ([com.keyflow.data.models.ModelCatalog.visionModels]); [openAiAuth] e as outras duas são
 * as portas para os módulos de autenticação, **as mesmas do Raciocínio** (PRV-01: conectar numa aba vale na outra). [tester] roda o OCR do Testar.
 */
@Composable
fun VisaoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    models: LocalModelsUi = LocalModelsUi.EMPTY_VISION,
    actions: LocalModelActions = LocalModelActions.None,
    // Falso num aparelho que não roda os modelos locais: a lista de download some, com o motivo.
    localSupported: Boolean = true,
    tester: OcrTester = OcrTester.None,
    initialTest: VisionTestState = VisionTestState(),
    openAiAuth: AuthActions = AuthActions.None,
    anthropicAuth: AuthActions = AuthActions.None,
    googleAuth: AuthActions = AuthActions.None,
    visionTester: VisionTester? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val activeSettings = settings.withoutCredits()
    val editor = rememberSettingsEditor(activeSettings, onSettingsChange)
    // Lido AQUI, no corpo do painel: quando a conexão muda, tudo abaixo recompõe (como no Raciocínio).
    val connection by remember(openAiAuth) { openAiAuth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val openAi = openAiPanelState(connection, openAiAuth.flows)
    val anthropicConnection by remember(anthropicAuth) { anthropicAuth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val googleConnection by remember(googleAuth) { googleAuth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val selectedConnection = when (activeSettings.visionSource) {
        VisionSource.OPENAI -> connection
        VisionSource.ANTHROPIC -> anthropicConnection
        VisionSource.GOOGLE -> googleConnection
        else -> ConnectionState.Disconnected
    }
    val availability = visionTestAvailability(activeSettings, models, selectedConnection is ConnectionState.Connected)
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Origem da visão")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Segmented(
                    options = VisionSource.entries,
                    selected = activeSettings.visionSource,
                    label = ::visionSourceLabel,
                    tag = ::visionSourceTag,
                    onSelect = { source -> editor.edit { it.copy(visionSource = source) } },
                    disabled = { it == VisionSource.CREDITS },
                    disabledLabel = { if (it == VisionSource.CREDITS) tr("Em breve") else null },
                )
                when (activeSettings.visionSource) {
                    VisionSource.CREDITS -> Unit
                    VisionSource.LOCAL ->
                        if (localSupported) VisaoLocal(activeSettings, onSettingsChange, models, actions) else LocalUnsupported()
                    VisionSource.OPENAI -> OpenAiPanel(activeSettings, onSettingsChange, openAiAuth, openAi, connection, VisionCloudSlot.OpenAi)
                    VisionSource.ANTHROPIC -> AnthropicPanel(activeSettings, onSettingsChange, anthropicAuth, VisionCloudSlot.Anthropic)
                    VisionSource.GOOGLE -> GooglePanel(activeSettings, onSettingsChange, googleAuth, VisionCloudSlot.Google)
                    VisionSource.CUSTOM -> VisaoCustomFields(activeSettings, editor::edit)
                }
            }
        }
        SettingsGroup(tr("Testar")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                VisaoTestArea(availability, tester, initialState = initialTest,
                    modelTester = visionTester?.let { current -> { current.run(activeSettings) } },
                    remote = activeSettings.visionSource != VisionSource.LOCAL,
                    requestKey = listOf(activeSettings.visionSource, activeSettings.visionLocalModelId,
                        activeSettings.visionOpenAiModel, activeSettings.visionAnthropicModel, activeSettings.visionGoogleModel,
                        activeSettings.visionCustomEndpoint, activeSettings.visionCustomModel,
                        activeSettings.visionCustomHeaderName, activeSettings.visionCustomHeaderValue, selectedConnection, availability),
                )
            }
        }
    }
}

// "Local", "OpenAI", "Anthropic", "Google" e "Custom" são nomes próprios/técnicos.
private fun visionSourceLabel(source: VisionSource): String = when (source) {
    VisionSource.CREDITS -> tr("Créditos")
    VisionSource.LOCAL -> "Local"
    VisionSource.OPENAI -> "OpenAI"
    VisionSource.ANTHROPIC -> "Anthropic"
    VisionSource.GOOGLE -> "Google"
    VisionSource.CUSTOM -> "Custom"
}

@Composable
private fun VisaoCustomFields(settings: Settings, onEdit: ((Settings) -> Settings) -> Unit) {
    Column {
        CustomProviderFields(
            tagPrefix = "visao",
            endpoint = settings.visionCustomEndpoint,
            headerName = settings.visionCustomHeaderName,
            headerValue = settings.visionCustomHeaderValue,
            model = settings.visionCustomModel,
            onEndpoint = { onEdit { current -> current.copy(visionCustomEndpoint = it) } },
            onHeaderName = { onEdit { current -> current.copy(visionCustomHeaderName = it) } },
            onHeaderValue = { onEdit { current -> current.copy(visionCustomHeaderValue = it) } },
            onModel = { onEdit { current -> current.copy(visionCustomModel = it) } },
            hints = listOf(
                tr("Padrão compatível com OpenAI Chat Completions com imagem — informe o endpoint e credenciais do seu serviço."),
            ),
        )
        // PRV-04: a imagem da captura sai do aparelho para o endpoint digitado.
        SettingSub(
            tr("A imagem da captura é enviada ao endpoint que você informar; as credenciais ficam só neste aparelho."),
            Modifier.padding(top = 8.dp).testTag(VISAO_CUSTOM_PRIVACY_TAG),
        )
    }
}

@Composable
private fun VisaoLocal(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    models: LocalModelsUi,
    actions: LocalModelActions,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Modelos no aparelho"))
        if (models.items.isEmpty()) {
            // O release só lista o que já foi provado num aparelho (ModelCatalog.visionModels): enquanto nenhum foi, a lista está vazia.
            SettingSub(
                tr("Os modelos de visão no aparelho chegam em uma atualização futura."),
                Modifier.padding(top = 12.dp).testTag(VISAO_LOCAL_EMPTY_TAG),
            )
        }
        ModelList(
            models = models,
            actions = actions,
            // Escolher o modelo só grava a escolha: carregar o Gemma na memória é do motor de visão (VC-T15), não da aba.
            onSelect = { id -> onSettingsChange(settings.copy(visionLocalModelId = id)) },
            // Apagar o selecionado deixa a Visão sem modelo local. Os Gemma são o MESMO arquivo do Raciocínio: apagar daqui também o tira de lá.
            onDeleted = { id -> onSettingsChange(settings.withoutLocalModel(id)) },
            tags = ModelListTags.Visao,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — a captura da tela e as imagens nunca saem do dispositivo. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(VISAO_LOCAL_PRIVACY_TAG),
        )
        SettingSub(
            tr("O download dos modelos vem do Hugging Face, que vê o endereço IP do aparelho. Os modelos Gemma 4 são os mesmos do Raciocínio: baixados lá, aparecem aqui."),
            Modifier.padding(top = 8.dp).testTag(VISAO_LOCAL_DOWNLOAD_NOTE_TAG),
        )
    }
}

/** Aparelho sem os modelos locais: nada de baixar um modelo que não roda — a nuvem segue disponível. */
@Composable
private fun LocalUnsupported() {
    SettingSub(
        tr("Este aparelho não suporta os modelos locais de visão."),
        Modifier.padding(top = 20.dp).testTag(VISAO_LOCAL_UNSUPPORTED_TAG),
    )
}

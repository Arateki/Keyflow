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
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette

fun reasoningSourceTag(source: ReasoningSource): String = "raciocinio-source-${source.name.lowercase()}"

const val RACIOCINIO_LOCAL_PRIVACY_TAG = "raciocinio-local-privacy"
const val RACIOCINIO_OPENAI_PRIVACY_TAG = "raciocinio-openai-privacy"
const val RACIOCINIO_LOCAL_UNSUPPORTED_TAG = "raciocinio-local-unsupported"

/**
 * Aba Raciocínio (RSC-01..06): a origem do modelo de linguagem — **Local** (modelo de texto no aparelho, lista de
 * download no mesmo molde da Transcrição) ou uma origem de nuvem/custom. Créditos fica desabilitado e marcado como
 * "Em breve"; configurações legadas caem no Local. Independe da origem da transcrição e nunca lista modelos de fala.
 * [models] já vem composto com o catálogo de TEXTO; [auth] é a porta para o módulo de autenticação.
 */
@Composable
fun ReasoningPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    models: LocalModelsUi = LocalModelsUi.EMPTY_TEXT,
    actions: LocalModelActions = LocalModelActions.None,
    // Qual motor responde com estas Settings — ou por que nenhum. Sem fiação (galeria, testes), nenhum.
    resolve: (Settings) -> ReasoningResolution = { ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL) },
    auth: AuthActions = AuthActions.None,
    anthropicAuth: AuthActions = AuthActions.None,
    googleAuth: AuthActions = AuthActions.None,
    initialTest: TestState = TestState(),
    // Falso num aparelho que não roda o motor local (ARM de 32 bits): a lista de download some, com o motivo.
    localSupported: Boolean = true,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val activeSettings = settings.withoutCredits()
    val editor = rememberSettingsEditor(activeSettings, onSettingsChange)
    // Lido AQUI, no corpo do painel: quando a conexão muda, tudo abaixo — inclusive o "pronto para testar" — recompõe.
    val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val anthropicConnection by remember(anthropicAuth) { anthropicAuth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val googleConnection by remember(googleAuth) { googleAuth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val openAi = openAiPanelState(connection, auth.flows)
    // O motor pronto (ou o motivo de não haver) depende das Settings, da conexão e dos modelos instalados.
    val resolution = remember(activeSettings, connection, anthropicConnection, googleConnection, models, resolve) { resolve(activeSettings) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Origem do raciocínio")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Segmented(
                    options = ReasoningSource.entries,
                    selected = activeSettings.reasoningSource,
                    label = ::reasoningSourceLabel,
                    tag = ::reasoningSourceTag,
                    onSelect = { source -> editor.edit { it.copy(reasoningSource = source) } },
                    disabled = { it == ReasoningSource.CREDITS },
                    disabledLabel = { if (it == ReasoningSource.CREDITS) tr("Em breve") else null },
                )
                when (activeSettings.reasoningSource) {
                    ReasoningSource.CREDITS -> Unit
                    ReasoningSource.LOCAL ->
                        if (localSupported) ReasoningLocal(activeSettings, onSettingsChange, models, actions) else LocalUnsupported()
                    ReasoningSource.OPENAI -> OpenAiPanel(activeSettings, onSettingsChange, auth, openAi, connection)
                    ReasoningSource.ANTHROPIC -> AnthropicPanel(activeSettings, onSettingsChange, anthropicAuth)
                    ReasoningSource.GOOGLE -> GooglePanel(activeSettings, onSettingsChange, googleAuth)
                    ReasoningSource.CUSTOM -> ReasoningCustomFields(activeSettings, editor::edit)
                }
            }
        }
        SettingsGroup(tr("Testar")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                // "Manage usage" só faz sentido quando quem responde é o PLANO do ChatGPT (não a chave de API).
                val manageUsage = auth.usageUrl?.takeIf { activeSettings.reasoningSource == ReasoningSource.OPENAI && openAi.status == OpenAiStatus.CONNECTED_LOGIN }
                ReasoningTestArea(resolution, onManageUsage = manageUsage?.let { url -> { auth.openUrl(url) } }, initialState = initialTest)
            }
        }
    }
}

// "Local", "OpenAI", "Anthropic", "Google" e "Custom" são nomes próprios/técnicos.
private fun reasoningSourceLabel(source: ReasoningSource): String = when (source) {
    ReasoningSource.CREDITS -> tr("Créditos")
    ReasoningSource.LOCAL -> "Local"
    ReasoningSource.OPENAI -> "OpenAI"
    ReasoningSource.ANTHROPIC -> "Anthropic"
    ReasoningSource.GOOGLE -> "Google"
    ReasoningSource.CUSTOM -> "Custom"
}

@Composable
private fun ReasoningCustomFields(settings: Settings, onEdit: ((Settings) -> Settings) -> Unit) {
    CustomProviderFields(
        tagPrefix = "raciocinio",
        endpoint = settings.reasoningCustomEndpoint,
        headerName = settings.reasoningCustomHeaderName,
        headerValue = settings.reasoningCustomHeaderValue,
        model = settings.reasoningCustomModel,
        onEndpoint = { onEdit { current -> current.copy(reasoningCustomEndpoint = it) } },
        onHeaderName = { onEdit { current -> current.copy(reasoningCustomHeaderName = it) } },
        onHeaderValue = { onEdit { current -> current.copy(reasoningCustomHeaderValue = it) } },
        onModel = { onEdit { current -> current.copy(reasoningCustomModel = it) } },
        hints = listOf(tr("Padrão compatível com OpenAI Chat Completions — informe o endpoint e credenciais do seu serviço.")),
    )
}

@Composable
private fun ReasoningLocal(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    models: LocalModelsUi,
    actions: LocalModelActions,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Modelos no aparelho"))
        ModelList(
            models = models,
            actions = actions,
            onSelect = { id ->
                onSettingsChange(settings.copy(reasoningLocalModelId = id))
                actions.prepare(id)
            },
            // Apagar o selecionado deixa o Raciocínio sem modelo local escolhido (e a Visão também, se ela usava o mesmo Gemma 4).
            onDeleted = { id -> if (settings.reasoningLocalModelId == id || settings.visionLocalModelId == id) onSettingsChange(settings.withoutLocalModel(id)) },
            tags = ModelListTags.Raciocinio,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — nada do que você escrever sai do dispositivo. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(RACIOCINIO_LOCAL_PRIVACY_TAG),
        )
    }
}

/** Aparelho sem o motor local (ARM de 32 bits): nada de baixar gigabytes de um modelo que não roda — a nuvem segue disponível. */
@Composable
private fun LocalUnsupported() {
    SettingSub(
        tr("Este aparelho não roda modelos locais (o motor só existe para processadores de 64 bits). Use a OpenAI."),
        Modifier.padding(top = 20.dp).testTag(RACIOCINIO_LOCAL_UNSUPPORTED_TAG),
    )
}

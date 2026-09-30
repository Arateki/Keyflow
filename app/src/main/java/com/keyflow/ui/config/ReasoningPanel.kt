package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette

fun reasoningSourceTag(source: ReasoningSource): String = "raciocinio-source-${source.name.lowercase()}"

const val RACIOCINIO_LOCAL_PRIVACY_TAG = "raciocinio-local-privacy"
const val RACIOCINIO_OPENAI_PRIVACY_TAG = "raciocinio-openai-privacy"

/**
 * Aba Raciocínio (RSC-01..06): a origem do modelo de linguagem — **Local** (modelo de texto no aparelho, lista de
 * download no mesmo molde da Transcrição) ou **OpenAI** (nuvem: login, chave e modelo da conta). Independe da origem da
 * transcrição e nunca lista os modelos de fala. [models] já vem composto com o catálogo de TEXTO; [auth] é a porta para o
 * módulo de autenticação (sem ele, a origem OpenAI não tem o que conectar).
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
    initialTest: TestState = TestState(),
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    // Lido AQUI, no corpo do painel: quando a conexão muda, tudo abaixo — inclusive o "pronto para testar" — recompõe.
    val connection by remember(auth) { auth.state() }.collectAsState(ConnectionState.Disconnected)
    val openAi = openAiPanelState(connection, auth.flows)
    // O motor pronto (ou o motivo de não haver) depende das Settings, da conexão e dos modelos instalados.
    val resolution = remember(settings, connection, models, resolve) { resolve(settings) }
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
                    selected = settings.reasoningSource,
                    label = ::reasoningSourceLabel,
                    tag = ::reasoningSourceTag,
                    onSelect = { onSettingsChange(settings.copy(reasoningSource = it)) },
                )
                when (settings.reasoningSource) {
                    ReasoningSource.LOCAL -> ReasoningLocal(settings, onSettingsChange, models, actions)
                    ReasoningSource.OPENAI -> OpenAiPanel(settings, onSettingsChange, auth, openAi, connection)
                }
            }
        }
        SettingsGroup(tr("Testar")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                // "Manage usage" só faz sentido quando quem responde é o PLANO do ChatGPT (não a chave de API).
                val manageUsage = auth.usageUrl?.takeIf { settings.reasoningSource == ReasoningSource.OPENAI && openAi.status == OpenAiStatus.CONNECTED_LOGIN }
                ReasoningTestArea(resolution, onManageUsage = manageUsage?.let { url -> { auth.openUrl(url) } }, initialState = initialTest)
            }
        }
    }
}

// "Local" e "OpenAI" são nomes próprios, iguais em todos os idiomas (como na origem da Transcrição).
private fun reasoningSourceLabel(source: ReasoningSource): String = when (source) {
    ReasoningSource.LOCAL -> "Local"
    ReasoningSource.OPENAI -> "OpenAI"
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
            onSelect = { id -> onSettingsChange(settings.copy(reasoningLocalModelId = id)) },
            // Apagar o selecionado deixa o Raciocínio sem modelo local escolhido.
            onDeleted = { id -> if (settings.reasoningLocalModelId == id) onSettingsChange(settings.copy(reasoningLocalModelId = null)) },
            tags = ModelListTags.Raciocinio,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — nada do que você escrever sai do dispositivo. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(RACIOCINIO_LOCAL_PRIVACY_TAG),
        )
    }
}

package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
 * download no mesmo molde da Transcrição) ou **OpenAI** (nuvem; o conteúdo vem na RSN-T16). Independe da origem da
 * transcrição e nunca lista os modelos de fala. [models] já vem composto com o catálogo de TEXTO.
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
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
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
                    ReasoningSource.OPENAI -> ReasoningOpenAi()
                }
            }
        }
        SettingsGroup(tr("Testar")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                ReasoningTestArea(resolve(settings))
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

/** Provisório até a RSN-T16 (login ChatGPT, chave de API e modelo da conta). */
@Composable
private fun ReasoningOpenAi() {
    Column(Modifier.padding(top = 20.dp)) {
        SettingSub(tr("Em breve: entrar com a conta do ChatGPT ou usar uma chave de API."))
        SettingSub(
            tr("O texto do teste é enviado à OpenAI; as credenciais ficam só neste aparelho."),
            Modifier.padding(top = 16.dp).testTag(RACIOCINIO_OPENAI_PRIVACY_TAG),
        )
    }
}

package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keyflow.data.Settings
import com.keyflow.i18n.tr

fun localModelTag(id: String) = "voz-local-model-$id"
fun localDownloadTag(id: String) = "voz-local-download-$id"
fun localCancelTag(id: String) = "voz-local-cancel-$id"
fun localDeleteTag(id: String) = "voz-local-delete-$id"
fun localProgressTag(id: String) = "voz-local-progress-$id"
fun localHeavyTag(id: String) = "voz-local-heavy-$id"
fun localNoticeTag(id: String) = "voz-local-notice-$id"
fun localConfirmTag(id: String) = "voz-local-confirm-$id"

const val VOZ_LOCAL_CONFIRM_YES_TAG = "voz-local-confirm-yes"
const val VOZ_LOCAL_CONFIRM_NO_TAG = "voz-local-confirm-no"
const val VOZ_LOCAL_LANGUAGE_TAG = "voz-local-language"
const val VOZ_LOCAL_PRIVACY_TAG = "voz-local-privacy"

/**
 * Gerenciador de modelos da origem Local (MGR-01..06, CAT-03): a lista de modelos de fala ([ModelList]) e, abaixo, o
 * idioma e a nota de privacidade. Idioma e seleção vão pelas Settings; download e disco, pelo [actions].
 */
@Composable
internal fun LocalPanel(
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
            onSelect = { id -> onSettingsChange(settings.copy(localModelId = id)) },
            // MGR-04: apagar o selecionado deixa a origem sem modelo (→ CONFIG_MISSING).
            onDeleted = { id -> if (settings.localModelId == id) onSettingsChange(settings.copy(localModelId = null)) },
            tags = ModelListTags.Voz,
        )
        Dropdown(
            label = tr("Idioma"),
            options = LANGUAGE_OPTIONS,
            selected = settings.localLanguage.orEmpty(),
            onSelect = { onSettingsChange(settings.copy(localLanguage = it.ifBlank { null })) },
            tag = VOZ_LOCAL_LANGUAGE_TAG,
            modifier = Modifier.padding(top = 18.dp),
            display = ::languageLabel,
        )
        SettingSub(
            tr("Vale para os modelos Rápido e Equilibrado; o Máxima qualidade reconhece o idioma sozinho."),
            Modifier.padding(top = 8.dp),
        )
        SettingSub(
            tr("Processado 100% neste aparelho — o áudio nunca sai do dispositivo. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(VOZ_LOCAL_PRIVACY_TAG),
        )
    }
}

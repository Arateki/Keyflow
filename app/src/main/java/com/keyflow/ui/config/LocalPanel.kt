package com.keyflow.ui.config

import com.keyflow.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Confirmação aberta num card (DLM-04, MGR-04) — inline, no próprio item, em vez de janela modal. */
private sealed interface Pending {
    val spec: ModelSpec
    data class Metered(override val spec: ModelSpec, val bytes: Long) : Pending
    data class Delete(override val spec: ModelSpec) : Pending
}

/**
 * Gerenciador de modelos da origem Local (MGR-01..06, CAT-03): lista do catálogo com o estado de cada
 * modelo, baixar/cancelar/apagar, tocar num baixado seleciona. Idioma e seleção vão pelas Settings;
 * download e disco, pelo [actions].
 */
@Composable
internal fun LocalPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    models: LocalModelsUi,
    actions: LocalModelActions,
) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    val notices = remember { mutableStateMapOf<String, String>() }

    fun download(spec: ModelSpec, allowMetered: Boolean) {
        notices.remove(spec.id)
        scope.launch {
            val result = withContext(Dispatchers.IO) { actions.download(spec, allowMetered) }
            if (result is StartResult.NeedsMeteredConfirmation) {
                pending = Pending.Metered(spec, result.bytes)
            } else {
                startProblem(result)?.let { notices[spec.id] = it }
            }
        }
    }

    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Modelos no aparelho"))
        models.items.forEach { item ->
            ModelCard(
                item = item,
                downloadBlocked = models.downloadActive,
                pending = pending?.takeIf { it.spec.id == item.spec.id },
                notice = notices[item.spec.id],
                onSelect = { onSettingsChange(settings.copy(localModelId = item.spec.id)) },
                onDownload = { download(item.spec, allowMetered = false) },
                onCancel = { actions.cancel(item.spec.id) },
                onDelete = { pending = Pending.Delete(item.spec) },
                onConfirm = {
                    when (val p = pending) {
                        is Pending.Metered -> download(p.spec, allowMetered = true)
                        is Pending.Delete -> {
                            actions.delete(p.spec.id)
                            // MGR-04: apagar o selecionado deixa a origem sem modelo (→ CONFIG_MISSING).
                            if (settings.localModelId == p.spec.id) onSettingsChange(settings.copy(localModelId = null))
                        }
                        null -> Unit
                    }
                    pending = null
                },
                onDismiss = { pending = null },
            )
        }
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

@Composable
private fun ModelCard(
    item: LocalModelItem,
    downloadBlocked: Boolean,
    pending: Pending?,
    notice: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val spec = item.spec
    val installed = item.status == LocalModelStatus.INSTALLED
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .border(1.dp, if (item.selected) palette.teal else palette.line2, shape)
            // Dentro do cartão do grupo "Origem": cada modelo é um bloco recuado (papel do fundo).
            .background(palette.bg)
            // Instalado = opção de rádio: o leitor de tela anuncia "selecionado" no card inteiro.
            .then(
                if (installed) {
                    Modifier.selectable(selected = item.selected, onClick = onSelect, role = Role.RadioButton)
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 12.dp, top = 15.dp, bottom = 15.dp)
            .testTag(localModelTag(spec.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                KfRadio(item.selected, Modifier.padding(end = 12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(tr(spec.displayName), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp)
                if (spec.modelName.isNotBlank()) {
                    Text(spec.modelName, color = palette.muted, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 9.5.sp)
                }
                Text(
                    "${formatModelSize(spec.totalBytes)} · ${tr(spec.languages)}",
                    color = palette.faint,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Light,
                    fontSize = 9.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            when (item.status) {
                LocalModelStatus.AVAILABLE -> CardAction(
                    tr("Baixar"),
                    enabled = !downloadBlocked && pending == null,
                    tag = localDownloadTag(spec.id),
                    onClick = onDownload,
                )
                LocalModelStatus.DOWNLOADING -> CardAction(tr("Cancelar"), tag = localCancelTag(spec.id), onClick = onCancel)
                LocalModelStatus.INSTALLED -> CardAction(
                    tr("Apagar"),
                    enabled = pending == null,
                    tag = localDeleteTag(spec.id),
                    onClick = onDelete,
                    color = palette.terracotta,
                )
            }
        }
        SettingSub(tr(spec.summary), Modifier.padding(top = 10.dp))
        if (item.heavy) {
            Text(
                tr("Pode ser pesado neste aparelho."),
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 6.dp).testTag(localHeavyTag(spec.id)),
            )
        }
        item.progress?.let { progress ->
            Row(
                // Barra + rótulo lidos juntos pelo leitor de tela ("42%", "Aguardando rede").
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag(localProgressTag(spec.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.line2),
                ) {
                    Box(Modifier.fillMaxWidth(progress.fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.teal))
                }
                Text(
                    downloadStateLabel(progress),
                    color = palette.muted,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Light,
                    fontSize = 9.5.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        val message = notice ?: item.failure?.let(::failureMessage)
        if (message != null) {
            Text(
                message,
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(localNoticeTag(spec.id)),
            )
        }
        if (pending != null) {
            ConfirmRow(
                question = when (pending) {
                    is Pending.Metered -> tr("Baixar %s por dados móveis?", formatModelSize(pending.bytes))
                    is Pending.Delete -> tr("Apagar %s? Libera %s.", tr(spec.displayName), formatModelSize(spec.totalBytes))
                },
                confirm = if (pending is Pending.Metered) tr("Baixar") else tr("Apagar"),
                tag = localConfirmTag(spec.id),
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun CardAction(
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = LocalKeyFlowPalette.current.teal,
) {
    val palette = LocalKeyFlowPalette.current
    val tint = if (enabled) color else palette.faint
    Text(
        label,
        color = tint,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

@Composable
private fun ConfirmRow(question: String, confirm: String, tag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.panel)
            .padding(14.dp)
            .testTag(tag),
    ) {
        Text(question, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 12.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            CardAction(tr("Cancelar"), tag = VOZ_LOCAL_CONFIRM_NO_TAG, onClick = onDismiss, color = palette.muted)
            CardAction(confirm, tag = VOZ_LOCAL_CONFIRM_YES_TAG, onClick = onConfirm)
        }
    }
}

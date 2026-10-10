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

/**
 * As tags de teste da lista de modelos. A Transcrição e o Raciocínio usam a MESMA lista ([ModelList]); só as tags mudam,
 * para os testes de cada aba encontrarem os seus nós sem esbarrar nos da outra (RSC-06).
 */
internal data class ModelListTags(
    val model: (String) -> String,
    val download: (String) -> String,
    val cancel: (String) -> String,
    val delete: (String) -> String,
    val progress: (String) -> String,
    val heavy: (String) -> String,
    val notice: (String) -> String,
    val confirm: (String) -> String,
    val confirmYes: String,
    val confirmNo: String,
) {
    companion object {
        /** As tags da aba Transcrição — os testes existentes dependem delas, não mudam. */
        val Voz = ModelListTags(
            ::localModelTag, ::localDownloadTag, ::localCancelTag, ::localDeleteTag, ::localProgressTag,
            ::localHeavyTag, ::localNoticeTag, ::localConfirmTag, VOZ_LOCAL_CONFIRM_YES_TAG, VOZ_LOCAL_CONFIRM_NO_TAG,
        )

        /** As tags da aba Raciocínio. */
        val Raciocinio = ModelListTags(
            { "raciocinio-local-model-$it" }, { "raciocinio-local-download-$it" }, { "raciocinio-local-cancel-$it" },
            { "raciocinio-local-delete-$it" }, { "raciocinio-local-progress-$it" }, { "raciocinio-local-heavy-$it" },
            { "raciocinio-local-notice-$it" }, { "raciocinio-local-confirm-$it" },
            "raciocinio-local-confirm-yes", "raciocinio-local-confirm-no",
        )

        /** As tags da aba Visão. */
        val Visao = ModelListTags(
            { "visao-local-model-$it" }, { "visao-local-download-$it" }, { "visao-local-cancel-$it" },
            { "visao-local-delete-$it" }, { "visao-local-progress-$it" }, { "visao-local-heavy-$it" },
            { "visao-local-notice-$it" }, { "visao-local-confirm-$it" },
            "visao-local-confirm-yes", "visao-local-confirm-no",
        )

        /** As tags da aba Voz (Fala). */
        val Fala = ModelListTags(
            { "fala-local-model-$it" }, { "fala-local-download-$it" }, { "fala-local-cancel-$it" },
            { "fala-local-delete-$it" }, { "fala-local-progress-$it" }, { "fala-local-heavy-$it" },
            { "fala-local-notice-$it" }, { "fala-local-confirm-$it" },
            "fala-local-confirm-yes", "fala-local-confirm-no",
        )
    }
}

/** Confirmação aberta num card (DLM-04, MGR-04) — inline, no próprio item, em vez de janela modal. */
private sealed interface Pending {
    val spec: ModelSpec
    data class Metered(override val spec: ModelSpec, val bytes: Long) : Pending
    data class Delete(override val spec: ModelSpec) : Pending
}

/**
 * A lista de modelos com o estado de cada um: baixar (com confirmação em rede medida), cancelar, apagar (com
 * confirmação) e tocar num baixado para selecioná-lo (MGR-01..04, CAT-03). Compartilhada entre Transcrição e Raciocínio.
 * A seleção já vem em `models.items[].selected`; [onSelect] e [onDeleted] avisam o painel dono das Settings.
 */
@Composable
internal fun ModelList(
    models: LocalModelsUi,
    actions: LocalModelActions,
    onSelect: (modelId: String) -> Unit,
    onDeleted: (modelId: String) -> Unit,
    tags: ModelListTags,
) {
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

    models.items.forEach { item ->
        ModelCard(
            item = item,
            tags = tags,
            downloadBlocked = models.downloadActive,
            pending = pending?.takeIf { it.spec.id == item.spec.id },
            notice = notices[item.spec.id],
            onSelect = { onSelect(item.spec.id) },
            onDownload = { download(item.spec, allowMetered = false) },
            onCancel = { actions.cancel(item.spec.id) },
            onDelete = { pending = Pending.Delete(item.spec) },
            onConfirm = {
                when (val p = pending) {
                    is Pending.Metered -> download(p.spec, allowMetered = true)
                    is Pending.Delete -> {
                        actions.delete(p.spec.id)
                        onDeleted(p.spec.id)
                    }
                    null -> Unit
                }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ModelCard(
    item: LocalModelItem,
    tags: ModelListTags,
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
            .testTag(tags.model(spec.id)),
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
                    tag = tags.download(spec.id),
                    onClick = onDownload,
                )
                LocalModelStatus.DOWNLOADING -> CardAction(tr("Cancelar"), tag = tags.cancel(spec.id), onClick = onCancel)
                LocalModelStatus.INSTALLED -> CardAction(
                    tr("Apagar"),
                    enabled = pending == null,
                    tag = tags.delete(spec.id),
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
                modifier = Modifier.padding(top = 6.dp).testTag(tags.heavy(spec.id)),
            )
        }
        item.progress?.let { progress ->
            Row(
                // Barra + rótulo lidos juntos pelo leitor de tela ("42%", "Aguardando rede").
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag(tags.progress(spec.id)),
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
                modifier = Modifier.padding(top = 8.dp).testTag(tags.notice(spec.id)),
            )
        }
        if (pending != null) {
            ConfirmRow(
                question = when (pending) {
                    is Pending.Metered -> tr("Baixar %s por dados móveis?", formatModelSize(pending.bytes))
                    is Pending.Delete -> tr("Apagar %s? Libera %s.", tr(spec.displayName), formatModelSize(spec.totalBytes))
                },
                confirm = if (pending is Pending.Metered) tr("Baixar") else tr("Apagar"),
                tag = tags.confirm(spec.id),
                tags = tags,
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
private fun ConfirmRow(question: String, confirm: String, tag: String, tags: ModelListTags, onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            CardAction(tr("Cancelar"), tag = tags.confirmNo, onClick = onDismiss, color = palette.muted)
            CardAction(confirm, tag = tags.confirmYes, onClick = onConfirm)
        }
    }
}

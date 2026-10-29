package com.keyflow.ui.config

import com.keyflow.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.ComputeBackend
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
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
    /** O chip de um backend no seletor "Roda em" do card; só o Raciocínio mostra o seletor, mas o padrão vale para qualquer aba. */
    val backend: (String, ComputeBackend) -> String = { id, option -> "${model(id)}-backend-${option.name.lowercase()}" },
    /** O aviso de "a primeira carga na GPU pode levar minutos" e o de "voltou para a CPU", sob o seletor. */
    val backendHint: (String) -> String = { id -> "${model(id)}-backend-hint" },
    val backendNotice: (String) -> String = { id -> "${model(id)}-backend-notice" },
    /** A marca de "baixado" (check) do card instalado. */
    val downloaded: (String) -> String = { id -> "${model(id)}-downloaded" },
    /** Uma etiqueta do card (idioma, pensamento, capacidade, RAM): `chave` é [CardTag.key]. */
    val tag: (String, String) -> String = { id, key -> "${model(id)}-tag-$key" },
    /** Quais etiquetas a aba mostra: o mesmo modelo (Gemma 4) fala de texto no Raciocínio e de imagem na Visão. */
    val tagScope: CardTagScope = CardTagScope.ALL,
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
            tagScope = CardTagScope.REASONING,
        )

        /** As tags da aba Visão. */
        val Visao = ModelListTags(
            { "visao-local-model-$it" }, { "visao-local-download-$it" }, { "visao-local-cancel-$it" },
            { "visao-local-delete-$it" }, { "visao-local-progress-$it" }, { "visao-local-heavy-$it" },
            { "visao-local-notice-$it" }, { "visao-local-confirm-$it" },
            "visao-local-confirm-yes", "visao-local-confirm-no",
            tagScope = CardTagScope.VISION,
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
    var license by remember { mutableStateOf<ModelSpec?>(null) }
    val notices = remember { mutableStateMapOf<String, String>() }

    fun download(spec: ModelSpec, allowMetered: Boolean, recordAcceptance: Boolean = false) {
        notices.remove(spec.id)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (recordAcceptance) actions.acceptVoiceLicense(spec.id)
                actions.download(spec, allowMetered)
            }
            when (result) {
                is StartResult.NeedsMeteredConfirmation -> pending = Pending.Metered(spec, result.bytes)
                is StartResult.NeedsLicenseAcceptance -> license = spec
                else -> startProblem(result)?.let { notices[spec.id] = it }
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
            onBackend = { actions.chooseBackend(item.spec.id, it, prepare = item.selected) },
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
    if (license != null) {
        VoiceLicenseDialog(
            onAccept = {
                val spec = license ?: return@VoiceLicenseDialog
                license = null
                download(spec, allowMetered = false, recordAcceptance = true)
            },
            onDismiss = { license = null },
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
    onBackend: (ComputeBackend) -> Unit,
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
    // Três estados que se distinguem: não baixado (fio neutro e "Baixar"), baixado (fio teal suave e o check "Baixado") e selecionado
    // (fio teal cheio, mais grosso, e o rádio preenchido). O leitor de tela lê "Baixado" e "selecionado" separados.
    val outline = when {
        item.selected -> palette.teal
        installed -> palette.teal.copy(alpha = 0.4f)
        else -> palette.line2
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .border(if (item.selected) 1.5.dp else 1.dp, outline, shape)
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
        // Cabeçalho: [rádio] [logo do fabricante] nome ✓ / modelo · tamanho. O botão de ação desce para a linha das etiquetas: com ele
        // aqui o nome (ex.: "Máxima qualidade") quebrava em duas linhas no card instalado.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                KfRadio(item.selected, Modifier.padding(end = 12.dp))
            }
            spec.vendor?.let { ModelVendorLogo(it, size = VENDOR_LOGO_SIZE, modifier = Modifier.padding(end = 12.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(spec.displayName),
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (installed) DownloadedMark(tags.downloaded(spec.id), Modifier.padding(start = 6.dp))
                }
                ModelMeta(spec.modelName, formatModelSize(spec.totalBytes))
            }
        }
        // Etiquetas (idioma, pensamento, capacidades, RAM) e, no fim da última linha, a ação do card (quebra para a linha de baixo se não couber).
        val cardTags = modelTags(spec, tags.tagScope)
        val action: @Composable () -> Unit = {
            when (item.status) {
                LocalModelStatus.AVAILABLE -> CardAction(
                    if (item.outdated) tr("Atualizar modelo") else tr("Baixar"),
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
        ModelTagRow(cardTags, tags, spec.id, Modifier.padding(top = 12.dp), trailing = action)
        if (item.showsBackendChooser) {
            BackendChooser(item, tags, onBackend)
        }
        if (item.outdated) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Há uma versão nova deste modelo. Atualize para voltar a usá-lo."),
                    color = palette.terracotta,
                    fontFamily = SpaceGrotesk,
                    fontSize = 10.5.sp,
                    modifier = Modifier.weight(1f).testTag("${tags.model(spec.id)}-outdated"),
                )
                // A cópia antiga segue no aparelho até a nova chegar: quem precisa do espaço dela apaga daqui (não aparece baixando).
                if (item.status == LocalModelStatus.AVAILABLE) {
                    CardAction(
                        tr("Apagar"),
                        enabled = pending == null,
                        tag = tags.delete(spec.id),
                        onClick = onDelete,
                        color = palette.terracotta,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
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
        item.preparation?.takeIf { it.failure == null }?.let { preparation ->
            ModelPreparationIndicator(preparation, Modifier.padding(top = 10.dp).testTag("${tags.model(spec.id)}-preparation"))
        }
        val message = notice ?: item.preparation?.failure ?: item.failure?.let(::failureMessage)
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
                    is Pending.Delete ->
                        if (item.outdated) tr("Apagar a versão antiga de %s? É preciso baixar de novo para usá-lo.", tr(spec.displayName))
                        else tr("Apagar %s? Libera %s.", tr(spec.displayName), formatModelSize(spec.totalBytes))
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

/**
 * O seletor "Roda em  [CPU | GPU]" do card do modelo de texto local: pequeno e na cor do resto do card, só com os backends que o
 * aparelho oferece (o chamador só o desenha com mais de um). Escolher a GPU pela primeira vez avisa que a primeira carga pode levar
 * minutos (compila os programas da GPU; as seguintes usam o cache); se a carga na GPU falhou e o app voltou para a CPU, o card diz.
 */
@Composable
private fun BackendChooser(item: LocalModelItem, tags: ModelListTags, onBackend: (ComputeBackend) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val id = item.spec.id
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("Roda em"),
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 10.5.sp,
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(palette.panel).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item.backendOptions.forEach { option ->
                val active = option == item.backend
                Text(
                    option.name,
                    color = if (active) palette.fg else palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (active) Modifier.background(raisedFill(palette)).border(1.dp, palette.line, RoundedCornerShape(8.dp)) else Modifier)
                        .selectable(selected = active, onClick = { onBackend(option) }, role = Role.RadioButton)
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                        .testTag(tags.backend(id, option)),
                )
            }
        }
    }
    if (item.gpuFirstLoad && item.backendFellBackFrom == null) {
        Text(
            tr("Na GPU, a primeira carga pode levar alguns minutos."),
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 6.dp).testTag(tags.backendHint(id)),
        )
    }
    if (item.backendFellBackFrom != null) {
        Text(
            tr("A GPU não funcionou neste aparelho; usando a CPU."),
            color = palette.terracotta,
            fontFamily = SpaceGrotesk,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 6.dp).testTag(tags.backendNotice(id)),
        )
    }
}

/** Tamanho do logo do fabricante no cabeçalho do card: grande o bastante para ler a marca, centralizado no bloco de texto. */
private val VENDOR_LOGO_SIZE = 30.dp

/**
 * "Qwen3 0.6B · ~585 MB" numa linha só. Se não couber, o modelo e o tamanho vão em duas linhas, sem o "·": quebrar a linha no meio
 * deixava o ponto sozinho no fim ou no começo de uma delas.
 */
@Composable
private fun ModelMeta(modelName: String, size: String) {
    val palette = LocalKeyFlowPalette.current
    var stacked by remember { mutableStateOf(false) }
    val style = TextStyle(color = palette.faint, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 9.5.sp)
    if (modelName.isBlank()) {
        Text(size, style = style, modifier = Modifier.padding(top = 2.dp))
    } else if (stacked) {
        Column(Modifier.padding(top = 2.dp)) {
            Text(modelName, style = style.copy(color = palette.muted))
            Text(size, style = style)
        }
    } else {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = palette.muted)) { append(modelName) }
                append(" · $size")
            },
            style = style,
            softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow) stacked = true },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** O check de "baixado" ao lado do nome do card instalado; o leitor de tela diz "Baixado". */
@Composable
private fun DownloadedMark(tag: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val description = tr("Baixado")
    Box(
        modifier
            .size(15.dp)
            .clip(CircleShape)
            .background(palette.teal.copy(alpha = 0.16f))
            .border(1.dp, palette.teal.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        KfIcon(KfIcons.Check, tint = palette.teal, modifier = Modifier.size(10.dp), strokeWidth = 3.2f)
    }
}

/**
 * As etiquetas do modelo em linhas que quebram: o que ele é, no que difere dos outros e o que pede do aparelho. O [trailing] (a ação do
 * card) fecha a última linha, encostado na direita; se não couber ali, desce para uma linha própria, também à direita.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelTagRow(
    cardTags: List<CardTag>,
    tags: ModelListTags,
    modelId: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cardTags.forEach { tag ->
            Text(
                tag.text,
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(50))
                    .background(palette.panel)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
                    .then(tag.description?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier)
                    .testTag(tags.tag(modelId, tag.key)),
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { trailing() }
    }
}

@Composable
private fun CardAction(
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = LocalKeyFlowPalette.current.teal,
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

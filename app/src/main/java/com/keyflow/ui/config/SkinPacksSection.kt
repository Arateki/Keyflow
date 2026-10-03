package com.keyflow.ui.config

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ime.KeyboardSkin
import com.keyflow.skins.ImportReport
import com.keyflow.skins.InstallResult
import com.keyflow.skins.PackPreview
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPack
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Qual superfície a seção veste: o teclado (aba Teclado) ou a orbe (aba Sobrepor). Cada aba lista só os pacotes que têm a peça dela. */
enum class SkinPackSurface { KEYBOARD, ORB }

const val SKIN_PACK_IMPORT_TAG = "skin-pack-import"
const val SKIN_PACK_REPORT_TAG = "skin-pack-report"
const val SKIN_PACK_REPORT_CLOSE_TAG = "skin-pack-report-close"
const val SKIN_PACK_REPORT_DONE_TAG = "skin-pack-report-done"
const val SKIN_PACK_REMOVE_YES_TAG = "skin-pack-remove-yes"
const val SKIN_PACK_REMOVE_NO_TAG = "skin-pack-remove-no"

/** O cartão do pacote [packId] (o id da pasta, sem o `pack:`). */
fun skinPackCardTag(packId: String) = "skin-pack-card-$packId"

fun skinPackRemoveTag(packId: String) = "skin-pack-remove-$packId"

fun skinPackConfirmTag(packId: String) = "skin-pack-confirm-$packId"

/**
 * "Suas skins" (SKP-UI-01..04): o botão **Importar skin**, o diálogo com o relatório do import e os cartões dos pacotes instalados que têm
 * a peça de [surface] — com a miniatura renderizada do SVG, selecionar (grava `pack:<id>` em `keyboardSkin`/`orbSkin`) e remover (com
 * confirmação). Fica logo abaixo da seção Skins das abas Teclado e Sobrepor; a skin do usuário nunca é bloqueada.
 *
 * O seletor é o do sistema (`ACTION_OPEN_DOCUMENT`); a leitura e a instalação rodam fora da thread principal (SKP-IMP-06). Cancelar o
 * seletor não faz nada. Importar não seleciona a skin: escolher é um toque no cartão — exceto que um pacote **já selecionado** que foi
 * reimportado aparece na hora (o teclado e a orbe reabrem pelo [SkinPackStore.changes]), e, se a versão nova perdeu a peça que a
 * superfície usava, a escolha volta ao padrão ([settingsAfterInstall]).
 */
@Composable
fun SkinPacksSection(
    surface: SkinPackSurface,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    store: SkinPackStore = rememberSkinPackStore(),
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // O seletor devolve o resultado depois (outra Activity): lê sempre o ajuste e o callback mais novos.
    val currentSettings by rememberUpdatedState(settings)
    val currentChange by rememberUpdatedState(onSettingsChange)
    val cards by rememberSkinPackCards(store)
    var importing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SkinImportOutcome?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Cancelar o seletor devolve `null`: nada foi lido, nada foi instalado.
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = importSkinPack(context, store, uri)
            importing = false
            val adjusted = settingsAfterInstall(currentSettings, result)
            if (adjusted != currentSettings) currentChange(adjusted)
            outcome = result
        }
    }

    SkinPacksContent(
        surface = surface,
        cards = cards.orEmpty(),
        selectedSkinId = if (surface == SkinPackSurface.KEYBOARD) settings.keyboardSkin else settings.orbSkin,
        importing = importing,
        confirmingId = confirming,
        onImport = { if (!importing) picker.launch(SKIN_PACK_MIME_TYPES) },
        onSelect = { card ->
            val current = currentSettings
            currentChange(
                if (surface == SkinPackSurface.KEYBOARD) current.copy(keyboardSkin = card.skinId) else current.copy(orbSkin = card.skinId),
            )
        },
        onRemoveRequest = { confirming = it.id },
        onRemoveCancel = { confirming = null },
        onRemoveConfirm = { card ->
            confirming = null
            // Primeiro o ajuste, depois o pacote: ninguém fica apontando para uma skin que já não existe (R1).
            val adjusted = settingsAfterRemoval(currentSettings, card.skinId)
            if (adjusted != currentSettings) currentChange(adjusted)
            scope.launch { withContext(Dispatchers.IO) { store.remove(card.id) } }
        },
        modifier = modifier,
    )
    outcome?.let { SkinImportReportDialog(it, onDismiss = { outcome = null }) }
}

/** Os tipos que o seletor oferece: o `.zip` aparece com tipos diferentes conforme o app de arquivos (e há quem o entregue como binário). */
internal val SKIN_PACK_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
internal fun rememberSkinPackStore(): SkinPackStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SkinPackStore.of(context) }
}

// ── o estado dos ajustes ─────────────────────────────────────────────────────

/**
 * A skin nativa que [selectedSkin] marca nos cartões do teclado, ou `null` se ele é um pacote: `KeyboardSkin.of` cai no Sistema para qualquer id que
 * não conhece, e o cartão do Sistema ficaria com anel junto com o do pacote.
 */
internal fun nativeKeyboardSkin(selectedSkin: String?): KeyboardSkin? = if (SkinIds.isPack(selectedSkin)) null else KeyboardSkin.of(selectedSkin)

/**
 * Os ajustes depois de remover o pacote [skinId] (`pack:<id>`): a superfície que o usava volta ao padrão — o teclado ao Sistema, a orbe ao
 * Ateliê. Os dois campos são conferidos, qualquer que seja a aba: um pacote com orbe e teclado pode estar nos dois. Sem isto o ajuste
 * continuaria `pack:<id>` e a skin só cairia no padrão na hora de desenhar, com o cartão do padrão sem anel.
 */
internal fun settingsAfterRemoval(settings: Settings, skinId: String): Settings = settings.copy(
    keyboardSkin = if (settings.keyboardSkin == skinId) KeyboardSkin.DEFAULT.id else settings.keyboardSkin,
    orbSkin = if (settings.orbSkin == skinId) OrbStyle.ATELIE.id else settings.orbSkin,
)

/**
 * Os ajustes depois de instalar [outcome]: reimportar um pacote selecionado cuja versão nova **não tem mais** a peça que a superfície usava
 * (o teclado tirado de um pacote que vestia o teclado) devolve essa superfície ao padrão — senão o cartão sumiria da aba e nada ficaria marcado.
 */
internal fun settingsAfterInstall(settings: Settings, outcome: SkinImportOutcome): Settings {
    val skinId = outcome.skinId ?: return settings
    return settings.copy(
        keyboardSkin = if (settings.keyboardSkin == skinId && !outcome.hasKeyboard) KeyboardSkin.DEFAULT.id else settings.keyboardSkin,
        orbSkin = if (settings.orbSkin == skinId && !outcome.hasOrb) OrbStyle.ATELIE.id else settings.orbSkin,
    )
}

// ── importar ─────────────────────────────────────────────────────────────────

/**
 * O que um import produziu, para o diálogo: o nome do arquivo escolhido, o relatório e, se instalou, o pacote. [result] sempre existe — até a
 * falha ao abrir o arquivo é um relatório com um erro.
 */
internal class SkinImportOutcome(
    val fileName: String?,
    val result: InstallResult,
    val packName: String? = null,
    val hasOrb: Boolean = false,
    val hasKeyboard: Boolean = false,
) {
    val report: ImportReport get() = result.report
    val installed: Boolean get() = result.installed
    val skinId: String? get() = result.skinId
}

/**
 * Lê o `.zip` de [uri] e o instala em [store]. **Bloqueia** (IO e desenho de teste dos SVGs): roda em `Dispatchers.IO`. Não lança: um arquivo
 * que não abre, uma permissão perdida ou um defeito inesperado viram um relatório com um erro, nunca um crash da config.
 */
internal suspend fun importSkinPack(
    context: Context,
    store: SkinPackStore,
    uri: Uri,
    /** Como se abre o arquivo; só os testes trocam (para provar em que thread o import roda). */
    open: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
): SkinImportOutcome = withContext(Dispatchers.IO) {
    val fileName = displayName(context, uri)
    val result = try {
        val stream = open(uri)
        if (stream == null) failedInstall(tr("Não foi possível abrir o arquivo escolhido.")) else stream.use { store.import(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        failedInstall(tr("Sem permissão para ler o arquivo escolhido."))
    } catch (e: IOException) {
        failedInstall(tr("Não foi possível ler o arquivo escolhido: %s", e.message ?: ""))
    } catch (e: Exception) {
        failedInstall(tr("Não foi possível importar a skin: %s", e.message ?: e.javaClass.simpleName))
    }
    val pack = result.packId?.let { runCatching { store.load(it) }.getOrNull() }
    SkinImportOutcome(fileName, result, pack?.name, pack?.hasOrb ?: false, pack?.hasKeyboard ?: false)
}

private fun failedInstall(message: String) = InstallResult(ImportReport(errors = listOf(message)), packId = null)

/** O nome que o app de arquivos mostra para [uri] (`minha-skin.zip`), ou o fim do caminho; `null` se não há. */
private fun displayName(context: Context, uri: Uri): String? {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

// ── os cartões ───────────────────────────────────────────────────────────────

/** Um pacote instalado, com as miniaturas já renderizadas. */
internal class SkinPackCard(
    /** O id da pasta (sem o `pack:`). */
    val id: String,
    /** O id guardado nos ajustes (`pack:<id>`). */
    val skinId: String,
    val name: String,
    val author: String?,
    val hasOrb: Boolean,
    val hasKeyboard: Boolean,
    val orbPreview: ImageBitmap?,
    val keyboardPreview: ImageBitmap?,
)

/** O tamanho da miniatura do cartão (a mesma para o teclado e a orbe). */
private val PREVIEW_WIDTH = 64.dp
private val PREVIEW_HEIGHT = 44.dp

/**
 * Lê os pacotes de [store] e renderiza as miniaturas. **Bloqueia** (IO e SVG): chame fora da thread principal. [density] só serve para o
 * tamanho em px das miniaturas.
 */
internal fun loadSkinPackCards(store: SkinPackStore, density: Float): List<SkinPackCard> {
    val width = (PREVIEW_WIDTH.value * density).roundToInt()
    val height = (PREVIEW_HEIGHT.value * density).roundToInt()
    return store.list().map { pack -> cardOf(pack, width, height) }
}

private fun cardOf(pack: SkinPack, width: Int, height: Int) = SkinPackCard(
    id = pack.id,
    skinId = pack.skinId,
    name = pack.name,
    author = pack.manifest.author,
    hasOrb = pack.hasOrb,
    hasKeyboard = pack.hasKeyboard,
    orbPreview = PackPreview.orb(pack, height)?.asImageBitmap(),
    keyboardPreview = PackPreview.keyboard(pack, width, height)?.asImageBitmap(),
)

/**
 * Os cartões de [store], atualizados quando um pacote é instalado, reimportado ou removido ([SkinPackStore.changes]). Lê e renderiza em
 * segundo plano; `null` até a primeira leitura, e a lista antiga continua na tela enquanto a nova é montada.
 */
@Composable
private fun rememberSkinPackCards(store: SkinPackStore): State<List<SkinPackCard>?> {
    val density = LocalDensity.current.density
    val changes by SkinPackStore.changes.collectAsState()
    return produceState<List<SkinPackCard>?>(initialValue = null, store, changes, density) {
        value = withContext(Dispatchers.IO) { loadSkinPackCards(store, density) }
    }
}

// ── a seção, sem estado ──────────────────────────────────────────────────────

/**
 * A seção "Suas skins" só com o que desenha: a galeria de verificação e os testes a usam sem seletor nem disco ([SkinPacksSection] é quem liga o
 * seletor, o store e os ajustes). [cards] são os pacotes (os que não têm a peça de [surface] ficam de fora), [selectedSkinId] a skin da
 * superfície nos ajustes e [confirmingId] o cartão com a pergunta de remoção aberta.
 */
@Composable
internal fun SkinPacksContent(
    surface: SkinPackSurface,
    cards: List<SkinPackCard>,
    selectedSkinId: String,
    importing: Boolean,
    confirmingId: String?,
    onImport: () -> Unit,
    onSelect: (SkinPackCard) -> Unit,
    onRemoveRequest: (SkinPackCard) -> Unit,
    onRemoveCancel: () -> Unit,
    onRemoveConfirm: (SkinPackCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val shown = cards.filter { if (surface == SkinPackSurface.KEYBOARD) it.hasKeyboard else it.hasOrb }
    Column(modifier.fillMaxWidth()) {
        HairlineDivider()
        Column(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 16.dp)) {
            Text(tr("Suas skins"), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            SettingSub(
                if (surface == SkinPackSurface.KEYBOARD) {
                    tr("Importe um pacote .zip de SVGs com o seu teclado: faces das teclas e fundo.")
                } else {
                    tr("Importe um pacote .zip de SVGs com a sua orbe: um desenho por frame e por estado.")
                },
                Modifier.padding(top = 6.dp),
            )
            SecondaryButton(
                text = if (importing) tr("Importando…") else tr("Importar skin"),
                tag = SKIN_PACK_IMPORT_TAG,
                onClick = onImport,
                modifier = Modifier.padding(top = 12.dp),
                icon = KfIcons.Plus,
            )
            for (card in shown) {
                SkinPackCardRow(
                    card = card,
                    surface = surface,
                    selected = card.skinId == selectedSkinId,
                    confirming = card.id == confirmingId,
                    onSelect = { onSelect(card) },
                    onRemoveRequest = { onRemoveRequest(card) },
                    onRemoveCancel = onRemoveCancel,
                    onRemoveConfirm = { onRemoveConfirm(card) },
                )
            }
        }
    }
}

@Composable
private fun SkinPackCardRow(
    card: SkinPackCard,
    surface: SkinPackSurface,
    selected: Boolean,
    confirming: Boolean,
    onSelect: () -> Unit,
    onRemoveRequest: () -> Unit,
    onRemoveCancel: () -> Unit,
    onRemoveConfirm: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) palette.fg else palette.line, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .testTag(skinPackCardTag(card.id))
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkinPackPreview(card, surface)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    card.name,
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(card.author, partsLabel(card)).joinToString(" · "),
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val removeLabel = tr("Remover %s", card.name)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemoveRequest)
                    .semantics { contentDescription = removeLabel }
                    .testTag(skinPackRemoveTag(card.id)),
                contentAlignment = Alignment.Center,
            ) {
                KfIcon(KfIcons.Trash, palette.muted, Modifier.size(16.dp), strokeWidth = 1.7f)
            }
        }
        if (confirming) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.panel)
                    .padding(14.dp)
                    .testTag(skinPackConfirmTag(card.id)),
            ) {
                Text(
                    tr("Remover %s? Se ela estiver em uso, volta para a skin padrão.", card.name),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 12.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    PillAction(tr("Cancelar"), SKIN_PACK_REMOVE_NO_TAG, palette.muted, onRemoveCancel)
                    PillAction(tr("Remover"), SKIN_PACK_REMOVE_YES_TAG, palette.terracotta, onRemoveConfirm)
                }
            }
        }
    }
}

/** "orbe e teclado", "só orbe" ou "só teclado": o que o pacote traz. */
private fun partsLabel(card: SkinPackCard): String = when {
    card.hasOrb && card.hasKeyboard -> tr("orbe e teclado")
    card.hasOrb -> tr("só orbe")
    else -> tr("só teclado")
}

/** A miniatura renderizada do SVG: a orbe sobre o fundo da tela (como as amostras das orbes nativas) ou a bandeja com uma tecla. */
@Composable
private fun SkinPackPreview(card: SkinPackCard, surface: SkinPackSurface) {
    val palette = LocalKeyFlowPalette.current
    val shape = RoundedCornerShape(8.dp)
    val box = Modifier.size(width = PREVIEW_WIDTH, height = PREVIEW_HEIGHT).clip(shape).border(1.dp, palette.line2, shape)
    if (surface == SkinPackSurface.ORB) {
        Box(box.background(palette.bg), contentAlignment = Alignment.Center) {
            val preview = card.orbPreview
            if (preview != null) Image(preview, contentDescription = null, modifier = Modifier.fillMaxHeight().aspectRatio(1f), contentScale = ContentScale.Fit)
        }
    } else {
        Box(box.background(palette.panel)) {
            val preview = card.keyboardPreview
            if (preview != null) Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

/** Botão de pílula da pergunta de remoção (o mesmo desenho dos "Cancelar"/"Apagar" das listas de modelos). */
@Composable
private fun PillAction(label: String, tag: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

// ── o relatório ──────────────────────────────────────────────────────────────

/**
 * O relatório do import (SKP-UI-02): o que entrou, os avisos e os erros (cada um com o nome do arquivo do `.zip`). Com algum erro **nada** foi
 * instalado — o diálogo diz isso. Fecha no `✕`, em "Fechar" ou fora do cartão.
 */
@Composable
internal fun SkinImportReportDialog(outcome: SkinImportOutcome, onDismiss: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val report = outcome.report
    val title = when {
        !outcome.installed -> tr("Não foi possível instalar")
        outcome.result.replaced -> tr("Skin atualizada")
        else -> tr("Skin instalada")
    }
    ConfigModal(title = title, onDismiss = onDismiss, closeTag = SKIN_PACK_REPORT_CLOSE_TAG, cardTag = SKIN_PACK_REPORT_TAG) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val subject = listOfNotNull(outcome.packName, outcome.fileName?.takeIf { it != outcome.packName }).joinToString(" · ")
            if (subject.isNotEmpty()) {
                Text(subject, color = palette.fg2, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 11.sp)
            }
            if (report.installed.isNotEmpty()) {
                ReportList(tr("Instalado"), report.installed, palette.teal, palette.fg) { KfIcon(KfIcons.Check, palette.teal, Modifier.size(12.dp), strokeWidth = 2.2f) }
            }
            if (report.warnings.isNotEmpty()) {
                ReportList(tr("Avisos"), report.warnings, palette.faint, palette.fg2) { Dot(palette.muted) }
            }
            if (report.errors.isNotEmpty()) {
                ReportList(tr("Erros"), report.errors, palette.terracotta, palette.terracotta) { Dot(palette.terracotta) }
                SettingSub(tr("Nada foi instalado."))
            }
            SecondaryButton(text = tr("Fechar"), tag = SKIN_PACK_REPORT_DONE_TAG, onClick = onDismiss, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ReportList(title: String, lines: List<String>, titleColor: Color, textColor: Color, marker: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Eyebrow(title, color = titleColor)
        for (line in lines) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.padding(top = 3.dp).size(12.dp), contentAlignment = Alignment.Center) { marker() }
                Text(line, color = textColor, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(5.dp).clip(CircleShape).background(color))
}

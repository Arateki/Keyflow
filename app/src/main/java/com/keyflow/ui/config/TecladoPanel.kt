package com.keyflow.ui.config

import com.keyflow.i18n.tr
import com.keyflow.ime.KeyboardSkin
import com.keyflow.ads.AdsController
import com.keyflow.ads.BannerSlot
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.Settings
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.skins.SkinIds
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono
import kotlin.math.roundToInt

const val TECLADO_AUTO_PASTE_IN_FIELD_TAG = "teclado-auto-paste-in-field"
const val TECLADO_AUTO_PASTE_ON_FOCUS_TAG = "teclado-auto-paste-on-focus"
const val TECLADO_LANGUAGE_TAG = "teclado-language"

/** Idiomas do teclado, na ordem do globo (`KeyFlowKeyboardService.LANGUAGE_CYCLE`). */
private val KEYBOARD_LANGUAGES = listOf("pt", "en", "es")

fun keyboardLanguageTag(language: String) = "teclado-language-$language"

/** Nome do idioma no próprio idioma — o mesmo que aparece na barra de espaço. */
private fun keyboardLanguageLabel(language: String): String = when (language) {
    "pt" -> "Português"
    "en" -> "English"
    "es" -> "Español"
    else -> language
}
const val TECLADO_UNLOCK_TAG = "teclado-unlock-skins"
const val TECLADO_SIZE_PREVIEW_NOTE_TAG = "teclado-size-preview-note"
const val TECLADO_SIZE_SLIDER_TAG = "teclado-size-slider"
const val TECLADO_SIZE_PREVIEW_TAG = "teclado-size-preview"
const val TECLADO_NUMBER_ROW_TAG = "teclado-number-row"
const val TECLADO_SHOW_WITH_HARD_KEYBOARD_TAG = "teclado-show-with-hard-keyboard"
const val TECLADO_AUTO_CORRECT_TAG = "teclado-auto-correct"

/**
 * Referência somente-leitura à `KeyFlowKeyboardView` da prévia de tamanho, para instrumentado medir a
 * altura real (mesmo padrão de `KeyFlowKeyboardService.activeViewForTest`).
 */
@Volatile
var keyboardSizePreviewForTest: KeyFlowKeyboardView? = null
    private set

/**
 * Aba Teclado (KBD config / SKIN-01): colar automaticamente, idioma do teclado (o mesmo do globo) e a
 * seção de Skins bloqueada. O teclado não tem modo de gravação (toque + seletor por hold no mic).
 */
@Composable
fun TecladoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onUnlockSkins: () -> Unit,
    skinsUnlocked: Boolean = false,
    adsController: AdsController? = null,
    adsOff: Boolean = false,
    entitlementsLoaded: Boolean = true,
    onOverlayRequired: () -> Unit = {},
    barsConfig: BarsConfig = BarsConfig.DEFAULT,
    onBarsConfigChange: (BarsConfig) -> Unit = {},
    shortcutGrids: List<ShortcutGrid> = emptyList(),
    onShortcutGridsChange: (List<ShortcutGrid>) -> Unit = {},
    modifier: Modifier = Modifier,
    // Cartão do Modo Teclado, sempre no topo e sempre tocável (a aba Modo saiu — Yan, 2026-09-28).
    modeCard: (@Composable () -> Unit)? = null,
    locked: Boolean = false,
    onLockedTap: () -> Unit = {},
    // Muda ao chegar de um aviso: a aba sobe até o cartão do modo (que pisca por conta própria).
    modeCardFocusKey: Int = 0,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    LaunchedEffect(modeCardFocusKey) { if (modeCardFocusKey > 0) scroll.animateScrollTo(0) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        modeCard?.invoke()
        // Sem o modo pronto (ligado, permissões, KeyFlow como teclado ativo), o resto da aba só se lê.
        LockedSection(locked = locked, onLockedTap = onLockedTap) {
            SettingsGroup(
                tr("Colar transcrição automaticamente"),
                description = tr("Quando você precisa que sua fala preencha os campos sem sua ação manual."),
            ) {
                ToggleRow(
                    label = tr("Ao gravar com um campo de texto aberto"),
                    sub = tr("Cola direto quando a transcrição volta."),
                    checked = settings.autoPasteInField,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPasteInField = it)) },
                    tag = TECLADO_AUTO_PASTE_IN_FIELD_TAG,
                )
                GroupDivider()
                ToggleRow(
                    // "Gravar fora" (sem campo focado) só acontece pelo Modo Sobrepor; sem ele a opção não faz
                    // sentido e fica inerte (mesmo estilo de abas/menus inativos), explicando o porquê no toque.
                    label = tr("Ao tocar num campo depois de gravar fora"),
                    sub = tr("Guarda o texto e cola quando você foca um campo."),
                    checked = settings.autoPasteOnFocus,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPasteOnFocus = it)) },
                    tag = TECLADO_AUTO_PASTE_ON_FOCUS_TAG,
                    enabled = settings.overlayModeEnabled,
                    onDisabledClick = onOverlayRequired,
                )
            }

            SettingsGroup(tr("Digitação")) {
                // Idioma do teclado: a mesma escolha que o globo alterna (layout, acentos e corretor). A linha era
                // um "pt-BR" fixo de antes de o teclado ter inglês e espanhol (achado do Yan, 2026-09-28).
                GroupItem(tr("Idioma do teclado"), tr("O mesmo que o globo do teclado alterna: letras, acentos e corretor.")) {
                    Segmented(
                        options = KEYBOARD_LANGUAGES,
                        selected = settings.keyboardLanguage,
                        label = ::keyboardLanguageLabel,
                        tag = ::keyboardLanguageTag,
                        onSelect = { onSettingsChange(settings.copy(keyboardLanguage = it)) },
                        modifier = Modifier.padding(top = 14.dp).testTag(TECLADO_LANGUAGE_TAG),
                    )
                }
                GroupDivider()
                // Correção automática (Q9, pedido do Yan, 2026-09-28): desligada, a faixa segue sugerindo.
                ToggleRow(
                    label = tr("Correção automática"),
                    sub = tr("Troca a palavra no espaço quando o corretor tem certeza (\"nao\" → \"não\"). Apagar logo depois desfaz."),
                    checked = settings.keyboardAutoCorrect,
                    onCheckedChange = { onSettingsChange(settings.copy(keyboardAutoCorrect = it)) },
                    tag = TECLADO_AUTO_CORRECT_TAG,
                )
                GroupDivider()
                // Fileira de números (NUMROW-01, pedido do Yan, 2026-07-03): dedicada acima do "qwertyuiop",
                // estilo Gboard — reflete na prévia abaixo e desativa o long-press de número na 1ª fileira
                // (redundante com a fileira nova).
                ToggleRow(
                    label = tr("Fileira de números"),
                    sub = tr("Linha dedicada de dígitos acima do teclado, como no Gboard."),
                    checked = settings.keyboardNumberRowEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(keyboardNumberRowEnabled = it)) },
                    tag = TECLADO_NUMBER_ROW_TAG,
                )
                GroupDivider()
                // Sem isto o Android esconde o teclado virtual quando há teclado físico conectado — e some
                // junto o mic, que é o produto. Ligado por padrão; desmarcar devolve o comportamento do sistema.
                ToggleRow(
                    label = tr("Mostrar com teclado físico"),
                    sub = tr("Mantém o teclado do KeyFlow na tela mesmo com um teclado Bluetooth ou USB conectado."),
                    checked = settings.keyboardShowWithHardKeyboard,
                    onCheckedChange = { onSettingsChange(settings.copy(keyboardShowWithHardKeyboard = it)) },
                    tag = TECLADO_SHOW_WITH_HARD_KEYBOARD_TAG,
                )
            }

            // Layout do teclado (CFG-01/02/03/06): editor WYSIWYG das barras de atalhos.
            SettingsGroup(
                tr("Layout do teclado"),
                description = tr("Personalize os botões da barra do teclado — mic, atalhos, gatilhos de grade."),
            ) {
                BarsEditor(
                    barsConfig = barsConfig,
                    onBarsConfigChange = onBarsConfigChange,
                    grids = shortcutGrids,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }

            // Teclados de atalhos (CFG-04): grades que os gatilhos da barra acima podem abrir.
            SettingsGroup(
                tr("Teclados de atalhos"),
                description = tr("Grades de atalhos que os gatilhos da barra podem abrir."),
            ) {
                GridsEditor(
                    grids = shortcutGrids,
                    onGridsChange = onShortcutGridsChange,
                    referencedGridIds = barsConfig.referencedGridIds(),
                    modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                )
            }

            SettingsGroup(
                tr("Tamanho do teclado"),
                description = tr("Encolhe ou aumenta o teclado inteiro — teclas, faixa de sugestões e barra retraída."),
                trailing = {
                    Text(
                        "${settings.keyboardScalePercent}%",
                        color = palette.fg2,
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Light,
                        fontSize = 11.sp,
                    )
                },
            ) {
                KfSlider(
                    value = settings.keyboardScalePercent.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(keyboardScalePercent = it.roundToInt())) },
                    valueRange = 70f..130f,
                    tag = TECLADO_SIZE_SLIDER_TAG,
                    modifier = Modifier.padding(top = 10.dp),
                )
                KeyboardSizePreview(
                    scalePercent = settings.keyboardScalePercent,
                    dark = palette.isDark,
                    locale = settings.keyboardLanguage,
                    numberRowEnabled = settings.keyboardNumberRowEnabled,
                    skin = if (SkinIds.isPack(settings.keyboardSkin)) {
                        settings.keyboardSkin
                    } else {
                        KeyboardSkin.effective(settings.keyboardSkin, skinsUnlocked).id
                    },
                )
            }

            SettingsGroup(null) {
                SkinsSection(
                    selectedSkin = settings.keyboardSkin,
                    onSkinSelected = { onSettingsChange(settings.copy(keyboardSkin = it)) },
                    onUnlock = onUnlockSkins,
                    skinsUnlocked = skinsUnlocked,
                    unlockTag = TECLADO_UNLOCK_TAG,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                SkinPacksSection(SkinPackSurface.KEYBOARD, settings, onSettingsChange)
            }
        }
        adsController?.let { BannerSlot(it, adsOff = adsOff, entitlementsLoaded = entitlementsLoaded) }
    }
}

/**
 * Prévia ao vivo do tamanho do teclado (pedido do Yan, 2026-07-02: "eu preciso mudar de teclado pra ver
 * diferença... o ideal seria ver o tamanho enquanto mexe no slide"). `KeyFlowKeyboardView` real, com
 * `locale`/`numberRowEnabled` reais, não digita/grava (callbacks no-op). Reconstruída a cada mudança
 * relevante via `key()`: várias propriedades da View são
 * fixadas no construtor (não mutáveis), então mudar qualquer uma troca a instância — leve o bastante
 * pra ser aceitável a cada passo do arrasto do slider (mesma técnica já usada pelo demo da aba Modo).
 *
 * Largura = a do painel. Até 2026-09-28 era a da TELA menos uma margem (pedido do Yan, 2026-07-03, para a
 * prévia bater com o teclado real); com a navegação lateral, a largura da tela invadia o menu. A ALTURA
 * continua a real — é ela que o slider muda.
 */
@Composable
private fun KeyboardSizePreview(
    scalePercent: Int,
    dark: Boolean,
    locale: String,
    numberRowEnabled: Boolean,
    skin: String = KeyboardSkin.DEFAULT.id,
) {
    val palette = LocalKeyFlowPalette.current
    val pack = rememberPackKeyboard(skin)
    // Largura do PAINEL (não da tela): com a navegação lateral, a largura da tela invadia o menu.
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.bg)
                .testTag(TECLADO_SIZE_PREVIEW_TAG),
        ) {
            key(scalePercent, dark, locale, numberRowEnabled, skin, pack) {
                AndroidView(
                    factory = { ctx ->
                        KeyFlowKeyboardView(
                            context = ctx,
                            onKey = {},
                            onMic = {},
                            onHide = {},
                            onExpand = {},
                            dark = dark,
                            skin = KeyboardSkin.of(skin),
                            pack = pack,
                            scale = scalePercent / 100f,
                            locale = locale,
                            showLanguageKey = true,
                            numberRowEnabled = numberRowEnabled,
                            // A barra de fábrica, com a área das sugestões vazia: mic, ajustes e recolher na
                            // proporção real (sem ela o mic esticava para o cartão todo — Yan, 2026-09-28).
                            barsFitWidth = true,
                        ).apply {
                            setExpanded(true)
                            keyboardSizePreviewForTest = this
                        }
                    },
                    onRelease = {
                        if (keyboardSizePreviewForTest === it) keyboardSizePreviewForTest = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // O cartão é mais estreito que a tela: a prévia acerta a altura, não a largura (Yan, 2026-09-28).
        SettingSub(
            tr("A prévia mostra a altura real do teclado. A largura foi estreitada para caber aqui — no uso, ele ocupa a tela toda."),
            Modifier.fillMaxWidth().padding(top = 10.dp).testTag(TECLADO_SIZE_PREVIEW_NOTE_TAG),
        )
    }
}


package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.data.Settings
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import kotlin.math.roundToInt

const val TECLADO_AUTO_PASTE_IN_FIELD_TAG = "teclado-auto-paste-in-field"
const val TECLADO_AUTO_PASTE_ON_FOCUS_TAG = "teclado-auto-paste-on-focus"
const val TECLADO_LANGUAGE_TAG = "teclado-language"
const val TECLADO_UNLOCK_TAG = "teclado-unlock-skins"
const val TECLADO_SIZE_SLIDER_TAG = "teclado-size-slider"
const val TECLADO_SIZE_PREVIEW_TAG = "teclado-size-preview"
const val TECLADO_NUMBER_ROW_TAG = "teclado-number-row"

/**
 * Margem lateral mantida de propósito na prévia de tamanho (ver [KeyboardSizePreview]) — erra pra menor
 * em vez de vazar até a borda física da tela, por pedido do Yan (2026-07-03). Histórico: 4dp (ainda
 * visivelmente maior que o real) → 10dp (centralização corrigida via `Box`/`Alignment.Center` +
 * `LocalConfiguration`, mas "falta reduzir muito pouco" — 3ª rodada) → 14dp.
 */
internal val PREVIEW_MARGIN_DP = 14.dp

/**
 * Referência somente-leitura à `KeyFlowKeyboardView` da prévia de tamanho, para instrumentado medir a
 * altura real (mesmo padrão de `KeyFlowKeyboardService.activeViewForTest`).
 */
@Volatile
var keyboardSizePreviewForTest: KeyFlowKeyboardView? = null
    private set

/**
 * Aba Teclado (KBD config / SKIN-01): colar automaticamente, idioma do teclado (inerte nesta
 * fase) e a seção de Skins bloqueada. O teclado não tem modo de gravação (toque + seletor por hold no mic).
 */
@Composable
fun TecladoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onLanguageClick: () -> Unit,
    onUnlockSkins: () -> Unit,
    onOverlayRequired: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 20.dp),
    ) {
        Text("Colar transcrição automaticamente", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
        Text(
            "Quando você precisa que sua fala preencha os campos sem sua ação manual.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        ToggleRow(
            label = "Ao gravar com um campo de texto aberto",
            sub = "Cola direto quando a transcrição volta.",
            checked = settings.autoPasteInField,
            onCheckedChange = { onSettingsChange(settings.copy(autoPasteInField = it)) },
            tag = TECLADO_AUTO_PASTE_IN_FIELD_TAG,
        )
        ToggleRow(
            // "Gravar fora" (sem campo focado) só acontece pelo Modo Sobrepor; sem ele a opção não faz
            // sentido e fica inerte (mesmo estilo de abas/menus inativos), explicando o porquê no toque.
            label = "Ao tocar num campo depois de gravar fora",
            sub = "Guarda o texto e cola quando você foca um campo.",
            checked = settings.autoPasteOnFocus,
            onCheckedChange = { onSettingsChange(settings.copy(autoPasteOnFocus = it)) },
            tag = TECLADO_AUTO_PASTE_ON_FOCUS_TAG,
            enabled = settings.overlayModeEnabled,
            onDisabledClick = onOverlayRequired,
        )
        Box(Modifier.fillMaxWidth().padding(top = 13.dp).height(1.dp).background(palette.line2))

        // Idioma do teclado — inerte nesta fase (i18n real = futuro).
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onLanguageClick() }
                .testTag(TECLADO_LANGUAGE_TAG)
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Idioma do teclado", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
            Text("pt-BR  ›", color = palette.muted, fontFamily = SpaceMono, fontSize = 12.sp)
        }

        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(palette.line2))

        // Fileira de números (NUMROW-01, pedido do Yan, 2026-07-03): dedicada acima do "qwertyuiop",
        // estilo Gboard — reflete na prévia abaixo e desativa o long-press de número na 1ª fileira
        // (redundante com a fileira nova).
        ToggleRow(
            label = "Fileira de números",
            sub = "Linha dedicada de dígitos acima do teclado, como no Gboard.",
            checked = settings.keyboardNumberRowEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(keyboardNumberRowEnabled = it)) },
            tag = TECLADO_NUMBER_ROW_TAG,
        )

        Box(Modifier.fillMaxWidth().padding(top = 13.dp).height(1.dp).background(palette.line2))

        Text("Tamanho do teclado", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp, modifier = Modifier.padding(top = 13.dp))
        Text(
            "Encolhe ou aumenta o teclado inteiro — teclas, faixa de sugestões e barra retraída.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Slider(
            value = settings.keyboardScalePercent.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(keyboardScalePercent = it.roundToInt())) },
            valueRange = 70f..130f,
            colors = SliderDefaults.colors(
                thumbColor = palette.teal,
                activeTrackColor = palette.teal,
                inactiveTrackColor = palette.line,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(TECLADO_SIZE_SLIDER_TAG),
        )
        Text(
            "${settings.keyboardScalePercent}%",
            color = palette.muted,
            fontFamily = SpaceMono,
            fontSize = 11.sp,
        )
        KeyboardSizePreview(
            scalePercent = settings.keyboardScalePercent,
            dark = palette.isDark,
            locale = settings.keyboardLanguage,
            numberRowEnabled = settings.keyboardNumberRowEnabled,
        )

        Box(Modifier.fillMaxWidth().padding(top = 13.dp).height(1.dp).background(palette.line2))
        SkinsSection(onUnlock = onUnlockSkins, unlockTag = TECLADO_UNLOCK_TAG)
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
 * Largura via `LocalConfiguration.screenWidthDp` (não a largura disponível dentro do painel, que já vem
 * reduzida pelo padding do `ConfigScreen`) + `Box(contentAlignment = Alignment.Center)`: centraliza a
 * prévia mesmo sendo mais larga que o espaço herdado do painel, sem precisar calcular offset manual (a
 * 1ª versão usava um `layout{}` próprio pra "vazar" o padding do pai e tinha um bug de assimetria).
 */
@Composable
private fun KeyboardSizePreview(scalePercent: Int, dark: Boolean, locale: String, numberRowEnabled: Boolean) {
    val palette = LocalKeyFlowPalette.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val previewWidth = (screenWidthDp - PREVIEW_MARGIN_DP * 2).coerceAtLeast(0.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(previewWidth)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.bg)
                .testTag(TECLADO_SIZE_PREVIEW_TAG),
        ) {
            key(scalePercent, dark, locale, numberRowEnabled) {
                AndroidView(
                    factory = { ctx ->
                        KeyFlowKeyboardView(
                            context = ctx,
                            onKey = {},
                            onMic = {},
                            onHide = {},
                            onExpand = {},
                            dark = dark,
                            scale = scalePercent / 100f,
                            locale = locale,
                            showLanguageKey = true,
                            numberRowEnabled = numberRowEnabled,
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
    }
}

/**
 * Linha de um cenário de cola automática: rótulo + descrição curta à esquerda, [PillToggle] à direita.
 * Quando [enabled] é falso a linha fica esmaecida (somente-leitura) e o toque chama [onDisabledClick]
 * em vez de alternar — mesmo padrão das abas/menus inativos.
 */
@Composable
private fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.clickable { onDisabledClick() })
            .padding(top = 12.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
            Text(sub, color = palette.faint, fontFamily = SpaceGrotesk, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
        PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = tag, enabled = enabled)
    }
}

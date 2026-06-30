package com.keyflow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Tokens de cor da direção *Ateliê* (FND-03). Valores de `context.md` (mock `Config Abas`).
 * Cores em ARGB `0xAARRGGBB`. Os acentos (teal/terracota/record) são **fixos** entre claro e escuro.
 */
data class KeyFlowPalette(
    // Superfícies e texto (variam por modo)
    val bg: Color,
    val card: Color,
    val fg: Color,
    val fg2: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val line2: Color,
    val panel: Color,
    val onFill: Color,
    // Acentos fixos
    val teal: Color,
    val tealLight: Color,
    val terracotta: Color,
    val record: Color,
    val recordSoft: Color,
    // Teclado
    val kbTray: Color,
    val kbTrayBorder: Color,
    val kbKey: Color,
    val kbKeyBorder: Color,
    val kbKeyShadow: Color,
    val kbModifier: Color,
    // Marcador do modo (claro/escuro) — útil p/ status-bar falsa e seleção de ícones
    val isDark: Boolean,
)

/**
 * Cor de acento da **orbe** — fonte única (hoje teal fixo; quando a skin da orbe existir, troca aqui e
 * tudo que referencia [OrbAccent]/[OrbAccentArgb] acompanha: setas do modo-arrasto, banners de overlay).
 */
val OrbAccent = Color(0xFF4E9683)

/** [OrbAccent] em ARGB `Int` para as views fora do Compose (banners do `OverlayService`). */
val OrbAccentArgb: Int = 0xFF4E9683.toInt()

// Acentos fixos compartilhados entre os dois temas (context.md).
private val Teal = OrbAccent
private val TealLight = Color(0xFF8FC3B3)
private val Terracotta = Color(0xFFD9694B)
private val Record = Color(0xFFC6873F)
private val RecordSoft = Color(0xFFA86A1E)

val LightPalette = KeyFlowPalette(
    bg = Color(0xFFF1E7C9),
    card = Color(0xFFF5ECCF),
    fg = Color(0xFF2B2720),
    fg2 = Color(0xFF3A352B),
    muted = Color(0xFF6A6049),
    faint = Color(0xFF9C9072),
    line = Color(0xFFD6CBA6),
    line2 = Color(0xFFE4DBBD),
    panel = Color(0xFFEBE1C6),
    onFill = Color(0xFFF4ECCF),
    teal = Teal,
    tealLight = TealLight,
    terracotta = Terracotta,
    record = Record,
    recordSoft = RecordSoft,
    // Teclado (handoff KeyFlowColors — paleta papel)
    kbTray = Color(0xFFDCD0AC),
    kbTrayBorder = Color(0xFFC7B98F),
    kbKey = Color(0xFFFBF6E6),
    kbKeyBorder = Color(0xFFD7C9A0),
    kbKeyShadow = Color(0xFFBDAF85),
    kbModifier = Color(0xFFCFC096),
    isDark = false,
)

val DarkPalette = KeyFlowPalette(
    bg = Color(0xFF2B2720),
    card = Color(0xFF332E27),
    fg = Color(0xFFF1E7C9),
    fg2 = Color(0xFFEDE3C4),
    muted = Color(0xFFC3B996),
    faint = Color(0xFF9C9072),
    line = Color(0xFF4A4234),
    line2 = Color(0xFF3A332A),
    panel = Color(0xFF3A332A),
    onFill = Color(0xFF2B2720),
    teal = Teal,
    tealLight = TealLight,
    terracotta = Terracotta,
    record = Record,
    recordSoft = RecordSoft,
    // Teclado escuro: derivado das superfícies escuras (T23 refina se preciso)
    kbTray = Color(0xFF332E27),
    kbTrayBorder = Color(0xFF4A4234),
    kbKey = Color(0xFF3A332A),
    kbKeyBorder = Color(0xFF4A4234),
    kbKeyShadow = Color(0xFF1C1813),
    kbModifier = Color(0xFF2B2720),
    isDark = true,
)

/** Seleção pura do tema por flag — ponto testável em unit (FND-03/FND-04). */
fun paletteFor(dark: Boolean): KeyFlowPalette = if (dark) DarkPalette else LightPalette

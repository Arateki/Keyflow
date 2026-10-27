package com.keyflow.ui.config

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keyflow.data.models.ModelVendor
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette

/** Desenho (paths SVG) e cor de marca de cada fabricante de modelo — a cor vem da fonte do path (Simple Icons ou lobe-icons). */
internal class VendorLogo(val icon: KfIconSpec, val brand: Color)

internal fun ModelVendor.logo(): VendorLogo = when (this) {
    ModelVendor.GOOGLE -> VendorLogo(KfIcons.LogoGoogle, Color(0xFF4285F4))
    // A marca da OpenAI é preta: no tema escuro [legibleOn] a clareia até contrastar (vira um cinza claro, próximo do texto).
    ModelVendor.OPENAI -> VendorLogo(KfIcons.LogoOpenAi, Color.Black)
    ModelVendor.QWEN -> VendorLogo(KfIcons.LogoQwen, Color(0xFF082DFF))
    ModelVendor.DEEPSEEK -> VendorLogo(KfIcons.LogoDeepSeek, Color(0xFF5786FE))
    ModelVendor.HUGGING_FACE -> VendorLogo(KfIcons.LogoHuggingFace, Color(0xFFFFD21E))
    ModelVendor.NVIDIA -> VendorLogo(KfIcons.LogoNvidia, Color(0xFF76B900))
}

/** Contraste mínimo (WCAG 1.4.11, componentes gráficos) do logo contra o fundo do card. */
internal const val LOGO_MIN_CONTRAST = 3.0

internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * A cor de marca [brand], ou, se ela não contrasta [minContrast]:1 com [background], a mais próxima dela na direção de
 * [toward] (a cor do texto do tema: escura no claro, clara no escuro) que contrasta. Mantém o matiz da marca sempre que dá.
 */
internal fun legibleOn(brand: Color, background: Color, toward: Color, minContrast: Double = LOGO_MIN_CONTRAST): Color {
    if (contrastRatio(brand, background) >= minContrast) return brand
    var lo = 0f
    var hi = 1f
    if (contrastRatio(toward, background) < minContrast) return toward
    // Busca binária da menor mistura que basta: o contraste cresce de forma monótona rumo à cor do texto.
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (contrastRatio(lerp(brand, toward, mid), background) >= minContrast) hi = mid else lo = mid
    }
    return lerp(brand, toward, hi)
}

/** Logo colorido do fabricante do modelo, com o nome como descrição para o leitor de tela. */
@Composable
internal fun ModelVendorLogo(vendor: ModelVendor, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val logo = remember(vendor) { vendor.logo() }
    val tint = remember(vendor, palette.bg, palette.fg) { legibleOn(logo.brand, palette.bg, palette.fg) }
    Box(modifier.semantics { contentDescription = vendor.displayName }.testTag("model-vendor-${vendor.name.lowercase()}")) {
        KfIcon(logo.icon, tint = tint, modifier = Modifier.size(size))
    }
}

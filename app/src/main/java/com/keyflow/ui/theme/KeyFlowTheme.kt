package com.keyflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * Acesso à paleta Ateliê dentro da árvore Compose. Erra cedo se usado fora de um [KeyFlowTheme].
 */
val LocalKeyFlowPalette = staticCompositionLocalOf<KeyFlowPalette> {
    error("LocalKeyFlowPalette acessado fora de KeyFlowTheme")
}

/**
 * Multiplicador global da tipografia (UAT em device físico): sobra de tela mesmo em celulares
 * menores, então escalamos todo texto `.sp` de uma vez via [Density.fontScale]. Ajuste fino aqui.
 */
const val KEYFLOW_FONT_SCALE = 1.30f

/** Tipografia base Ateliê: Space Grotesk para UI; Space Mono fica nos componentes que pedem mono. */
val KeyFlowTypography = Typography(
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Normal, fontSize = 12.sp),
)

/**
 * Tema raiz do app (FND-03/FND-04). Provê a [KeyFlowPalette] do modo e a tipografia Ateliê.
 * Sem Material color scheme cru: as telas leem cores de [LocalKeyFlowPalette].
 */
@Composable
fun KeyFlowTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = paletteFor(dark)
    val base = LocalDensity.current
    val scaled = Density(density = base.density, fontScale = base.fontScale * KEYFLOW_FONT_SCALE)
    CompositionLocalProvider(
        LocalKeyFlowPalette provides palette,
        LocalDensity provides scaled,
    ) {
        androidx.compose.material3.MaterialTheme(
            typography = KeyFlowTypography,
            content = content,
        )
    }
}

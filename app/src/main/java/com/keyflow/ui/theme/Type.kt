package com.keyflow.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.keyflow.R

/**
 * Tipografia da direção *Ateliê*, empacotada como `.ttf` OFL em `res/font` (FND-02).
 *
 * Diferente do handoff, **não** usamos Downloadable Fonts (`GoogleFont.Provider`): o render precisa ser
 * determinístico/offline para a verificação por screenshot. Pesos disponíveis estáticos: Grotesk
 * 300/400/500/700 (floriankarsten) + 600 instanciado do variable font; Mono 400/700 (google/fonts).
 */
/**
 * UAT em device físico (Yan): além do `fontScale` global, pediu-se um passo a mais de **peso**. Cada
 * `FontWeight` aponta para o arquivo um degrau acima (Light→Regular, Normal→Medium, Medium→SemiBold,
 * SemiBold/Bold→Bold), engrossando toda a UI Compose num só ponto. Reverter = voltar ao mapa 1:1.
 */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Light),
    Font(R.font.space_grotesk_medium, FontWeight.Normal),
    Font(R.font.space_grotesk_semibold, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/**
 * UAT (Yan): textos miúdos custavam a ler. SpaceMono só aparece nos pequenos (labels/legendas/hints
 * 8–13sp), então mapeamos o peso Normal para o arquivo Bold — engrossa apenas os textos menores num
 * ponto só, sem mexer nos títulos (Grotesk). As entradas já Bold seguem Bold.
 */
val SpaceMono = FontFamily(
    Font(R.font.space_mono_bold, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

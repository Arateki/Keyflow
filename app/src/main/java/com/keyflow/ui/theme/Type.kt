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
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_light, FontWeight.Light),
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

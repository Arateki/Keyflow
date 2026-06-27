package com.keyflow.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette

/**
 * Única fonte de verdade das rotas de verificação (VERIFY-05). Cada rota é uma tela/estado isolado,
 * renderizado deterministicamente pela [GalleryActivity]. Telas reais (config/onboarding/…) entram
 * aqui conforme as tasks T10–T24; nesta etapa só as amostras do orbe são renderizáveis.
 */
data class Route(val key: String, val content: @Composable () -> Unit)

/** Tempo fixo do orbe no modo galeria → frames determinísticos (VERIFY-01). */
const val GALLERY_ORB_TIME_MS: Float = 1234f

object RouteCatalog {

    val routes: List<Route> = listOf(
        orbRoute("orb-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("orb-listening", OrbState.Listening, OrbCenter.None),
        orbRoute("orb-paused", OrbState.Paused, OrbCenter.PauseGlyph),
        orbRoute("orb-transcribing", OrbState.Transcribing, OrbCenter.None),
    )

    private val byKey: Map<String, Route> = routes.associateBy { it.key }

    fun route(key: String): Route? = byKey[key]

    val keys: List<String> get() = routes.map { it.key }

    private fun orbRoute(key: String, state: OrbState, center: OrbCenter): Route = Route(key) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(
                state = state,
                modifier = Modifier.size(160.dp),
                center = center,
                timeProvider = { GALLERY_ORB_TIME_MS },
            )
        }
    }
}

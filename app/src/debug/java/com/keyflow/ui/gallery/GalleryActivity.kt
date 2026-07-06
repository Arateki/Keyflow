package com.keyflow.ui.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.keyflow.ui.orb.LocalOrbClock
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.delay
import java.io.File

/**
 * Harness de verificação (VERIFY-01/02). Renderiza **uma** rota do [RouteCatalog] isolada, em tela
 * cheia imersiva (barras de sistema escondidas), com clock fixo e dados mock, de forma determinística.
 *
 *   adb shell am start -n com.keyflow/.ui.gallery.GalleryActivity --es route orb-idle --ez dark false
 *
 * Após o primeiro frame desenhado, grava um marcador `gallery_ready` em `externalFilesDir` contendo a
 * chave renderizada, para o script de captura saber que pode tirar o screenshot.
 *
 * Existe apenas no build de **debug**.
 */
class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val routeKey = intent.getStringExtra(EXTRA_ROUTE).orEmpty()
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)

        // Modo lista: despeja as chaves do RouteCatalog (fonte única, VERIFY-05) e encerra.
        if (routeKey == LIST_ROUTES) {
            runCatching {
                File(getExternalFilesDir(null), ROUTES_FILE).writeText(RouteCatalog.keys.joinToString("\n"))
            }
            finish()
            return
        }

        clearReadyMarker()
        enterImmersiveMode()

        val route = RouteCatalog.route(routeKey)
        setContent {
            // Congela TODO orbe da árvore (header de config, onboarding, …) para frames determinísticos.
            CompositionLocalProvider(LocalOrbClock provides { GALLERY_ORB_TIME_MS }) {
                KeyFlowTheme(dark = dark) {
                    if (route != null) {
                        route.content()
                    } else {
                        Text(
                            text = "rota desconhecida: $routeKey",
                            modifier = Modifier.fillMaxSize().background(Color.Magenta),
                        )
                    }
                    LaunchedEffect(routeKey, dark) {
                        // Espera alguns frames para a 1ª composição/medição assentar e marca pronto.
                        repeat(3) { withFrameNanos { } }
                        // Rotas com estado assíncrono real (ex.: reveal por tempo parado da grade
                        // efêmera, SB-T10/GRD-06) pedem tempo extra além dos 3 frames padrão.
                        route?.minDelayMs?.takeIf { it > 0 }?.let { delay(it) }
                        writeReadyMarker(if (route != null) routeKey else "unknown:$routeKey")
                    }
                }
            }
        }
    }

    private fun enterImmersiveMode() {
        // enableEdgeToEdge() já deixa o conteúdo desenhar atrás das barras (transparentes); aqui só as escondemos.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun readyMarkerFile(): File = File(getExternalFilesDir(null), READY_MARKER)

    private fun clearReadyMarker() {
        runCatching { readyMarkerFile().delete() }
    }

    private fun writeReadyMarker(key: String) {
        runCatching { readyMarkerFile().writeText(key) }
        reportFullyDrawn()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_DARK = "dark"
        const val READY_MARKER = "gallery_ready"
        const val LIST_ROUTES = "__list__"
        const val ROUTES_FILE = "gallery_routes"
    }
}

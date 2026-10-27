package com.keyflow.ui.gallery

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.keyflow.ui.config.LocalModelPreparationClock
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.tour.LocalTourClock
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Harness de verificação (VERIFY-01/02). Renderiza **uma** rota do [RouteCatalog] isolada, em tela
 * cheia imersiva (barras de sistema escondidas), com clock fixo e dados mock, de forma determinística.
 *
 *   adb shell am start -n com.arateki.keyflow/com.keyflow.ui.gallery.GalleryActivity --es route orb-idle --ez dark false
 *
 * Sai em português, qualquer que seja o idioma do aparelho; `--es lang en` (ou `es`) mostra a tradução.
 *
 * Após o primeiro frame desenhado, grava um marcador `gallery_ready` em `externalFilesDir` contendo a
 * chave renderizada, para o script de captura saber que pode tirar o screenshot.
 *
 * Existe apenas no build de **debug**.
 */
class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // O vidro do Aero muda com o desfoque de janela do aparelho; a galeria fixa o valor (sem depender do emulador).
        com.keyflow.ime.windowBlurOverrideForTest = true
        super.onCreate(savedInstanceState)

        val routeKey = intent.getStringExtra(EXTRA_ROUTE).orEmpty()
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        // Prints em português por padrão, qualquer que seja o idioma do aparelho; `--es lang en` para revisar.
        com.keyflow.i18n.AppLocale.pinned = com.keyflow.i18n.AppLanguage.of(intent.getStringExtra("lang"))

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
            // A rota só compõe depois de a janela confirmar o foco (ou de 3 s sem ele). No emulador do CI (GPU por
            // software, 2 núcleos) o 1º quadro das rotas mais pesadas compila shaders por mais de 5 s; com o evento de
            // foco ainda esperando resposta nesse quadro, o Android dava ANR ("Waited 5005ms for FocusEvent") e matava
            // o app — calado, com hide_error_dialogs (2026-10-09: config-overlay-travado, skins-importadas). Com o foco
            // já respondido, o quadro pesado não tem evento nenhum esperando.
            var focusWaitOver by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(FOCUS_WAIT_MS)
                focusWaitOver = true
            }
            if (!windowFocused.value && !focusWaitOver) return@setContent
            // Congela TODO orbe da árvore (header de config, onboarding, …) para frames determinísticos.
            // O tour da aba Modo anima igual à orbe: sem congelar os DOIS, `config-modo` renderiza um
            // frame diferente a cada captura e o golden vira ruído.
            CompositionLocalProvider(
                LocalOrbClock provides { GALLERY_ORB_TIME_MS },
                LocalTourClock provides { GALLERY_ORB_TIME_MS },
                LocalModelPreparationClock provides { 5000L },
            ) {
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
                        // Quadro da thread principal não é quadro na tela: com a rota composta depois do foco, a
                        // entrega à tela vinha atrasada, e a captura pegava a janela ainda vazia (o 1º quadro, que o
                        // Android sincroniza com a janela aparecer, já não é o da rota).
                        awaitSystemBarsHidden()
                        repeat(2) { awaitFrameCommit() }
                        writeReadyMarker(if (route != null) routeKey else "unknown:$routeKey")
                    }
                }
            }
        }
    }

    private val windowFocused = mutableStateOf(false)

    /**
     * Espera o próximo quadro ser entregue à tela (render thread terminou e mandou o buffer). O aviso de quadro entregue é do
     * Android 10; abaixo disso, o passo de animação do quadro seguinte é o mais perto que há.
     */
    private suspend fun awaitFrameCommit() = suspendCancellableCoroutine { cont ->
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            decor.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
        } else {
            decor.postOnAnimation { if (cont.isActive) cont.resume(Unit) }
        }
        decor.invalidate()
    }

    /**
     * Espera as barras do sistema sumirem de fato. O pedido do [enterImmersiveMode] só vale quando a janela ganha o foco; se o
     * foco passa dos 3 s de [FOCUS_WAIT_MS], a rota compõe e ficava pronta com a barra de status ainda na tela — a captura
     * pegava o relógio real e os ícones (gate da main de 2026-10-11: 22 das 36 telas de um recorte, ~0,24% dos pixels cada).
     * Não sumiram em [BARS_WAIT_MS]: segue e avisa no log, para a captura errada se explicar.
     */
    private suspend fun awaitSystemBarsHidden() {
        val deadline = SystemClock.uptimeMillis() + BARS_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val insets = window.decorView.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, window.decorView) }
            if (insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())) return
            withFrameNanos { }
        }
        Log.w(TAG, "barras do sistema ainda visíveis $BARS_WAIT_MS ms depois de pedir para esconder (foco=${windowFocused.value})")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocused.value = true
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
        private const val FOCUS_WAIT_MS = 3_000L
        private const val BARS_WAIT_MS = 10_000L
        private const val TAG = "KeyFlow.Gallery"
    }
}

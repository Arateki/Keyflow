package com.keyflow.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Capturas de fixtures sintéticas; trafegam na saída do teste também quando o pool escolhe a AWS. Não são goldens. */
internal fun capturaFluxos(nome: String, view: View) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val quadro = CountDownLatch(1)
    instrumentation.runOnMainSync {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.viewTreeObserver.registerFrameCommitCallback { quadro.countDown() }
        else view.postOnAnimation { quadro.countDown() }
        view.invalidate()
    }
    check(quadro.await(15, TimeUnit.SECONDS)) { "A tela não entregou o quadro da captura $nome" }
    // `takeScreenshot` devolve null enquanto a janela (principalmente a de um Popup) ainda não estabilizou: espera e tenta de novo; sem
    // sucesso, desenha a própria View (não pega Popups, mas nunca deixa o teste cair por causa da captura).
    var captura: Bitmap? = null
    repeat(10) {
        if (captura == null) {
            captura = instrumentation.uiAutomation.takeScreenshot()
            if (captura == null) { instrumentation.waitForIdleSync(); Thread.sleep(300) }
        }
    }
    val bitmap = captura ?: Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
        instrumentation.runOnMainSync { view.draw(android.graphics.Canvas(it)) }
    }
    val png = ByteArrayOutputStream()
    try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, png) } finally { bitmap.recycle() }
    val partes = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP).chunked(4000)
    partes.forEachIndexed { i, parte ->
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nKEYFLOW_FLUXOS:$nome:$i:${partes.size}:$parte\n") })
    }
}

@androidx.compose.runtime.Composable
internal fun FluxosCena(
    escuro: Boolean = false,
    registrarView: (View) -> Unit = {},
    conteudo: @androidx.compose.runtime.Composable () -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { registrarView(view) }
    com.keyflow.ui.theme.KeyFlowTheme(dark = escuro) {
        androidx.compose.material3.Surface(color = com.keyflow.ui.theme.LocalKeyFlowPalette.current.bg) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                .fillMaxSize().padding(16.dp)) { conteudo() }
        }
    }
}

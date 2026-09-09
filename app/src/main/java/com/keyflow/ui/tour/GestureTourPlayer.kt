package com.keyflow.ui.tour

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Estado do tour: qual roteiro está tocando, desde quando, e se o autoplay pode andar (TOUR-04).
 *
 * Vive fora do Composable de propósito. O consumidor lê [index]/[title]/[description] para posicionar o
 * texto onde quiser (a aba Modo põe ao lado do card; outra tela pode pôr embaixo), e a passagem de tempo
 * fica testável com o relógio na mão, sem device.
 *
 * O tempo entra sempre por [tick] — a classe não tem relógio próprio, que é o que mantém tudo
 * determinístico sob clock congelado (TOUR-05).
 */
class GestureTourState(
    scripts: List<GestureScript>,
    private val autoplayPauseMs: Int = DEFAULT_AUTOPLAY_PAUSE_MS,
) {
    var scripts: List<GestureScript> = scripts
        internal set

    var index by mutableIntStateOf(0)
        private set

    private var scriptStartMs by mutableStateOf(0f)
    private var autoplayHeldUntilMs by mutableStateOf(0f)
    private var lastTickMs = 0f

    val script: GestureScript? get() = scripts.getOrNull(index)
    val title: String get() = script?.title.orEmpty()
    val description: String get() = script?.description.orEmpty()

    /** Frame em [nowMs] **sem efeito colateral** — é o que a composição lê. */
    fun frameFor(nowMs: Float): TourFrame {
        val current = script ?: return EMPTY_FRAME
        return tourFrameAt(current, (nowMs - scriptStartMs).toInt())
    }

    /**
     * Frame em [nowMs], avançando o roteiro se ele acabou e o autoplay estiver livre. Chamado uma vez
     * por quadro, fora da composição; com o relógio parado devolve sempre o mesmo frame.
     */
    fun tick(nowMs: Float): TourFrame {
        lastTickMs = nowMs
        val frame = frameFor(nowMs)
        if (!frame.finished || nowMs < autoplayHeldUntilMs || scripts.isEmpty()) return frame

        index = (index + 1) % scripts.size
        scriptStartMs = nowMs
        return tourFrameAt(scripts[index], 0)
    }

    /** Próximo roteiro. Interrompe a animação corrente e segura o autoplay por um tempo (TOUR-04). */
    fun next() = jumpTo(index + 1)

    /** Roteiro anterior, com a mesma interrupção do [next]. */
    fun prev() = jumpTo(index - 1)

    private fun jumpTo(target: Int) {
        if (scripts.isEmpty()) return
        val size = scripts.size
        index = ((target % size) + size) % size
        // Recomeça do zero: entrar no meio de um gesto que a pessoa pediu para ver é pior do que
        // esperar meio segundo pelo começo dele.
        scriptStartMs = lastTickMs
        autoplayHeldUntilMs = lastTickMs + autoplayPauseMs
    }

    private companion object {
        /** Tempo que o autoplay fica parado depois de uma seta, para dar tempo de ler (T4 do context). */
        const val DEFAULT_AUTOPLAY_PAUSE_MS = 6_000

        val EMPTY_FRAME = TourFrame(
            stepIndex = -1,
            position = Offset.Zero,
            action = FingerAction.MOVE,
            actionProgress = 1f,
            tapCount = 1,
            reaction = SceneReaction(),
            trail = emptyList(),
            finished = true,
        )
    }
}

/** Estado lembrado entre recomposições; troca de lista (Settings mudaram) recomeça do primeiro. */
@Composable
fun rememberGestureTourState(scripts: List<GestureScript>): GestureTourState =
    remember(scripts) { GestureTourState(scripts) }

/**
 * Player isolado (TOUR-01): toca a lista de roteiros recebida, sem saber nada de quem o hospeda.
 *
 * A cena entra por [scene] — nesta camada o player só sabe posicionar o dedo por cima dela. As cenas
 * reais (orbe e teclado) chegam nas GT-T4/T5, e qualquer tela futura pode passar a sua.
 */
@Composable
fun GestureTourPlayer(
    scripts: List<GestureScript>,
    modifier: Modifier = Modifier,
    state: GestureTourState = rememberGestureTourState(scripts),
    scene: @Composable (TourFrame) -> Unit,
) {
    val provided = LocalTourClock.current
    val animated = rememberTourClock(enabled = provided == null)
    val nowMs = provided?.invoke() ?: animated

    // O frame é lido sem efeito colateral; o avanço do autoplay acontece DEPOIS da composição, senão
    // escrever o índice no meio dela realimentaria a recomposição.
    val frame = state.frameFor(nowMs)
    SideEffect { state.tick(nowMs) }

    val label = state.title.takeIf { it.isNotBlank() }?.let { "$it. ${state.description}" }.orEmpty()
    Box(modifier.semantics { if (label.isNotBlank()) contentDescription = label }) {
        scene(frame)
        FingerPuppet(frame, Modifier.matchParentSize())
    }
}

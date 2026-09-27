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
    private val loopPauseMs: Int = DEFAULT_LOOP_PAUSE_MS,
) {
    var scripts: List<GestureScript> = scripts
        internal set

    var index by mutableIntStateOf(0)
        private set

    private var scriptStartMs by mutableStateOf(0f)
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
     * Frame em [nowMs], **repetindo o gesto corrente** quando ele acaba. Chamado uma vez por quadro,
     * fora da composição; com o relógio parado devolve sempre o mesmo frame.
     *
     * O tour NÃO avança sozinho de um gesto para o outro (decisão do Yan, 2026-09-09, usando no A32:
     * a troca automática dava pouco tempo para entender cada um). Quem troca é a seta.
     */
    fun tick(nowMs: Float): TourFrame {
        lastTickMs = nowMs
        val current = script ?: return EMPTY_FRAME
        val elapsed = nowMs - scriptStartMs
        if (elapsed < current.totalDurationMs + loopPauseMs) return frameFor(nowMs)

        // Fim do gesto + respiro: recomeça o MESMO, para a pessoa ver de novo sem perseguir a tela.
        scriptStartMs = nowMs
        return tourFrameAt(current, 0)
    }

    /** Próximo gesto. É o ÚNICO jeito de trocar de roteiro — o tour repete o corrente sozinho. */
    fun next() = jumpTo(index + 1)

    /** Gesto anterior. */
    fun prev() = jumpTo(index - 1)

    private fun jumpTo(target: Int) {
        if (scripts.isEmpty()) return
        val size = scripts.size
        index = ((target % size) + size) % size
        // Recomeça do zero: entrar no meio de um gesto que a pessoa pediu para ver é pior do que
        // esperar meio segundo pelo começo dele.
        scriptStartMs = lastTickMs
    }

    private companion object {
        /** Respiro entre uma repetição e a próxima do mesmo gesto — sem ele, o loop parece um soluço. */
        const val DEFAULT_LOOP_PAUSE_MS = 900

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
    val nowMs = rememberTourTimeMs()

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

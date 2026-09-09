package com.keyflow.ui.tour

import androidx.compose.ui.geometry.Offset
import com.keyflow.core.ButtonState
import com.keyflow.ui.orb.OrbState

/**
 * Modelo declarativo do tour de gestos (SCR-01).
 *
 * **Roteiro é dado; player é motor; cena é a UI real.** Um [GestureScript] descreve um gesto como uma
 * lista de keyframes — onde o dedo está, o que ele faz e como a cena reage — sem saber desenhar nada.
 * Quem desenha é o player (GT-T3), sobre as superfícies reais do app em miniatura, para o tour não
 * dessincronizar do produto como aconteceu com as demos estáticas da aba Modo.
 *
 * Tudo aqui é `data class` de tipos simples: features futuras (modo Visão, transcrição parcial) entram
 * como dados novos, sem tocar no motor.
 */
enum class TourScene { ORB, KEYBOARD }

/** O que o dedo faz num passo. [MULTI_TAP] usa [GestureStep.tapCount]; [DRAG] usa [GestureStep.dragPath]. */
enum class FingerAction { MOVE, TAP, MULTI_TAP, PRESS, RELEASE, DRAG }

/**
 * Elementos que a cena mostra além da orbe/teclado em si — representações, não as views reais: o alvo
 * de cancelamento e o banner de desfazer vivem no `OverlayService`, e o seletor do mic no IME.
 */
enum class TourOverlayHint {
    CANCEL_TARGET,
    UNDO_BANNER,
    MIC_ACTION_SELECTOR,

    /** Apaga o que estiver na tela. Existe porque `null` já significa "não mexe" (ver [SceneReaction]). */
    NONE,
}

/**
 * Como a cena responde a partir deste passo. Campos nulos significam "não mexe" — o passo só declara o
 * que muda, e o player mantém o resto. Para **desfazer** algo que um passo anterior ligou, declare o
 * valor de desligamento explicitamente: é o caso de [TourOverlayHint.NONE], que apaga o banner ou o
 * seletor (`null` ali não apagaria nada).
 */
data class SceneReaction(
    val orbState: OrbState? = null,
    /**
     * Onde a orbe descansa na cena (0..1). Durante um [FingerAction.DRAG] ela acompanha o dedo — é o
     * que o gesto significa — e este campo diz onde ela FICA quando o dedo solta: no lugar de origem
     * (cancelar) ou no lugar novo (reposicionar).
     */
    val orbAt: Offset? = null,
    val micState: ButtonState? = null,
    val keyboardExpanded: Boolean? = null,
    val overlayHint: TourOverlayHint? = null,
)

/**
 * Um keyframe. [at] e [dragPath] são relativos à cena (0..1 em cada eixo), então o mesmo roteiro serve
 * ao card da aba Modo e a qualquer miniatura futura sem recalcular coordenada.
 */
data class GestureStep(
    val at: Offset,
    val action: FingerAction,
    val durationMs: Int,
    val reaction: SceneReaction = SceneReaction(),
    val dragPath: List<Offset> = emptyList(),
    val tapCount: Int = 1,
)

/**
 * Um gesto demonstrável. [title] vai na barra "PRÉVIA" (com as setas ‹ ›) e [description] no texto ao
 * lado, sincronizada com o passo corrente.
 */
data class GestureScript(
    val id: String,
    val title: String,
    val description: String,
    val scene: TourScene,
    val steps: List<GestureStep>,
)

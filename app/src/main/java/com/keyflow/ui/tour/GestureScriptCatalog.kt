package com.keyflow.ui.tour

import com.keyflow.i18n.tr
import androidx.compose.ui.geometry.Offset
import com.keyflow.core.ButtonState
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.fluxos.AcaoEfetiva
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.RoteadorGatilhos
import com.keyflow.ui.config.recordingHintParts
import com.keyflow.ui.config.shortcutActionLabel
import com.keyflow.ui.config.shortcutActionSub
import com.keyflow.ui.orb.OrbState

/**
 * Catálogo de roteiros do tour (SCR-02..06) — funções puras de [Settings].
 *
 * O tour mostra os gestos que valem para a configuração DAQUELA pessoa: trocar o modo de gravação troca
 * os básicos, mudar um atalho de rajada muda o roteiro correspondente, e atalho em `NONE` simplesmente
 * não aparece. Um conjunto fixo ensinaria gestos que não existem no aparelho de quem está lendo.
 *
 * As descrições saem de `recordingHintParts`/`shortcutActionSub`, os mesmos textos que a config e a Docs
 * mostram — fonte única, para o tour não envelhecer separado do resto.
 */

// A orbe ocupa o centro da cena; o alvo de cancelamento fica embaixo, como no overlay real.
private val ORB_CENTER = Offset(0.5f, 0.45f)
private val ORB_CANCEL_TARGET = Offset(0.5f, 0.88f)
private val ORB_PARKED = Offset(0.85f, 0.22f)

// Na cena do teclado, o mic e o botão de retrair ficam na barra de cima (mesma posição da BarView).
private val KBD_MIC = Offset(0.11f, 0.14f)
private val KBD_COLLAPSE = Offset(0.92f, 0.14f)
// Para onde o dedo vai ao arrastar a orbe para cima (o gesto fixo que pausa): reto acima do encaixe, na faixa que ela ocupa ao sobressair.
private val KBD_MIC_DRAG_UP = Offset(0.11f, 0.02f)

private const val SHORT = 420
private const val BEAT = 700
private const val HOLD = 900

/**
 * Roteiros da orbe, na ordem pedagógica do SCR-06: básicos de gravação → pausa → cancelar/desfazer →
 * reposicionar → atalhos de rajada.
 */
fun buildOrbScripts(settings: Settings, fluxos: ConfigFluxos = ConfigFluxos(emptyList(), emptyList())): List<GestureScript> {
    val mode = settings.overlayRecordingMode
    val parts = recordingHintParts(mode)
    val pushToTalk = mode == RecordingMode.PUSH_TO_TALK

    return buildList {
        if (pushToTalk) {
            add(pushToTalkBasics(parts))
            add(pushToTalkPause(parts))
        } else {
            add(toggleBasics(parts))
            add(togglePause(parts))
        }
        add(cancelAndUndo())
        add(reposition())
        addAll(shortcutScripts(settings, pushToTalk, fluxos))
    }
}

/** Roteiros do teclado (SCR-05): retrair/expandir → mic toque → mic segurar. */
fun buildKeyboardScripts(@Suppress("UNUSED_PARAMETER") settings: Settings): List<GestureScript> =
    listOf(collapseAndExpand(), micTap(), micHold())

// ── orbe · básicos ────────────────────────────────────────────────────────────

private fun toggleBasics(parts: List<String>) = GestureScript(
    id = "orb-toggle-basics",
    title = tr("Gravar e parar"),
    description = sentence(parts[0]),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Idle)),
        GestureStep(ORB_CENTER, FingerAction.TAP, BEAT, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.TAP, BEAT, SceneReaction(orbState = OrbState.Transcribing)),
    ),
)

private fun togglePause(parts: List<String>) = GestureScript(
    id = "orb-toggle-pause",
    title = tr("Pausar e retomar"),
    description = sentence(parts[1]),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.PRESS, HOLD, SceneReaction(orbState = OrbState.Paused)),
        GestureStep(ORB_CENTER, FingerAction.RELEASE, SHORT),
        GestureStep(ORB_CENTER, FingerAction.PRESS, HOLD, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.RELEASE, SHORT),
    ),
)

private fun pushToTalkBasics(parts: List<String>) = GestureScript(
    id = "orb-ptt-basics",
    title = tr("Falar e enviar"),
    description = sentence(parts[0] + " · " + parts[1]),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Idle)),
        GestureStep(ORB_CENTER, FingerAction.PRESS, HOLD, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.RELEASE, BEAT, SceneReaction(orbState = OrbState.Transcribing)),
    ),
)

private fun pushToTalkPause(parts: List<String>) = GestureScript(
    id = "orb-ptt-pause",
    title = tr("Pausar depois de soltar"),
    description = sentence(parts[2]),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.TAP, BEAT, SceneReaction(orbState = OrbState.Paused)),
        GestureStep(ORB_CENTER, FingerAction.PRESS, HOLD, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(ORB_CENTER, FingerAction.RELEASE, SHORT),
    ),
)

// ── orbe · cancelar/desfazer e reposicionar (valem nos dois modos) ────────────

private fun cancelAndUndo() = GestureScript(
    id = "orb-cancel-undo",
    title = tr("Cancelar e desfazer"),
    description = tr("Arraste a orbe até o alvo para cancelar · o áudio fica guardado enquanto o banner estiver na tela, e Desfazer devolve a gravação pausada."),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Listening)),
        GestureStep(
            at = ORB_CANCEL_TARGET,
            action = FingerAction.DRAG,
            durationMs = HOLD,
            reaction = SceneReaction(orbState = OrbState.Listening, overlayHint = TourOverlayHint.CANCEL_TARGET),
            dragPath = listOf(ORB_CENTER, Offset(0.5f, 0.68f), ORB_CANCEL_TARGET),
        ),
        GestureStep(
            at = ORB_CANCEL_TARGET,
            action = FingerAction.RELEASE,
            durationMs = BEAT,
            reaction = SceneReaction(
                orbState = OrbState.Idle,
                // Cancelou: a orbe volta para o lugar dela, o banner é que fica.
                orbAt = ORB_CENTER,
                overlayHint = TourOverlayHint.UNDO_BANNER,
            ),
        ),
        GestureStep(
            at = Offset(0.28f, 0.88f),
            action = FingerAction.TAP,
            durationMs = BEAT,
            reaction = SceneReaction(orbState = OrbState.Paused, overlayHint = TourOverlayHint.NONE),
        ),
    ),
)

private fun reposition() = GestureScript(
    id = "orb-reposition",
    title = tr("Reposicionar a orbe"),
    description = tr("Arraste a orbe para onde ela não atrapalhar · ela gruda na lateral mais próxima."),
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Idle)),
        GestureStep(
            at = ORB_PARKED,
            action = FingerAction.DRAG,
            durationMs = HOLD,
            dragPath = listOf(ORB_CENTER, Offset(0.7f, 0.32f), ORB_PARKED),
        ),
        // Reposicionar é o contrário do cancelar: soltou, ficou onde a pessoa pôs.
        GestureStep(ORB_PARKED, FingerAction.RELEASE, SHORT, SceneReaction(orbAt = ORB_PARKED)),
    ),
)

// ── orbe · atalhos de rajada (SCR-03) ────────────────────────────────────────

/**
 * Um roteiro por atalho realmente mapeado. As duas regras são as mesmas da aba Sobrepor: atalho em
 * `NONE` não vira roteiro, e o de 1 toque só existe no Push-to-talk — no modo Alternar o toque já é
 * da gravação e não sobra para atalho.
 */
private fun shortcutScripts(settings: Settings, pushToTalk: Boolean, fluxos: ConfigFluxos): List<GestureScript> =
    (1..3).mapNotNull { taps ->
        if (taps == 1 && !pushToTalk) return@mapNotNull null
        // O gesto ligado a um fluxo vale no lugar da ação do Settings (FLX-35, FLX-51): o tour descreve o que de fato acontece.
        when (val efetiva = RoteadorGatilhos.acaoEfetivaDoGesto(taps, settings, fluxos)) {
            is AcaoEfetiva.DeFluxo -> shortcutScript(taps, tr("Fluxo: %s", efetiva.nome ?: tr("indisponível")),
                if (efetiva.valido) tr("Roda o fluxo da aba Fluxos neste gesto, no lugar da ação de sempre.")
                else tr("Este gesto está ligado a um fluxo incompleto: nada roda até você completá-lo na aba Fluxos."))
            is AcaoEfetiva.DaConfig -> {
                if (efetiva.acao == OrbShortcutAction.NONE) return@mapNotNull null
                shortcutScript(taps, shortcutActionLabel(efetiva.acao), shortcutActionSub(efetiva.acao))
            }
        }
    }

private fun shortcutScript(taps: Int, label: String, sub: String) = GestureScript(
    id = "orb-shortcut-${taps}tap",
    title = "${if (taps == 1) tr("1 toque") else tr("%d toques", taps)} · $label",
    description = sub,
    scene = TourScene.ORB,
    steps = listOf(
        GestureStep(ORB_CENTER, FingerAction.MOVE, SHORT, SceneReaction(orbState = OrbState.Idle)),
        GestureStep(
            at = ORB_CENTER,
            action = FingerAction.MULTI_TAP,
            durationMs = BEAT + taps * 120,
            tapCount = taps,
        ),
    ),
)

// ── teclado (SCR-05) ─────────────────────────────────────────────────────────

private fun collapseAndExpand() = GestureScript(
    id = "kbd-collapse-expand",
    title = tr("Retrair e expandir"),
    description = tr("Toque na seta para encolher o teclado até a barra · toque de novo para trazer as teclas de volta."),
    scene = TourScene.KEYBOARD,
    steps = listOf(
        GestureStep(KBD_COLLAPSE, FingerAction.MOVE, SHORT, SceneReaction(keyboardExpanded = true)),
        GestureStep(KBD_COLLAPSE, FingerAction.TAP, BEAT, SceneReaction(keyboardExpanded = false)),
        GestureStep(KBD_COLLAPSE, FingerAction.TAP, BEAT, SceneReaction(keyboardExpanded = true)),
    ),
)

private fun micTap() = GestureScript(
    id = "kbd-mic-tap",
    title = tr("Orbe: gravar e enviar"),
    description = tr("Toque na orbe para começar a gravar · toque de novo para enviar e o texto cai no campo · 2 e 3 toques fazem os atalhos que você escolheu."),
    scene = TourScene.KEYBOARD,
    steps = listOf(
        GestureStep(KBD_MIC, FingerAction.MOVE, SHORT, SceneReaction(micState = ButtonState.IDLE_READY)),
        GestureStep(KBD_MIC, FingerAction.TAP, BEAT, SceneReaction(micState = ButtonState.RECORDING)),
        GestureStep(KBD_MIC, FingerAction.TAP, BEAT, SceneReaction(micState = ButtonState.SENDING)),
    ),
)

private fun micHold() = GestureScript(
    id = "kbd-mic-hold",
    title = tr("Orbe: segurar e arrastar"),
    description = tr("Gravando, a orbe sobressai acima do teclado · segure e arraste para cima para pausar, para o lado para cancelar, para baixo para abrir o app."),
    scene = TourScene.KEYBOARD,
    steps = listOf(
        GestureStep(KBD_MIC, FingerAction.MOVE, SHORT, SceneReaction(micState = ButtonState.RECORDING)),
        GestureStep(at = KBD_MIC, action = FingerAction.PRESS, durationMs = HOLD),
        GestureStep(
            at = KBD_MIC_DRAG_UP,
            action = FingerAction.DRAG,
            durationMs = BEAT,
            dragPath = listOf(KBD_MIC, KBD_MIC_DRAG_UP),
        ),
        GestureStep(
            at = KBD_MIC_DRAG_UP,
            action = FingerAction.RELEASE,
            durationMs = BEAT,
            reaction = SceneReaction(micState = ButtonState.PAUSED),
        ),
    ),
)

/** Os pedaços de `recordingHintParts` vêm sem ponto final (a config os junta numa frase só). */
private fun sentence(text: String): String =
    text.replaceFirstChar { it.uppercase() } + "."

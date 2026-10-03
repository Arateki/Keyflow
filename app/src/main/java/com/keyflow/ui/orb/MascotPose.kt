package com.keyflow.ui.orb

import kotlin.math.abs
import kotlin.math.sin

/**
 * A pose do mascote em um instante: tudo o que se mexe, já calculado — o desenho ([drawMascotOrb]) só lê estes números.
 * Função pura de `(t em ms, estado)` — [update] não lê nada além dos argumentos; o `hasCenter` só muda o espaço que o
 * desenho dá ao rosto —: relógio parado, pose parada. É uma classe mutável de propósito — o desenho reaproveita uma instância por quadro em vez de alocar uma — e o
 * teste de unidade confere que o resultado não depende do que a instância guardava antes.
 *
 * Os princípios de animação aplicados aqui (e não só "mexer"):
 * - **respiração** com squash & stretch (o corpo estica e afina ao inspirar, com os pés parados no chão);
 * - **atraso e acompanhamento** (follow-through): as orelhas e o rabo não seguem o corpo no mesmo instante — repetem o
 *   balanço dele com 160 ms de atraso, e a ponta com 330 ms, então arrastam atrás quando o corpo balança e passam um
 *   pouco do ponto quando ele para;
 * - **nunca periódico**: cada movimento soma duas ou três senoides de períodos sem divisor comum (no estilo do
 *   [orbLevel]) e os eventos raros — piscar, tremer a orelha, olhar de lado, abanar o rabo — vêm do [MascotTimeline];
 * - **personalidade por estado**: atento escutando (orelhas em pé, olhos arregalados, pulsando com a voz),
 *   sonolento pausado (orelhas caídas, pálpebras pesadas, cabeça caindo) e "pensando" transcrevendo (cabeça
 *   inclinada, olhos vagando para cima, orelhas alternando, pontinhos acima da cabeça).
 */
internal class MascotPose {
    // Corpo: a escala é em torno dos pés (que ficam no chão); a inclinação, em torno dos pés ou, com texto no centro, do centro da
    // orbe (ver `leanPivotY` em MascotOrb.kt); [bob] sobe e desce o conjunto.
    var scaleX = 1f
    var scaleY = 1f
    var lean = 0f
    var bob = 0f

    /** Ângulo de cada orelha em graus a partir da vertical (positivo = ponta para a direita) e o quanto a ponta dobra. */
    var earL = 0f
    var earR = 0f
    var earBendL = 0f
    var earBendR = 0f
    var earLen = 1f

    /**
     * Olhos: a piscada (0 aberto .. 1 fechado: o olho achata), a pálpebra caída de base (0 .. 1: o sonolento a deixa a meio
     * caminho, e ela corta o olho por cima), o tamanho, o olhar (-1..1 nos dois eixos) e o brilho extra (olho cintilando).
     */
    var blink = 0f
    var lid = 0f
    var eyeScale = 1f
    var gazeX = 0f
    var gazeY = 0f
    var sparkle = 0f

    /** Boca: sorriso (-1 curva para baixo .. 1 sorriso) e abertura (0 fechada .. 1 aberta). */
    var smile = 1f
    var mouthOpen = 0f

    /** Bochechas: opacidade (0..1). */
    var cheek = 0.5f

    /** Rabo: ângulo e dobra da ponta, como nas orelhas. */
    var tail = 0f
    var tailBend = 0f

    /** O topete entre as orelhas: ângulo em graus (positivo = ponta para a direita). Balança com o corpo, mais solto que as orelhas. */
    var tuft = 0f

    /** Brilho do peito: pulsa com a voz quando escuta. */
    var chestGlow = 0f

    /** Fase dos efeitos do estado (0..1 em loop): arcos de som escutando, "z" pausado. Pontinhos: [thinkPhase]. */
    var soundPhase = 0f
    var zPhase = 0f
    var thinkPhase = 0f

    /** O quanto o olho está aberto, de 0 a 1, juntando a piscada e a pálpebra caída. */
    val eyeOpen: Float get() = (1f - blink) * (1f - lid)

    /** Energia da voz em 0..1 (só escutando): sobe e desce com o [orbLevel]. */
    var energy = 0f

    fun update(t: Float, state: OrbState) {
        val lvl = orbLevel(state, t)
        energy = if (state == OrbState.Listening) ((lvl - 0.6f) / 0.4f).coerceIn(0f, 1f) else 0f
        val b = breathAt(t, state)
        val leanNow = leanAt(t, state)
        lean = leanNow

        // Respiração: squash & stretch com volume quase constante (estica na vertical, afina na horizontal).
        when (state) {
            OrbState.Idle -> {
                scaleY = 1f + 0.024f * b
                scaleX = 1f - 0.014f * b
                bob = 0.006f * sin(t * 0.0021f + 0.7f)
            }
            OrbState.Listening -> {
                // Pulsa com a voz: infla com a energia, além da respiração (mais rápida que a do repouso).
                scaleY = 1f + 0.016f * b + 0.014f * energy
                scaleX = 1f - 0.009f * b + 0.014f * energy
                bob = 0.004f * sin(t * 0.0047f) - 0.004f * energy
            }
            OrbState.Paused -> {
                // Respiração funda e lenta: segura o fôlego e solta.
                scaleY = 1f + 0.030f * b
                scaleX = 1f - 0.018f * b
                bob = 0.012f + 0.004f * sin(t * 0.0013f)
            }
            OrbState.Transcribing -> {
                scaleY = 1f + 0.022f * b
                scaleX = 1f - 0.012f * b
                bob = -0.010f * abs(sin(t * 0.0042f)) // quica de leve, no ritmo do balanço
            }
        }

        // Orelhas: ângulo de repouso do estado + o balanço do corpo com atraso + a tremida rara. A ponta atrasa mais
        // ainda ([lagB]): é a parte que "chicoteia".
        val lagA = leanAt(t - 160f, state) - leanNow
        val lagB = leanAt(t - 330f, state) - leanAt(t - 160f, state)
        val spreadL: Float
        val spreadR: Float
        val droop: Float
        when (state) {
            OrbState.Idle -> {
                spreadL = 25f + 2.5f * sin(t * 0.0013f + 0.6f)
                spreadR = 25f + 2.5f * sin(t * 0.0017f + 2.4f)
                droop = 0.014f
            }
            OrbState.Listening -> {
                // Em pé e viradas para a frente; tremem com a voz, mais quando ela está forte.
                spreadL = 9f - 3f * energy + 1.8f * energy * sin(t * 0.0119f)
                spreadR = 9f - 3f * energy + 1.8f * energy * sin(t * 0.0131f + 1.2f)
                droop = -0.012f
            }
            OrbState.Paused -> {
                // Caídas para os lados: relaxadas, descem um pouco mais a cada expiração.
                spreadL = 66f + 3f * sin(t * 0.0011f + 0.3f) - 2f * b
                spreadR = 66f + 3f * sin(t * 0.0012f + 1.9f) - 2f * b
                droop = 0.060f
            }
            OrbState.Transcribing -> {
                // Alternam: uma sobe enquanto a outra cai (uma batida a cada ~1,65 s), como quem pondera dois lados.
                val a = sin(t * 0.0038f)
                spreadL = 10f + 48f * (1f + a) * 0.5f
                spreadR = 10f + 48f * (1f - a) * 0.5f
                droop = 0.025f
            }
        }
        earL = -spreadL + FOLLOW * lagA + MascotTimeline.earFlickDeg(t, state, rightEar = false)
        earR = spreadR + FOLLOW * lagA + MascotTimeline.earFlickDeg(t, state, rightEar = true)
        // A ponta dobra seguindo o atraso do balanço, e para fora quando a orelha cai.
        earBendL = (-droop + lagB * 0.030f).coerceIn(-MAX_BEND, MAX_BEND)
        earBendR = (droop + lagB * 0.030f).coerceIn(-MAX_BEND, MAX_BEND)
        earLen = when (state) {
            OrbState.Listening -> 1.02f + 0.02f * energy
            OrbState.Paused -> 0.94f
            else -> 1f
        } * (1f + 0.012f * breathAt(t - 120f, state))

        // Olhos: a piscada vem do calendário e, no sonolento, há uma pálpebra pesada de base.
        blink = MascotTimeline.blink(t, state)
        lid = when (state) {
            OrbState.Paused -> 0.52f + 0.07f * sin(t * 0.0009f + 1f)
            OrbState.Transcribing -> 0.08f
            else -> 0f
        }
        eyeScale = when (state) {
            OrbState.Listening -> 1.12f + 0.05f * energy
            OrbState.Paused -> 0.96f
            else -> 1f
        }
        sparkle = when (state) {
            OrbState.Listening -> 0.5f + 0.5f * energy
            OrbState.Idle -> 0.35f + 0.15f * sin(t * 0.0024f)
            OrbState.Transcribing -> 0.25f
            OrbState.Paused -> 0f
        }

        // Olhar: um olhar de lado raro (ou o vaivém do "pensando") sobre uma pequena deriva de base do estado.
        val g = MascotTimeline.glanceAmount(t, state)
        val driftX = when (state) {
            OrbState.Idle -> 0.12f * sin(t * 0.0009f + 1.1f)
            OrbState.Listening -> -0.10f + 0.10f * sin(t * 0.0013f) // procura de onde vem o som
            OrbState.Paused -> 0f
            OrbState.Transcribing -> 0f
        }
        val driftY = when (state) {
            OrbState.Paused -> 0.65f // olhos caídos
            OrbState.Listening -> -0.15f
            else -> 0f
        }
        gazeX = driftX + g * MascotTimeline.glanceTargetX(t, state)
        gazeY = driftY + g * MascotTimeline.glanceTargetY(t, state)

        // Boca e bochechas.
        when (state) {
            OrbState.Idle -> { smile = 1f; mouthOpen = 0f; cheek = 0.55f }
            OrbState.Listening -> { smile = 0.4f; mouthOpen = 0.35f + 0.55f * energy; cheek = 0.62f }
            OrbState.Paused -> { smile = 0.2f; mouthOpen = 0.22f + 0.14f * b; cheek = 0.40f }
            OrbState.Transcribing -> { smile = -0.25f; mouthOpen = 0f; cheek = 0.45f }
        }

        // Rabo: balança devagar e, em repouso, abana de vez em quando; escutando fica em pé, tenso; pausado, caído.
        val wag = if (state == OrbState.Idle) MascotTimeline.wag(t) else 0f
        tail = when (state) {
            OrbState.Idle -> 8f * sin(t * 0.0021f + 0.9f) + 4f * sin(t * 0.0047f) + 26f * wag * sin(t * 0.0215f)
            OrbState.Listening -> -16f + 2.5f * energy * sin(t * 0.0151f) + 3f * sin(t * 0.0023f)
            OrbState.Paused -> 24f + 2f * sin(t * 0.0010f)
            OrbState.Transcribing -> 14f * sin(t * 0.0068f) + 5f * sin(t * 0.0153f + 1f)
        } + FOLLOW * lagA
        tailBend = (0.030f * lagB + 0.0020f * tail).coerceIn(-MAX_BEND, MAX_BEND)

        // O topete é o mais leve: balança sozinho, por cima do balanço atrasado do corpo, e cai para o lado quando dorme.
        tuft = when (state) {
            OrbState.Idle -> 12f * sin(t * 0.0026f + 0.2f) + 5f * sin(t * 0.0061f + 1.4f)
            OrbState.Listening -> 4f * sin(t * 0.0049f) + 6f * energy * sin(t * 0.0167f + 0.5f)
            OrbState.Paused -> 32f + 3f * sin(t * 0.0012f)
            OrbState.Transcribing -> 18f * sin(t * 0.0042f + 0.6f) + 4f * sin(t * 0.0111f)
        } + FOLLOW * 1.4f * lagA

        chestGlow = energy

        soundPhase = (t * 0.00072f) % 1f
        zPhase = (t * 0.00034f) % 1f
        thinkPhase = (t * 0.00185f) % 1f
    }

    companion object {
        /** A parte fracionária de [x] (0 inclusive, 1 exclusive): a fase de um efeito em loop. */
        fun wrapPhase(x: Float): Float = x - kotlin.math.floor(x)

        /** Ganho do acompanhamento: o balanço é lento (±2,4° em 6 s), então o atraso de 160 ms sozinho não se veria. */
        private const val FOLLOW = 3f

        /** O quanto a ponta de uma orelha (ou do rabo) pode dobrar para o lado, em unidades: acima disso a folha vira uma foice. */
        private const val MAX_BEND = 0.05f

        /** A respiração em -1..1: duas senoides de períodos sem divisor comum, por estado. */
        fun breathAt(t: Float, state: OrbState): Float = when (state) {
            OrbState.Idle -> (sin(t * 0.00175f) + 0.22f * sin(t * 0.0043f + 1.3f)) / 1.22f
            OrbState.Listening -> (sin(t * 0.0033f) + 0.25f * sin(t * 0.0079f + 0.4f)) / 1.25f
            OrbState.Paused -> (sin(t * 0.00115f) + 0.18f * sin(t * 0.0029f + 2.1f)) / 1.18f
            OrbState.Transcribing -> (sin(t * 0.0042f) + 0.30f * sin(t * 0.0093f + 1.0f)) / 1.30f
        }

        /** O balanço do corpo em graus (positivo = para a direita), em torno dos pés. Orelhas e rabo o repetem com atraso. */
        fun leanAt(t: Float, state: OrbState): Float = when (state) {
            OrbState.Idle -> 2.4f * sin(t * 0.00106f + 0.4f) + 1.2f * sin(t * 0.00243f + 2.0f)
            OrbState.Listening -> -2.5f + 1.4f * sin(t * 0.0013f) + 1.0f * orbLevelEnergy(t) * sin(t * 0.0071f)
            OrbState.Paused -> 3.8f + 2.6f * sin(t * 0.0007f)
            OrbState.Transcribing -> -5.5f + 3f * sin(t * 0.0041f) + 1f * sin(t * 0.0097f + 1f)
        }

        private fun orbLevelEnergy(t: Float): Float = ((orbLevel(OrbState.Listening, t) - 0.6f) / 0.4f).coerceIn(0f, 1f)
    }
}

/** A pose do mascote em [t] (ms): atalho para os testes e para quem não quer reaproveitar a instância. */
internal fun mascotPose(t: Float, state: OrbState): MascotPose =
    MascotPose().also { it.update(t, state) }

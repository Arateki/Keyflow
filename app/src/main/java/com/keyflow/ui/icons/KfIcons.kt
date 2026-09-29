package com.keyflow.ui.icons

/**
 * Ícone próprio do KeyFlow: paths SVG num grid 24×24, desenhados em traço de ponta e junção
 * arredondadas ([strokes]) mais detalhes cheios ([fills]). É só dado — sem Android nem Compose — para o
 * mesmo desenho sair igual no app ([KfIcon], Compose) e no teclado ([KfIconDrawer], `android.graphics`).
 *
 * Linguagem do conjunto: área útil de ~3 a 21, traço fino (1,6 no grid), cantos de raio 2–2,75 e, quando
 * o ícone pede um ponto focal, um disco cheio — o mesmo "ponto" da orbe.
 */
class KfIconSpec(
    val name: String,
    val strokes: List<String>,
    val fills: List<String> = emptyList(),
    /** Espessura do traço em unidades do grid 24 (escala junto com o ícone). */
    val strokeWidth: Float = 1.6f,
)

object KfIcons {

    // ── barra do teclado ────────────────────────────────────────────────────

    /** Microfone de cápsula: corpo, arco do suporte, haste e base. */
    val Mic = KfIconSpec(
        "mic",
        listOf(
            "M12 3.25a2.75 2.75 0 0 1 2.75 2.75v5.5a2.75 2.75 0 0 1 -5.5 0V6A2.75 2.75 0 0 1 12 3.25z",
            "M6 11.25a6 6 0 0 0 12 0",
            "M12 17.25v3.5",
            "M9.25 20.75h5.5",
        ),
    )

    /** Ajustes (abre o app): dois trilhos com cursor — lê como "configurar" sem competir com o sol do tema. */
    val Adjust = KfIconSpec(
        "adjust",
        listOf(
            "M4 8h8.5",
            "M17.5 8H20",
            circle(15f, 8f, 2.5f),
            "M4 16h2.5",
            "M11.5 16H20",
            circle(9f, 16f, 2.5f),
        ),
    )

    val ChevronUp = KfIconSpec("chevron-up", listOf("M6.5 14.75L12 9.25l5.5 5.5"))

    val ChevronDown = KfIconSpec("chevron-down", listOf("M6.5 9.25L12 14.75l5.5 -5.5"))

    /** Enter: haste que desce e volta para a esquerda, com a ponta aberta. */
    val Return = KfIconSpec(
        "return",
        listOf(
            "M19.5 5.5v6a3 3 0 0 1 -3 3H5.5",
            "M9 11l-3.5 3.5L9 18",
        ),
    )

    /** Prancheta: prancha, presilha e duas linhas de texto. */
    val Paste = KfIconSpec(
        "paste",
        listOf(
            "M9 4.75H7.5a2 2 0 0 0 -2 2v12.5a2 2 0 0 0 2 2h9a2 2 0 0 0 2 -2V6.75a2 2 0 0 0 -2 -2H15",
            "M10.25 3h3.5a1.25 1.25 0 0 1 1.25 1.25v1a1.25 1.25 0 0 1 -1.25 1.25h-3.5A1.25 1.25 0 0 1 9 5.25v-1A1.25 1.25 0 0 1 10.25 3z",
            "M9 11.5h6",
            "M9 15h4",
        ),
    )

    /** Gatilho de grade: quatro quadrados arredondados vazados (o "apps" de sempre, mais leve). */
    val Grid = KfIconSpec(
        "grid",
        listOf(
            roundSquare(4f, 4f, 6.5f, 1.75f),
            roundSquare(13.5f, 4f, 6.5f, 1.75f),
            roundSquare(4f, 13.5f, 6.5f, 1.75f),
            roundSquare(13.5f, 13.5f, 6.5f, 1.75f),
        ),
    )

    /** Alavanca parada: base circular com a bola no centro (a `BarView` desloca a bola ao puxar). */
    val Joystick = KfIconSpec(
        "joystick",
        listOf(circle(12f, 12f, 8f)),
        fills = listOf(circle(12f, 12f, 3f)),
    )

    // ── estados do microfone ────────────────────────────────────────────────

    val RecordDot = KfIconSpec("record", emptyList(), fills = listOf(circle(12f, 12f, 4.5f)))

    val Pause = KfIconSpec("pause", listOf("M9 6.5v11", "M15 6.5v11"), strokeWidth = 2.4f)

    /** Retomar: triângulo cheio de cantos macios — par visual da [Pause]. */
    val Play = KfIconSpec(
        "play",
        emptyList(),
        fills = listOf("M8.5 6.3v11.4a1 1 0 0 0 1.5 0.85l8.9 -5.7a1 1 0 0 0 0 -1.7L10 5.45A1 1 0 0 0 8.5 6.3z"),
    )

    /** Desfazer: seta que volta e faz a curva de retorno. */
    val Undo = KfIconSpec(
        "undo",
        listOf("M9 6.5L5 10.5l4 4", "M5 10.5h9.5a4.5 4.5 0 0 1 0 9H11.5"),
    )

    val Sending = KfIconSpec(
        "sending",
        emptyList(),
        fills = listOf(circle(6f, 12f, 1.6f), circle(12f, 12f, 1.6f), circle(18f, 12f, 1.6f)),
    )

    val Close = KfIconSpec("close", listOf("M7 7l10 10", "M17 7L7 17"))

    val Trash = KfIconSpec(
        "trash",
        listOf(
            "M4.5 6.75h15",
            "M9.75 6.75V5a1.25 1.25 0 0 1 1.25 -1.25h2A1.25 1.25 0 0 1 14.25 5v1.75",
            "M6.5 6.75l0.85 11.6a2 2 0 0 0 2 1.9h5.3a2 2 0 0 0 2 -1.9l0.85 -11.6",
            "M10.25 10.75v5.5",
            "M13.75 10.75v5.5",
        ),
    )

    // ── app ────────────────────────────────────────────────────────────────

    val Check = KfIconSpec("check", listOf("M5 12.75l4.25 4.25L19 7.25"))

    val Plus = KfIconSpec("plus", listOf("M12 5.5v13", "M5.5 12h13"))

    val ChevronRight = KfIconSpec("chevron-right", listOf("M9.5 6.5L15 12l-5.5 5.5"))

    val ChevronLeft = KfIconSpec("chevron-left", listOf("M14.5 6.5L9 12l5.5 5.5"))

    val Lock = KfIconSpec(
        "lock",
        listOf(
            "M7 10.5h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2H7a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z",
            "M8.25 10.5V8a3.75 3.75 0 0 1 7.5 0v2.5",
        ),
    )

    val LockOpen = KfIconSpec(
        "lock-open",
        listOf(
            "M7 10.5h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2H7a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z",
            "M8.25 10.5V8a3.75 3.75 0 0 1 7.3 -1.2",
        ),
    )

    val Globe = KfIconSpec(
        "globe",
        listOf(
            circle(12f, 12f, 8.75f),
            "M3.5 12h17",
            "M12 3.25c-2.3 2.35 -3.4 5.25 -3.4 8.75s1.1 6.4 3.4 8.75",
            "M12 3.25c2.3 2.35 3.4 5.25 3.4 8.75s-1.1 6.4 -3.4 8.75",
        ),
    )

    val Moon = KfIconSpec("moon", listOf("M19.5 14.2A7.75 7.75 0 1 1 9.8 4.5a6.25 6.25 0 0 0 9.7 9.7z"))

    val Sun = KfIconSpec(
        "sun",
        listOf(
            circle(12f, 12f, 3.75f),
            "M12 3.25v1.5M12 19.25v1.5M3.25 12h1.5M19.25 12h1.5",
            "M5.8 5.8l1.05 1.05M17.15 17.15l1.05 1.05M18.2 5.8l-1.05 1.05M6.85 17.15L5.8 18.2",
        ),
    )

    // ── tour ────────────────────────────────────────────────────────────────

    /**
     * Mão apontando (o "dedo" do tour): silhueta fechada — o tour preenche de papel e contorna de tinta —
     * mais as dobras entre os dedos. A ponta do indicador fica em [HAND_TIP_X], [HAND_TIP_Y]: é esse ponto
     * que encosta no alvo do toque.
     */
    val Hand = KfIconSpec(
        "hand",
        listOf(
            "M8.5 13V4a1.5 1.5 0 0 1 3 0V10a1.5 1.5 0 0 1 3 0v0.75a1.5 1.5 0 0 1 3 0v1a1.5 1.5 0 0 1 3 0V16" +
                "c0 3.6 -2.9 6.5 -6.5 6.5h-1.2c-2 0 -3.4 -0.7 -4.7 -2l-4 -4a1.45 1.45 0 0 1 2.05 -2.05L8.5 16.5z",
            "M11.5 10v2.6",
            "M14.5 10.75v2",
            "M17.5 11.75v1.4",
        ),
    )

    const val HAND_TIP_X = 10f
    const val HAND_TIP_Y = 2.5f

    // ── abas da configuração ────────────────────────────────────────────────

    /** Modo: interruptor com o botão cheio — ligar/desligar cada modo. */
    val TabModo = KfIconSpec(
        "tab-modo",
        listOf("M8 6.5h8a5.5 5.5 0 0 1 0 11H8a5.5 5.5 0 0 1 0 -11z"),
        fills = listOf(circle(16f, 12f, 2.4f)),
    )

    /** Voz: forma de onda simétrica. */
    val TabVoz = KfIconSpec(
        "tab-voz",
        listOf("M4 10.5v3", "M8 7.5v9", "M12 4.5v15", "M16 8.5v7", "M20 10.5v3"),
    )

    /** Teclado: moldura, fileira de teclas e barra de espaço. */
    val TabTeclado = KfIconSpec(
        "tab-teclado",
        listOf(
            "M4.5 6h15a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1 -2.5 2.5h-15A2.5 2.5 0 0 1 2 15.5v-7A2.5 2.5 0 0 1 4.5 6z",
            "M8.5 14.25h7",
        ),
        fills = listOf(circle(6.75f, 10.25f, 1f), circle(10.25f, 10.25f, 1f), circle(13.75f, 10.25f, 1f), circle(17.25f, 10.25f, 1f)),
    )

    /**
     * Sobrepor: o aparelho com a orbe pousada na borda, por cima do app. A borda direita se interrompe em
     * volta da orbe — o respiro é o que faz ela ler como "flutuando", e não como um botão do aparelho.
     */
    val TabSobrepor = KfIconSpec(
        "tab-sobrepor",
        listOf(
            "M17.75 9.5V5a2.25 2.25 0 0 0 -2.25 -2.25h-7A2.25 2.25 0 0 0 6.25 5v14a2.25 2.25 0 0 0 2.25 2.25h7" +
                "a2.25 2.25 0 0 0 2.25 -2.25v-0.5",
            circle(17.75f, 14f, 3.25f),
        ),
        fills = listOf(circle(17.75f, 14f, 1.4f)),
    )

    /** Docs: livro aberto. */
    val TabDocs = KfIconSpec(
        "tab-docs",
        listOf(
            "M12 7c-1.6 -1.5 -4.2 -2.25 -8 -2.25v12.5c3.8 0 6.4 0.75 8 2.25",
            "M12 7c1.6 -1.5 4.2 -2.25 8 -2.25v12.5c-3.8 0 -6.4 0.75 -8 2.25",
            "M12 7v12.5",
        ),
    )

    /**
     * Visão: um olho — o contorno em amêndoa, a íris e a pupila. A curva sobe e desce suave, como os
     * outros ícones de aba (traço 1,6 no grid 24).
     */
    val TabVisao = KfIconSpec(
        "tab-visao",
        listOf(
            "M2 12C4.4 8 7.9 5.75 12 5.75C16.1 5.75 19.6 8 22 12C19.6 16 16.1 18.25 12 18.25C7.9 18.25 4.4 16 2 12z",
            circle(12f, 12f, 3.25f),
        ),
        fills = listOf(circle(12f, 12f, 1.25f)),
    )

    /**
     * Raciocínio: um cérebro visto de cima — os dois hemisférios (contornos ondulados), a fissura no meio e
     * as dobras de cada lado, espelhadas.
     */
    val TabRaciocinio = KfIconSpec(
        "tab-raciocinio",
        listOf(
            // Hemisfério esquerdo e o direito, espelhado.
            "M12 4.75C10.9 3.6 9 3.5 7.9 4.6C6.2 4.4 4.9 5.8 5 7.4C3.7 8 3.2 9.6 3.9 10.9C3.2 12.1 3.4 13.6 4.4 14.5" +
                "C4.3 16.1 5.5 17.4 7 17.4C7.6 18.7 9.2 19.4 10.6 18.8C11.1 18.6 11.6 18.2 12 17.7",
            "M12 4.75C13.1 3.6 15 3.5 16.1 4.6C17.8 4.4 19.1 5.8 19 7.4C20.3 8 20.8 9.6 20.1 10.9C20.8 12.1 20.6 13.6 19.6 14.5" +
                "C19.7 16.1 18.5 17.4 17 17.4C16.4 18.7 14.8 19.4 13.4 18.8C12.9 18.6 12.4 18.2 12 17.7",
            // A fissura entre os dois.
            "M12 4.75V17.7",
            // Dobras, um par de cada lado.
            "M5 7.4C6.4 7.6 7.4 8.4 7.6 9.6",
            "M3.9 10.9C5.2 11 6.4 11.8 6.7 13",
            "M10.8 9.5C9.5 9.7 8.6 10.6 8.5 11.8",
            "M19 7.4C17.6 7.6 16.6 8.4 16.4 9.6",
            "M20.1 10.9C18.8 11 17.6 11.8 17.3 13",
            "M13.2 9.5C14.5 9.7 15.4 10.6 15.5 11.8",
        ),
    )

    /**
     * Voz: uma boca vista de frente — o contorno dos lábios, com o arco do lábio de cima e a linha entre os
     * dois. (A aba Transcrição, cujo ícone é a onda, é quem ouve; a Voz é quem fala.)
     */
    val TabBoca = KfIconSpec(
        "tab-boca",
        listOf(
            // Os lábios: canto esquerdo, o arco de cima com o vão no meio, canto direito, e a curva de baixo.
            "M2.5 12C4.6 9.6 7 8 9.3 8.2C10.5 8.3 11.2 9.3 12 9.3C12.8 9.3 13.5 8.3 14.7 8.2C17 8 19.4 9.6 21.5 12" +
                "C19.4 15.6 16 17.6 12 17.6C8 17.6 4.6 15.6 2.5 12z",
            // A linha da boca, entre os lábios.
            "M2.5 12C6 13 8.5 12.4 12 12.4C15.5 12.4 18 13 21.5 12",
        ),
    )

    /** Todos os ícones — o teste de unidade confere nomes únicos e paths bem-formados. */
    /**
     * Sem limite ("∞" nos seletores de tempo da aba Transcrição): duas alças que se cruzam no centro. O
     * glifo de fonte saía miúdo ao lado de "15s"/"5m".
     */
    val Infinity = KfIconSpec(
        "infinity",
        listOf(
            "M12 12C10 9.5 8.6 8.25 6.75 8.25a3.75 3.75 0 0 0 0 7.5C8.6 15.75 10 14.5 12 12z",
            "M12 12C14 14.5 15.4 15.75 17.25 15.75a3.75 3.75 0 0 0 0 -7.5C15.4 8.25 14 9.5 12 12z",
        ),
        strokeWidth = 1.8f,
    )

    val all: List<KfIconSpec> = listOf(
        Mic, Adjust, ChevronUp, ChevronDown, Return, Paste, Grid, Joystick,
        RecordDot, Pause, Play, Undo, Sending, Close, Trash, Hand,
        Check, Plus, ChevronRight, ChevronLeft, Lock, LockOpen, Globe, Moon, Sun,
        TabModo, TabVoz, TabVisao, TabRaciocinio, TabBoca, TabTeclado, TabSobrepor, TabDocs, Infinity,
    )
}

/** Círculo como path SVG (dois arcos), para caber no mesmo formato dos demais traços. */
internal fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** Quadrado de lado [side] com cantos de raio [r], a partir do canto superior esquerdo ([x], [y]). */
internal fun roundSquare(x: Float, y: Float, side: Float, r: Float): String {
    val inner = side - 2 * r
    return "M${x + r} ${y}h${inner}a$r $r 0 0 1 $r $r" +
        "v${inner}a$r $r 0 0 1 ${-r} $r" +
        "h${-inner}a$r $r 0 0 1 ${-r} ${-r}" +
        "v${-inner}a$r $r 0 0 1 $r ${-r}z"
}

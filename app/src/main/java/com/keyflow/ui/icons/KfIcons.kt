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
     * Raciocínio: um cérebro visto de lado, virado para a esquerda — o contorno ondulado do cérebro com o
     * lóbulo temporal embaixo, três dobras e o tronco descendo. De perfil ele lê melhor no tamanho pequeno da
     * barra lateral do que visto de cima (Yan, 2026-09-29). Sem cerebelo e com poucas dobras de propósito: com
     * traço 1,6 qualquer vão menor que ~2 unidades fecha e vira mancha.
     */
    val TabRaciocinio = KfIconSpec(
        "tab-raciocinio",
        listOf(
            // O contorno: testa, as ondas de cima, a nuca e o lóbulo temporal por baixo.
            "M7.4 15.1C5 14.8 3.5 12.8 3.8 10.6C4 8.8 5 7.6 6.4 7.1C6.5 5.2 8.3 4 10 4.5C11 3.2 13 3.2 14 4.2" +
                "C15.6 3.8 17.4 4.4 18 6C19.8 6.6 20.7 8.6 20.2 10.5C20.7 11.7 20.3 13 19.4 13.8" +
                "C18.6 14.5 17.4 14.7 16.4 14.4C15 14.5 14 15.2 13.4 16C12.2 17.1 9.8 17.1 8.9 16C8.5 15.6 8 15.3 7.4 15.1z",
            // A fissura lateral, que separa o temporal.
            "M8.8 12C10.4 12.4 12.2 11.6 13.4 10.2",
            // Duas dobras: uma na testa e uma no alto.
            "M6.4 7.1C7.6 7.6 8.2 8.6 8.2 9.6",
            "M14 4.2C13.8 5.6 14.4 6.8 15.6 7.2",
            // O tronco encefálico.
            "M12.6 16.8C12.6 18.2 12.9 19.5 13.3 20.6",
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

    /**
     * Notificação: um sino — o corpo que alarga na base, o badalo embaixo e o pino em cima. O mesmo traço dos outros
     * ícones de aba (1,6 no grid 24).
     */
    val TabNotificacao = KfIconSpec(
        "tab-notificacao",
        listOf(
            "M6 16.5v-5.5a6 6 0 0 1 12 0v5.5l1.6 2H4.4z",
            "M10 20.75a2.1 2.1 0 0 0 4 0",
            "M12 3.25V5",
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

    /**
     * Fluxos: nós conectados em pipeline/ramificação — representação de fluxos de interação
     * entre as capacidades e interfaces.
     */
    val TabFluxos = KfIconSpec(
        "tab-fluxos",
        listOf(
            circle(6f, 6f, 2.5f),
            circle(18f, 12f, 2.5f),
            circle(6f, 18f, 2.5f),
            "M8.5 6h3.5a3 3 0 0 1 3 3v6a3 3 0 0 1 -3 3H8.5",
            "M15 12h0.5",
        ),
        fills = listOf(
            circle(6f, 6f, 1f),
            circle(18f, 12f, 1f),
            circle(6f, 18f, 1f),
        ),
    )

    /**
     * Computador: monitor com terminal/prompt interno e suporte — acesso SSH e shell.
     */
    val TabComputador = KfIconSpec(
        "tab-computador",
        listOf(
            "M4.5 4.5h15a2 2 0 0 1 2 2v8.5a2 2 0 0 1 -2 2h-15a2 2 0 0 1 -2 -2V6.5a2 2 0 0 1 2 -2z",
            "M12 17v3",
            "M8.5 20h7",
            "M7.5 8.5l2.5 2.25l-2.5 2.25",
            "M12 13h3",
        ),
    )

    /**
     * Provedores: nuvem com conexões e servidores — perfis e chaves de IA em nuvem.
     */
    val TabProvedores = KfIconSpec(
        "tab-provedores",
        listOf(
            "M6.5 17.5h11a3.5 3.5 0 0 0 .5 -6.96A5 5 0 0 0 7.8 8.8A3.75 3.75 0 0 0 6.5 17.5z",
            "M10 13.5h4",
        ),
        fills = listOf(circle(12f, 13.5f, 1.25f)),
    )

    /**
     * KeyFlow: orbe central com anéis de fluxo — o agente principal.
     */
    val TabKeyflow = KfIconSpec(
        "tab-keyflow",
        listOf(
            circle(12f, 12f, 7.5f),
            "M6.5 12a5.5 5.5 0 0 1 11 0",
        ),
        fills = listOf(circle(12f, 12f, 2.75f)),
    )

    // ── Logos de provedores ────────────────────────────────────────────────
    val LogoAnthropic = KfIconSpec(
        "logo-anthropic",
        emptyList(),
        fills = listOf(
            "M17.3041 3.541h-3.6718l6.696 16.918H24Zm-10.6082 0L0 20.459h3.7442l1.3693-3.5527h7.0052l1.3693 3.5528h3.7442L10.5363 3.5409Zm-.3712 10.2232 2.2914-5.9456 2.2914 5.9456Z",
        ),
    )

    val LogoOpenAi = KfIconSpec(
        "logo-openai",
        emptyList(),
        fills = listOf(
            "M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z",
        ),
    )

    val LogoGoogle = KfIconSpec(
        "logo-google",
        emptyList(),
        fills = listOf(
            "M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387 .307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48z",
        ),
    )

    val LogoGroq = KfIconSpec(
        "logo-groq",
        emptyList(),
        fills = listOf(
            "M12.036 2c-3.853-.035-7 3-7.036 6.781-.035 3.782 3.055 6.872 6.908 6.907h2.42v-2.566h-2.292c-2.407.028-4.38-1.866-4.408-4.23-.029-2.362 1.901-4.298 4.308-4.326h.1c2.407 0 4.358 1.915 4.365 4.278v6.305c0 2.342-1.944 4.25-4.323 4.279a4.375 4.375 0 01-3.033-1.252l-1.851 1.818A7 7 0 0012.029 22h.092c3.803-.056 6.858-3.083 6.879-6.816v-6.5C18.907 4.963 15.817 2 12.036 2z",
        ),
    )

    val LogoElevenLabs = KfIconSpec(
        "logo-elevenlabs",
        emptyList(),
        fills = listOf(
            "M5.7 3v18h3.7V3zm8.9 0v18h3.7V3z",
        ),
    )

    val Exit = KfIconSpec(
        "exit",
        listOf(
            "M9 20H5a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2h4",
            "M16 17l5 -5 -5 -5",
            "M21 12H9",
        ),
    )

    val User = KfIconSpec(
        "user",
        listOf(
            circle(12f, 8f, 3.5f),
            "M5 20c0 -3.5 3.1 -6.5 7 -6.5s7 3 7 6.5",
        ),
    )

    /** Rotação de tela: smartphone central com setas circulares ao redor. */
    val ScreenRotate = KfIconSpec(
        "screen-rotate",
        listOf(
            "M10 7h4a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-4A1.5 1.5 0 0 1 8.5 15.5v-7A1.5 1.5 0 0 1 10 7z",
            "M6.5 8.5a7 7 0 0 1 11 0",
            "M18.5 5.5v3h-3",
            "M17.5 15.5a7 7 0 0 1 -11 0",
            "M5.5 18.5v-3h3",
        ),
    )

    val all: List<KfIconSpec> = listOf(
        Mic, Adjust, ChevronUp, ChevronDown, Return, Paste, Grid, Joystick,
        RecordDot, Pause, Play, Undo, Sending, Close, Trash, Hand,
        Check, Plus, ChevronRight, ChevronLeft, Lock, LockOpen, Globe, Moon, Sun, Exit, User, ScreenRotate,
        TabKeyflow, TabModo, TabVoz, TabVisao, TabRaciocinio, TabBoca, TabNotificacao, TabTeclado, TabSobrepor,
        TabFluxos, TabComputador, TabProvedores, TabDocs, Infinity,
        LogoAnthropic, LogoOpenAi, LogoGoogle, LogoGroq, LogoElevenLabs,
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

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
    // Origem dos paths: Simple Icons (CC0 1.0) e, onde faltava, lobe-icons (MIT) — ver `app/licenses/README.md`. Marcas registradas
    // são dos donos; o desenho é só para identificar o fabricante do modelo.
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

    /** Simple Icons (CC0 1.0), nvidia.svg; marca verde #76B900. */
    val LogoNvidia = KfIconSpec(
        "logo-nvidia",
        emptyList(),
        fills = listOf(
            "M8.948 8.798v-1.43a6.7 6.7 0 0 1 .424-.018c3.922-.124 6.493 3.374 6.493 3.374s-2.774 3.851-5.75 3.851c-.398 0-.787-.062-1.158-.185v-4.346c1.528.185 1.837.857 2.747 2.385l2.04-1.714s-1.492-1.952-4-1.952a6.016 6.016 0 0 0-.796.035m0-4.735v2.138l.424-.027c5.45-.185 9.01 4.47 9.01 4.47s-4.08 4.964-8.33 4.964c-.37 0-.733-.035-1.095-.097v1.325c.3.035.61.062.91.062 3.957 0 6.82-2.023 9.593-4.408.459.371 2.34 1.263 2.73 1.652-2.633 2.208-8.772 3.984-12.253 3.984-.335 0-.653-.018-.971-.053v1.864H24V4.063zm0 10.326v1.131c-3.657-.654-4.673-4.46-4.673-4.46s1.758-1.944 4.673-2.262v1.237H8.94c-1.528-.186-2.73 1.245-2.73 1.245s.68 2.412 2.739 3.11M2.456 10.9s2.164-3.197 6.5-3.533V6.201C4.153 6.59 0 10.653 0 10.653s2.35 6.802 8.948 7.42v-1.237c-4.84-.6-6.492-5.936-6.492-5.936z",
        ),
    )

    /** Simple Icons (CC0 1.0), huggingface.svg; marca amarela #FFD21E. */
    val LogoHuggingFace = KfIconSpec(
        "logo-huggingface",
        emptyList(),
        fills = listOf(
            "M12.025 1.13c-5.77 0-10.449 4.647-10.449 10.378 0 1.112.178 2.181.503 3.185.064-.222.203-.444.416-.577a.96.96 0 0 1 .524-.15c.293 0 .584.124.84.284.278.173.48.408.71.694.226.282.458.611.684.951v-.014c.017-.324.106-.622.264-.874s.403-.487.762-.543c.3-.047.596.06.787.203s.31.313.4.467c.15.257.212.468.233.542.01.026.653 1.552 1.657 2.54.616.605 1.01 1.223 1.082 1.912.055.537-.096 1.059-.38 1.572.637.121 1.294.187 1.967.187.657 0 1.298-.063 1.921-.178-.287-.517-.44-1.041-.384-1.581.07-.69.465-1.307 1.081-1.913 1.004-.987 1.647-2.513 1.657-2.539.021-.074.083-.285.233-.542.09-.154.208-.323.4-.467a1.08 1.08 0 0 1 .787-.203c.359.056.604.29.762.543s.247.55.265.874v.015c.225-.34.457-.67.683-.952.23-.286.432-.52.71-.694.257-.16.547-.284.84-.285a.97.97 0 0 1 .524.151c.228.143.373.388.43.625l.006.04a10.3 10.3 0 0 0 .534-3.273c0-5.731-4.678-10.378-10.449-10.378M8.327 6.583a1.5 1.5 0 0 1 .713.174 1.487 1.487 0 0 1 .617 2.013c-.183.343-.762-.214-1.102-.094-.38.134-.532.914-.917.71a1.487 1.487 0 0 1 .69-2.803m7.486 0a1.487 1.487 0 0 1 .689 2.803c-.385.204-.536-.576-.916-.71-.34-.12-.92.437-1.103.094a1.487 1.487 0 0 1 .617-2.013 1.5 1.5 0 0 1 .713-.174m-10.68 1.55a.96.96 0 1 1 0 1.921.96.96 0 0 1 0-1.92m13.838 0a.96.96 0 1 1 0 1.92.96.96 0 0 1 0-1.92M8.489 11.458c.588.01 1.965 1.157 3.572 1.164 1.607-.007 2.984-1.155 3.572-1.164.196-.003.305.12.305.454 0 .886-.424 2.328-1.563 3.202-.22-.756-1.396-1.366-1.63-1.32q-.011.001-.02.006l-.044.026-.01.008-.03.024q-.018.017-.035.036l-.032.04a1 1 0 0 0-.058.09l-.014.025q-.049.088-.11.19a1 1 0 0 1-.083.116 1.2 1.2 0 0 1-.173.18q-.035.029-.075.058a1.3 1.3 0 0 1-.251-.243 1 1 0 0 1-.076-.107c-.124-.193-.177-.363-.337-.444-.034-.016-.104-.008-.2.022q-.094.03-.216.087-.06.028-.125.063l-.13.074q-.067.04-.136.086a3 3 0 0 0-.135.096 3 3 0 0 0-.26.219 2 2 0 0 0-.12.121 2 2 0 0 0-.106.128l-.002.002a2 2 0 0 0-.09.132l-.001.001a1.2 1.2 0 0 0-.105.212q-.013.036-.024.073c-1.139-.875-1.563-2.317-1.563-3.203 0-.334.109-.457.305-.454m.836 10.354c.824-1.19.766-2.082-.365-3.194-1.13-1.112-1.789-2.738-1.789-2.738s-.246-.945-.806-.858-.97 1.499.202 2.362c1.173.864-.233 1.45-.685.64-.45-.812-1.683-2.896-2.322-3.295s-1.089-.175-.938.647 2.822 2.813 2.562 3.244-1.176-.506-1.176-.506-2.866-2.567-3.49-1.898.473 1.23 2.037 2.16c1.564.932 1.686 1.178 1.464 1.53s-3.675-2.511-4-1.297c-.323 1.214 3.524 1.567 3.287 2.405-.238.839-2.71-1.587-3.216-.642-.506.946 3.49 2.056 3.522 2.064 1.29.33 4.568 1.028 5.713-.624m5.349 0c-.824-1.19-.766-2.082.365-3.194 1.13-1.112 1.789-2.738 1.789-2.738s.246-.945.806-.858.97 1.499-.202 2.362c-1.173.864.233 1.45.685.64.451-.812 1.683-2.896 2.322-3.295s1.089-.175.938.647-2.822 2.813-2.562 3.244 1.176-.506 1.176-.506 2.866-2.567 3.49-1.898-.473 1.23-2.037 2.16c-1.564.932-1.686 1.178-1.464 1.53s3.675-2.511 4-1.297c.323 1.214-3.524 1.567-3.287 2.405.238.839 2.71-1.587 3.216-.642.506.946-3.49 2.056-3.522 2.064-1.29.33-4.568 1.028-5.713-.624",
        ),
    )

    /** Simple Icons (CC0 1.0), deepseek.svg; marca azul #5786FE. */
    val LogoDeepSeek = KfIconSpec(
        "logo-deepseek",
        emptyList(),
        fills = listOf(
            "M23.748 4.651c-.254-.124-.364.113-.512.233-.051.04-.094.09-.137.137-.372.397-.806.657-1.373.626-.829-.046-1.537.214-2.163.848-.133-.782-.575-1.248-1.247-1.548-.352-.155-.708-.311-.955-.65-.172-.24-.219-.509-.305-.774-.055-.16-.11-.323-.293-.35-.2-.031-.278.136-.356.276-.313.572-.434 1.202-.422 1.84.027 1.436.633 2.58 1.838 3.393.137.094.172.187.129.323-.082.28-.18.553-.266.833-.055.179-.137.218-.328.14a5.5 5.5 0 0 1-1.737-1.179c-.857-.828-1.631-1.743-2.597-2.46a12 12 0 0 0-.689-.47c-.985-.957.13-1.743.387-1.836.27-.098.094-.433-.778-.428-.872.003-1.67.295-2.687.685a3 3 0 0 1-.465.136 9.6 9.6 0 0 0-2.883-.101c-1.885.21-3.39 1.1-4.497 2.622C.082 8.776-.231 10.854.152 13.02c.403 2.284 1.568 4.175 3.36 5.653 1.857 1.533 3.997 2.284 6.438 2.14 1.482-.085 3.132-.284 4.994-1.86.47.234.962.328 1.78.398.629.058 1.235-.031 1.705-.129.735-.155.684-.836.418-.961-2.155-1.004-1.682-.595-2.112-.926 1.095-1.295 2.768-3.598 3.284-6.733.05-.346.115-.834.108-1.114-.004-.171.035-.238.23-.257a4.2 4.2 0 0 0 1.545-.475c1.397-.763 1.96-2.016 2.093-3.517.02-.23-.004-.467-.247-.588M11.58 18.168c-2.088-1.642-3.101-2.183-3.52-2.16-.39.024-.32.472-.234.763.09.288.207.487.371.74.114.167.192.416-.113.603-.673.416-1.842-.14-1.897-.168-1.361-.801-2.5-1.86-3.301-3.306-.775-1.393-1.225-2.888-1.299-4.482-.02-.385.094-.522.477-.592a4.7 4.7 0 0 1 1.53-.038c2.131.311 3.946 1.264 5.467 2.774.868.86 1.525 1.887 2.202 2.89.72 1.066 1.494 2.082 2.48 2.915.348.291.626.513.892.677-.802.09-2.14.109-3.055-.615zm1.001-6.44a.306.306 0 0 1 .415-.287.3.3 0 0 1 .113.074.3.3 0 0 1 .086.214c0 .17-.136.307-.308.307a.303.303 0 0 1-.306-.307m3.11 1.596c-.2.081-.4.151-.591.16a1.25 1.25 0 0 1-.798-.254c-.274-.23-.47-.358-.551-.758a1.7 1.7 0 0 1 .015-.588c.07-.327-.007-.537-.238-.727-.188-.156-.426-.199-.689-.199a.6.6 0 0 1-.254-.078.253.253 0 0 1-.114-.358 1 1 0 0 1 .192-.21c.356-.202.767-.136 1.146.016.352.144.618.408 1.001.782.392.451.462.576.685.915.176.264.336.536.446.848.066.194-.02.353-.25.45",
        ),
    )

    /** lobe-icons (MIT, LobeHub), packages/static-svg/icons/qwen-color.svg (a Simple Icons não tem a Qwen); marca azul #082DFF. */
    val LogoQwen = KfIconSpec(
        "logo-qwen",
        emptyList(),
        fills = listOf(
            "M19.713 2.825h-5.907c0-.251-.066-.503-.196-.728L12.819.728A1.46 1.46 0 0 0 11.559 0h-1.58c-.52 0-1 .275-1.26.728l-2.42 4.19a1.45 1.45 0 0 0 0 1.453l.791 1.37c.26.448.74.727 1.26.727h11.36c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451l-.79-1.37a1.46 1.46 0 0 0-1.26-.728zM.195 9.905l2.951 5.115a1.46 1.46 0 0 0-.531.531l-.791 1.37a1.45 1.45 0 0 0 0 1.452l.79 1.37c.26.448.74.727 1.26.727h4.84c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451L5.086 7.083a1.46 1.46 0 0 0-1.26-.728h-1.58c-.52 0-1 .276-1.26.728l-.79 1.37a1.45 1.45 0 0 0 0 1.452m15.888 13.367 2.951-5.115c.217.125.469.196.728.196h1.582c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451l-2.42-4.191a1.46 1.46 0 0 0-1.259-.728h-1.582c-.52 0-1 .276-1.259.728l-5.678 9.838a1.45 1.45 0 0 0 0 1.452l.79 1.37c.26.448.74.727 1.26.727h1.582c.52 0 1-.275 1.26-.728z",
        ),
    )

    /** LobeHub lobe-icons (MIT), src/IBM/components/Mono.tsx, commit 8b7bbc433d38437fa2b0cca58ae896917162b812. */
    val LogoIbm = KfIconSpec(
        "logo-ibm", emptyList(), fills = listOf(
            "M24 16.333V17h-3.158v-.667H24zm-7.579 0V17h-3.158v-.667h3.158zm2.464 0L18.63 17l-.25-.667h.504zm-7.075 0a2.528 2.528 0 01-1.717.667h-5.04v-.667h6.757zm-7.389 0V17H0v-.667h4.421zm12-1.333v.667h-3.158V15h3.158zm2.958 0l-.246.667h-1L17.885 15h1.494zm-6.937 0c-.057.237-.148.46-.265.667H5.053V15h7.39zm-8.02 0v.667H0V15h4.421zM24 15v.667h-3.158V15H24zm-1.263-1.333v.666h-1.895v-.666h1.895zm-6.316 0v.666h-1.895v-.666h1.895zm3.453 0l-.248.666h-1.989l-.25-.666h2.487zm-7.52 0c.056.212.088.435.088.666h-2.337v-.666h2.249zm-4.143 0v.666H6.316v-.666H8.21zm-5.053 0v.666H1.263v-.666h1.895zm19.579-1.334V13h-1.895v-.667h1.895zm-6.316 0V13h-1.895v-.667h1.895zm3.948 0l-.247.667h-2.987l-.245-.667h3.48zm-8.792 0c.218.188.405.414.55.667H6.315v-.667h5.26zm-8.42 0V13H1.264v-.667h1.895zM18.456 11l.177.539.176-.539h3.929v.667h-1.895v-.613l-.215.613H16.63l-.209-.613v.613h-1.895V11h3.929zM3.158 11v.667H1.263V11h1.895zm8.968 0a2.555 2.555 0 01-.55.667h-5.26V11h5.81zm10.61-1.333v.666h-3.709l.224-.666h3.486zm-4.722 0l.224.666h-3.712v-.666h3.488zm-5.572 0c0 .23-.032.454-.088.666h-2.249v-.666h2.337zm-4.231 0v.666H6.316v-.666H8.21zm-5.053 0v.666H1.263v-.666h1.895zm14.419-1.334l.22.667h-4.534v-.667h4.314zm6.423 0V9h-4.536l.229-.667H24zm-11.823 0c.117.206.208.43.265.667h-7.39v-.667h7.125zm-7.756 0V9H0v-.667h4.421zM17.133 7l.224.667h-4.094V7h3.87zM24 7v.667h-4.089L20.13 7H24zM10.093 7c.662 0 1.264.253 1.717.667H5.053V7h5.04zM4.42 7v.667H0V7h4.421z",
        ),
    )

    /** Simple Icons (CC0 1.0), paddlepaddle.svg; marca azul #0062B0. */
    val LogoPaddlePaddle = KfIconSpec(
        "logo-paddlepaddle",
        emptyList(),
        fills = listOf(
            "M12.17185 4.8315c-.6339 0-1.15033.51448-1.15033 1.14833s.51643 1.15033 1.15033 1.15033 1.14833-.51643 1.14833-1.15033-.51448-1.14833-1.14833-1.14833zm9.9916 0c-.6339 0-1.15033.51448-1.15033 1.14833s.51643 1.15033 1.15033 1.15033c.6339 0 1.14833-.51643 1.14833-1.15033S22.7973 4.8315 22.16345 4.8315zM6.67238 8.00711c-.0331 0-.06258.01885-.07617.04883l-1.95298 4.29274H.75286c-.05136 0-.09805.02932-.11913.07616l-.62691 1.39832c-.02155.04809.01363.10351.0664.10351h3.85327l-2.32796 5.1208c-.02545.0559.01465.12108.07617.12108h1.6561c.0331 0 .06438-.02064.07812-.05078l4.9977-10.99134c.02529-.0559-.01481-.11913-.07617-.11913zm3.4783 0c-.02484 0-.0483.01431-.0586.0371l-.58004 1.28513c-.01905.04216.01237.08984.05859.08984h1.02334c.21703-.00312.37745.01142.55466.03906.82499.12818 1.33602.73302 1.32412 1.5351-.01124.75786-.64035 1.36123-1.39832 1.36123H7.40865c-.06245 0-.11876.03691-.14452.09374l-.60935 1.33972c-.0295.0648.0188.13671.08984.13671h4.28495c1.77679 0 3.1942-1.5331 2.99592-3.2986-.1616-1.43981-1.34012-2.56875-2.81033-2.61894-.01327-.00047-.02641.0011-.03906.0039a.06152.06152 0 0 0-.02148-.0039zm6.4762 0c-.0331 0-.06438.0187-.07812.04883l-4.9977 10.99134c-.0253.0559.01676.12108.07812.12108h1.6561c.03294 0 .06259-.0208.07617-.05078l4.9997-10.99134c.02545-.0559-.01661-.11913-.07813-.11913zm3.4783 0c-.02498 0-.04633.01431-.05664.0371l-.58005 1.28513c-.01889.04216.01058.08984.05664.08984h1.02334c.21717-.00312.37744.01142.55465.03906.825.12818 1.33782.73302 1.32612 1.5351-.0114.75786-.64034 1.36123-1.39831 1.36123h-3.66778c-.06246 0-.11876.03691-.14452.09374l-.60935 1.33972c-.02934.0648.01864.13671.08984.13671h4.28494c1.7766 0 3.1961-1.5331 2.99782-3.2986-.16159-1.43981-1.34222-2.56875-2.81233-2.61894-.01343-.00047-.02642.0011-.03906.0039a.0611.0611 0 0 0-.02148-.0039z"
        ),
    )

    /** Redesenho vetorial do avatar da Nanbeige no Hugging Face; referência e atribuição em `app/licenses/README.md`.
     * Barra curva, faixa larga e cinco blocos no grid 24×24; marca verde-petróleo #0B4A45. */
    val LogoNanbeige = KfIconSpec(
        "logo-nanbeige",
        emptyList(),
        fills = listOf(
            "M3.6 6.24Q11.22 6.78 18.84 6.24V9Q11.22 9.48 3.6 9zM0 10.56Q12 11.04 24 10.56v2.76Q12 12.84 0 13.32zM1.08 14.64h2.76v4.2H1.08zM5.4 14.64h2.88v4.2H5.4zM9.84 14.64h2.76v4.2H9.84zM14.16 14.64h2.88v4.2h-2.88zM18.6 14.64h2.88v4.2H18.6z",
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
        LogoNvidia, LogoHuggingFace, LogoDeepSeek, LogoQwen, LogoPaddlePaddle, LogoIbm, LogoNanbeige,
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

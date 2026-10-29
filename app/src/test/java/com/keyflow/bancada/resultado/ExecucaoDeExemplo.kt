package com.keyflow.bancada.resultado

/** Uma execução rica, com um pouco de tudo, para os testes de serialização, histórico e comparação. */
internal fun execucaoDeExemplo(
    id: String = "e-0001",
    iniciada: String = "2026-10-28T14:03:11Z",
    estado: EstadoExecucao = EstadoExecucao.COMPLETA,
    modelo: String = "qwen3-0.6b-litertlm",
    backend: String = "CPU",
    corpus: Int = 1,
    perfil: String = "completo",
    nota: Double = 0.81,
    aplicadas: Map<String, String> = mapOf("temperature" to "0.0", "seed" to "42"),
    pensamento: Boolean = false,
    saida: String = "Oi, pessoal, tudo bem?",
    motivo: MotivoIncompleta? = null,
    teste: String = "pontuacao",
    versaoTeste: Int = 1,
) = Execucao(
    id = id,
    iniciada = iniciada,
    estado = estado,
    motivo = motivo,
    aparelho = Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33),
    app = AppInfo("1.0.0", 1, "debug", "e5aa0e7"),
    motor = MotorInfo("litert-lm", "0.16.1"),
    corpus = corpus,
    perfil = perfil,
    modelo = ModeloInfo(modelo, "local", "c5f39b41", backend, 4, pensamento),
    opcoes = OpcoesRegistradas(aplicadas, setOf("topK")),
    cargas = Cargas(friaMs = 2310, quenteMs = 640, ramPicoMb = 980, memDisponivelMb = 1210),
    testes = listOf(
        ResultadoTeste(
            teste = teste, versaoTeste = versaoTeste, nota = nota, n = 2, invalidos = 1, naoSeAplica = 0, naoDeterministico = true,
            primeiroTrechoMsP50 = 420, totalMsP50 = 3100, tokensPorSegundoMedia = 11.8,
            casos = listOf(
                ResultadoCaso(
                    id = "pt-01", estado = EstadoCaso.OK, nota = nota, criterios = mapOf("preservacao" to 1.0, "pontuacao" to 0.8),
                    marcas = setOf("PREFIXO_DE_CONVERSA"), saida = saida, primeiroTrechoMs = 410, totalMs = 2900, tokensEntrada = 41,
                    tokensSaida = 33, tokensPorSegundo = 11.8, tokensEstimados = true, termico = 0, bateriaPct = 78, notasRepeticoes = listOf(0.7, 0.81, 0.9),
                ),
                ResultadoCaso(id = "pt-02", estado = EstadoCaso.FALHA, causa = "OOM", motivo = "memória", nota = 0.0),
                ResultadoCaso(id = "pt-03", estado = EstadoCaso.INVALIDO, causa = "REDE", motivo = "sem conexão"),
            ),
        ),
    ),
)

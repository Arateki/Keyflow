package com.keyflow.orbe

import com.keyflow.core.ButtonState
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedorDaOrbeDoTecladoTest {

    @Test
    fun `1 toque espera a janela e 2 toques resolvem em 2 uma unica vez`() {
        var agoraMs = 1_000L
        val rajada = RajadaDeToques(relogio = { agoraMs }, janelaMs = { 250L })

        // 1 toque: espera a janela de 250 ms e resolve uma única vez.
        assertEquals(1, rajada.registrarToque())
        agoraMs = 1_249L
        assertNull(rajada.assentarSePronta())
        agoraMs = 1_250L
        assertEquals(1, rajada.assentarSePronta())
        assertNull(rajada.assentarSePronta())

        // 2 toques: o segundo reinicia a janela e resolve em 2.
        agoraMs = 2_000L
        assertEquals(1, rajada.registrarToque())
        agoraMs = 2_150L
        assertNull(rajada.assentarSePronta())
        assertEquals(2, rajada.registrarToque())
        agoraMs = 2_399L
        assertNull(rajada.assentarSePronta())
        agoraMs = 2_400L
        assertEquals(2, rajada.assentarSePronta())
        assertNull(rajada.assentarSePronta())
    }

    @Test
    fun `classificador usa limiares da KBO-13 para segurar, arrasto e sentido lateral para o centro`() {
        var agoraMs = 0L
        val classificador = ClassificadorDeGesto(relogio = { agoraMs }, janelaRajadaMs = { 250L })
        assertEquals(400L, classificador.limiarSegurarMs())
        assertEquals(44.8f, classificador.distanciaAtivacaoArrastoPx(64f), 1e-4f)

        // Segurar por 400 ms emite SEGURAR em andamento, cancela rajada pendente e não duplica no soltar.
        classificador.rajada.registrarToque()
        classificador.aoPressionar(x = 32f, y = 50f, diametroDaOrbePx = 64f, centroDaOrbeX = 32f, centroDaTelaX = 180f)
        agoraMs = 399L
        assertNull(classificador.avaliarSegurar())
        agoraMs = 400L
        assertEquals(Interacao.SEGURAR, classificador.avaliarSegurar())
        assertEquals(0, classificador.rajada.contagemPendente)
        assertNull(classificador.aoSoltar())

        // Arrasto: <= 10 px não entra em arrasto; > 10 px entra, mas exige 0,7 * diâmetro (44,8 px) para ativar zona.
        agoraMs = 1_000L
        classificador.aoPressionar(x = 32f, y = 50f, diametroDaOrbePx = 64f, centroDaOrbeX = 32f, centroDaTelaX = 180f)
        val movCurto = classificador.aoMover(x = 40f, y = 42f)
        assertEquals(false, movCurto.emArrasto)
        val movSemZona = classificador.aoMover(x = 32f, y = 10f) // dy = -40 (< 44,8)
        assertEquals(true, movSemZona.emArrasto)
        assertNull(classificador.aoSoltar())

        // Lateral: orbe à esquerda (32) com centro da tela em 180 → +dx ativa ARRASTAR_LADO; à direita (300) → -dx ativa.
        classificador.aoPressionar(x = 32f, y = 50f, diametroDaOrbePx = 64f, centroDaOrbeX = 32f, centroDaTelaX = 180f)
        classificador.aoMover(x = 32f + 46f, y = 50f)
        assertEquals(Interacao.ARRASTAR_LADO, classificador.aoSoltar())

        classificador.aoPressionar(x = 300f, y = 50f, diametroDaOrbePx = 64f, centroDaOrbeX = 300f, centroDaTelaX = 180f)
        classificador.aoMover(x = 300f - 46f, y = 50f)
        assertEquals(Interacao.ARRASTAR_LADO, classificador.aoSoltar())
    }

    @Test
    fun `4 toques com CANCEL nao viram comando e com NEAREST_DOWN caem no 3 se ele tiver acao`() {
        val config = ConfigOrbeTeclado(copiarDaOrbePrincipal = false)

        val cancelado = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 4,
            config = config,
            settings = Settings(shortcutOverflow = ShortcutOverflow.CANCEL),
        )
        assertEquals(ComandoDaOrbeDoTeclado.Nenhum, cancelado)

        val caiNo3 = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 4,
            config = config,
            settings = Settings(shortcutOverflow = ShortcutOverflow.NEAREST_DOWN),
        )
        assertEquals(ComandoDaOrbeDoTeclado.TrocarTeclado, caiNo3)

        val semAcaoNo3 = config.copy(toque3 = AcaoDaOrbeDoTeclado.Nenhuma)
        val caiNo2 = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 4,
            config = semAcaoNo3,
            settings = Settings(shortcutOverflow = ShortcutOverflow.NEAREST_DOWN),
            tecladoVisivel = true,
        )
        assertEquals(ComandoDaOrbeDoTeclado.RetrairExpandirTeclado, caiNo2)
    }

    @Test
    fun `fabrica grava no 1 toque e envia no segundo e catalogo contem as sete acoes da KBO-16`() {
        val fabrica = ConfigOrbeTeclado(copiarDaOrbePrincipal = false)

        assertEquals(
            listOf(
                AcaoDaOrbeDoTeclado.GravarEnviar,
                AcaoDaOrbeDoTeclado.Nenhuma,
                AcaoDaOrbeDoTeclado.RetrairExpandir,
                AcaoDaOrbeDoTeclado.TrocarTeclado,
                AcaoDaOrbeDoTeclado.LerTela,
                AcaoDaOrbeDoTeclado.PausarRetomar,
                AcaoDaOrbeDoTeclado.CancelarDesfazer,
            ),
            ResolvedorDaOrbeDoTeclado.CATALOGO_DE_TOQUES,
        )

        val primeiroToque = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 1,
            config = fabrica,
            estado = ButtonState.IDLE_READY,
        )
        assertEquals(ComandoDaOrbeDoTeclado.IniciarGravacao, primeiroToque)

        val segundoToque = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 1,
            config = fabrica,
            estado = ButtonState.RECORDING,
        )
        assertEquals(ComandoDaOrbeDoTeclado.EnviarGravacao, segundoToque)

        assertEquals(
            ComandoDaOrbeDoTeclado.ConcluirCancelamento,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, fabrica, estado = ButtonState.CANCELLING),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.ColarTextoPendente,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, fabrica, estado = ButtonState.PENDING_PASTE),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.DispensarErro,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, fabrica, estado = ButtonState.ERROR),
        )
    }

    @Test
    fun `config com zero gravar-enviar e aceita no Alternar e no segurar-para-falar proprio e copiado`() {
        val semGravarAlternar = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            modo = ModoDaOrbe.ALTERNAR,
            toque1 = AcaoDaOrbeDoTeclado.LerTela,
            toque2 = AcaoDaOrbeDoTeclado.RetrairExpandir,
            toque3 = AcaoDaOrbeDoTeclado.TrocarTeclado,
        )
        val edicaoAlternar = ResolvedorDaOrbeDoTeclado.aplicarEdicao(ConfigOrbeTeclado(), semGravarAlternar)
        assertEquals(semGravarAlternar, edicaoAlternar.config)
        assertEquals(
            ComandoDaOrbeDoTeclado.IniciarVisao,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, semGravarAlternar, estado = ButtonState.IDLE_READY),
        )

        val semGravarPttProprio = ConfigOrbeTeclado.padraoParaModo(ModoDaOrbe.SEGURAR_PARA_FALAR).copy(
            copiarDaOrbePrincipal = false,
        )
        assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, semGravarPttProprio.toque1)
        val edicaoPtt = ResolvedorDaOrbeDoTeclado.aplicarEdicao(semGravarAlternar, semGravarPttProprio)
        assertEquals(semGravarPttProprio, edicaoPtt.config)
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, semGravarPttProprio, estado = ButtonState.IDLE_READY),
        )

        val pttCopiadoSettings = Settings(
            overlayRecordingMode = RecordingMode.PUSH_TO_TALK,
            shortcut1Tap = OrbShortcutAction.NONE,
            shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD,
            shortcut3Tap = OrbShortcutAction.SWITCH_IME,
        )
        val efetivaPttCopiado = ResolvedorDaOrbeDoTeclado.efetiva(
            ConfigOrbeTeclado(copiarDaOrbePrincipal = true),
            pttCopiadoSettings,
        )
        assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, efetivaPttCopiado.toque1)
    }

    @Test
    fun `substituir um toque, gesto ou botao que ja tinha acao avisa o que saiu e nao recoloca gravar`() {
        val inicial = ConfigOrbeTeclado(copiarDaOrbePrincipal = false)

        // Substitui o 1 toque (GravarEnviar) por LerTela: avisa que GravarEnviar saiu e não recoloca GravarEnviar em 2 ou 3.
        val resultado = ResolvedorDaOrbeDoTeclado.substituirToque(
            config = inicial,
            toques = 1,
            novaAcao = AcaoDaOrbeDoTeclado.LerTela,
        )
        assertEquals(AcaoDaOrbeDoTeclado.LerTela, resultado.config.toque1)
        assertEquals(AcaoDaOrbeDoTeclado.RetrairExpandir, resultado.config.toque2)
        assertEquals(AcaoDaOrbeDoTeclado.TrocarTeclado, resultado.config.toque3)
        assertEquals(
            listOf(
                AvisoDeSubstituicao(
                    alvo = AlvoDeSubstituicao.ToqueDaOrbe(1),
                    acaoSubstituida = AcaoDaOrbeDoTeclado.GravarEnviar,
                    acaoNova = AcaoDaOrbeDoTeclado.LerTela,
                ),
            ),
            resultado.avisos,
        )

        // Atribuir em toque que estava em Nenhuma não gera aviso.
        val semAcaoNo1 = resultado.config.copy(toque1 = AcaoDaOrbeDoTeclado.Nenhuma)
        val semAviso = ResolvedorDaOrbeDoTeclado.substituirToque(
            config = semAcaoNo1,
            toques = 1,
            novaAcao = AcaoDaOrbeDoTeclado.PausarRetomar,
        )
        assertTrue(semAviso.avisos.isEmpty())

        // Substituição em gesto da orbe e botão do teclado também avisa o que saiu.
        val avisoGesto = ResolvedorDaOrbeDoTeclado.avaliarSubstituicao(
            alvo = AlvoDeSubstituicao.GestoDaOrbe(Interacao.TOQUE_2),
            acaoAnterior = AcaoDaOrbeDoTeclado.RetrairExpandir,
            acaoNova = AcaoDaOrbeDoTeclado.RodarFluxo("f1"),
            vazia = { it == AcaoDaOrbeDoTeclado.Nenhuma },
        )
        assertEquals(
            AvisoDeSubstituicao(
                alvo = AlvoDeSubstituicao.GestoDaOrbe(Interacao.TOQUE_2),
                acaoSubstituida = AcaoDaOrbeDoTeclado.RetrairExpandir,
                acaoNova = AcaoDaOrbeDoTeclado.RodarFluxo("f1"),
            ),
            avisoGesto,
        )

        val avisoBotao = ResolvedorDaOrbeDoTeclado.avaliarSubstituicao(
            alvo = AlvoDeSubstituicao.BotaoDoTeclado("barra-topo-2"),
            acaoAnterior = "clipboard",
            acaoNova = "flow:resumo",
            vazia = { it.isBlank() },
        )
        assertEquals(
            AvisoDeSubstituicao(
                alvo = AlvoDeSubstituicao.BotaoDoTeclado("barra-topo-2"),
                acaoSubstituida = "clipboard",
                acaoNova = "flow:resumo",
            ),
            avisoBotao,
        )
    }

    @Test
    fun `copiar ligado no Alternar ignora shortcut1Tap no 1 toque e usa shortcut2Tap`() {
        val settings = Settings(
            orbSkin = "glitch",
            overlayRecordingMode = RecordingMode.TOGGLE,
            shortcut1Tap = OrbShortcutAction.VISION_MODE,
            shortcut2Tap = OrbShortcutAction.SWITCH_IME,
            shortcut3Tap = OrbShortcutAction.TOGGLE_KEYBOARD,
        )
        val propria = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = true,
            skin = "atelie",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.Nenhuma,
            toque2 = AcaoDaOrbeDoTeclado.CancelarDesfazer,
            toque3 = AcaoDaOrbeDoTeclado.PausarRetomar,
        )

        val efetiva = ResolvedorDaOrbeDoTeclado.efetiva(propria, settings)
        assertEquals("glitch", efetiva.skin)
        assertEquals(ModoDaOrbe.ALTERNAR, efetiva.modo)
        assertEquals(AcaoDaOrbeDoTeclado.GravarEnviar, efetiva.toque1)
        assertEquals(AcaoDaOrbeDoTeclado.TrocarTeclado, efetiva.toque2)
        assertEquals(AcaoDaOrbeDoTeclado.RetrairExpandir, efetiva.toque3)

        assertEquals(
            ComandoDaOrbeDoTeclado.IniciarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, propria, settings, estado = ButtonState.IDLE_READY),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.TrocarTeclado,
            ResolvedorDaOrbeDoTeclado.resolverToques(2, propria, settings, estado = ButtonState.IDLE_READY),
        )
    }

    @Test
    fun `copiar segurar-para-falar mapeia o 1 toque em shortcut1Tap e o segurar grava`() {
        val settingsSemToque1 = Settings(
            orbSkin = "rose_gold",
            overlayRecordingMode = RecordingMode.PUSH_TO_TALK,
            shortcut1Tap = OrbShortcutAction.NONE,
            shortcut2Tap = OrbShortcutAction.VISION_MODE,
            shortcut3Tap = OrbShortcutAction.SWITCH_IME,
        )
        val configCopiando = ConfigOrbeTeclado(copiarDaOrbePrincipal = true)

        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, configCopiando, settingsSemToque1, estado = ButtonState.IDLE_READY),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.IniciarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(
                Interacao.SEGURAR,
                configCopiando,
                settingsSemToque1,
                estado = ButtonState.IDLE_READY,
            ),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.AbrirJanelaEnvioPtt,
            ResolvedorDaOrbeDoTeclado.resolverSoltarSegurar(
                configCopiando,
                settingsSemToque1,
                estado = ButtonState.RECORDING,
            ),
        )

        val settingsComToque1 = settingsSemToque1.copy(shortcut1Tap = OrbShortcutAction.VISION_MODE)
        assertEquals(
            ComandoDaOrbeDoTeclado.IniciarVisao,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, configCopiando, settingsComToque1, estado = ButtonState.IDLE_READY),
        )
    }

    @Test
    fun `copiar nao recebe a config propria como escrita e desligar restaura a config propria`() {
        val propriaGuardada = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            skin = "pack:retro",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.PausarRetomar,
            toque2 = AcaoDaOrbeDoTeclado.CancelarDesfazer,
            toque3 = AcaoDaOrbeDoTeclado.LerTela,
        )
        val settings = Settings(orbSkin = "aero", overlayRecordingMode = RecordingMode.TOGGLE)

        val comCopiarLigado = ResolvedorDaOrbeDoTeclado.alternarCopiar(propriaGuardada, copiar = true)
        assertEquals(propriaGuardada.copy(copiarDaOrbePrincipal = true), comCopiarLigado)
        assertEquals("aero", ResolvedorDaOrbeDoTeclado.efetiva(comCopiarLigado, settings).skin)

        val restaurada = ResolvedorDaOrbeDoTeclado.alternarCopiar(comCopiarLigado, copiar = false)
        assertEquals(propriaGuardada, restaurada)
        assertEquals(propriaGuardada, ResolvedorDaOrbeDoTeclado.efetiva(restaurada, settings))
    }

    @Test
    fun `acao desconhecida permanece e dispara como Nenhuma sem reescrever o 1 toque para gravar`() {
        val config = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            toque1 = AcaoDaOrbeDoTeclado.Desconhecida("acao_futura"),
            toque2 = AcaoDaOrbeDoTeclado.RetrairExpandir,
            toque3 = AcaoDaOrbeDoTeclado.TrocarTeclado,
        )

        val efetiva = ResolvedorDaOrbeDoTeclado.efetiva(config, Settings())
        assertEquals(AcaoDaOrbeDoTeclado.Desconhecida("acao_futura"), efetiva.toque1)
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, config, estado = ButtonState.IDLE_READY),
        )
    }

    @Test
    fun `Visao ativa so aceita gravar-enviar como encerrar visao e ignora o resto`() {
        val config = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            toque1 = AcaoDaOrbeDoTeclado.LerTela,
            toque2 = AcaoDaOrbeDoTeclado.GravarEnviar,
            toque3 = AcaoDaOrbeDoTeclado.TrocarTeclado,
        )

        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, config, visaoAtiva = true),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.EncerrarVisao,
            ResolvedorDaOrbeDoTeclado.resolverToques(2, config, visaoAtiva = true),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(3, config, visaoAtiva = true),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.SEGURAR, config, visaoAtiva = true),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(
                Interacao.ARRASTAR_LADO,
                config,
                estado = ButtonState.RECORDING,
                visaoAtiva = true,
            ),
        )
    }

    @Test
    fun `1 toque nao chama o gancho de fluxo, gancho nulo deixa a acao correr e flow guardado vira Nenhuma`() {
        val chamadas = mutableListOf<Pair<LugarDaOrbe, Int>>()
        val ganchoNuloRastreado: GanchoDeFluxo = { lugar, toques ->
            chamadas += lugar to toques
            null
        }

        val configPropria = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            toque1 = AcaoDaOrbeDoTeclado.GravarEnviar,
            toque2 = AcaoDaOrbeDoTeclado.RetrairExpandir,
            toque3 = AcaoDaOrbeDoTeclado.RodarFluxo("meu-fluxo"),
        )

        // 1 toque, segurar e arrastar nunca chamam o gancho de fluxo.
        assertEquals(
            ComandoDaOrbeDoTeclado.IniciarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverToques(1, configPropria, consultarFluxo = ganchoNuloRastreado),
        )
        ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.SEGURAR, configPropria, consultarFluxo = ganchoNuloRastreado)
        ResolvedorDaOrbeDoTeclado.resolverInteracao(
            Interacao.ARRASTAR_CIMA,
            configPropria,
            estado = ButtonState.RECORDING,
            consultarFluxo = ganchoNuloRastreado,
        )
        assertTrue(chamadas.isEmpty())

        // 2 toques com gancho nulo consulta TECLADO (copiar desligado) e deixa a ação do lugar correr.
        assertEquals(
            ComandoDaOrbeDoTeclado.RetrairExpandirTeclado,
            ResolvedorDaOrbeDoTeclado.resolverToques(2, configPropria, consultarFluxo = ganchoNuloRastreado),
        )
        assertEquals(listOf(LugarDaOrbe.TECLADO to 2), chamadas)

        // 3 toques com flow: guardado e gancho nulo vira Nenhuma.
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverToques(3, configPropria, consultarFluxo = ganchoNuloRastreado),
        )
        assertEquals(listOf(LugarDaOrbe.TECLADO to 2, LugarDaOrbe.TECLADO to 3), chamadas)

        // Com copiar ligado, o lugar consultado é FLUTUANTE (KBO-31) e o gancho válido sombreia a ação do lugar.
        chamadas.clear()
        val configCopiando = configPropria.copy(copiarDaOrbePrincipal = true)
        val cmdFluxo = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 2,
            config = configCopiando,
            consultarFluxo = { lugar, toques ->
                chamadas += lugar to toques
                ResolucaoDeFluxo.Disparar("fluxo-flutuante")
            },
        )
        assertEquals(listOf(LugarDaOrbe.FLUTUANTE to 2), chamadas)
        assertEquals(ComandoDaOrbeDoTeclado.DispararFluxo("fluxo-flutuante"), cmdFluxo)

        // Fluxo inválido avisa e não roda a ação de baixo.
        val cmdInvalido = ResolvedorDaOrbeDoTeclado.resolverToques(
            contagem = 2,
            config = configPropria,
            consultarFluxo = { _, _ -> ResolucaoDeFluxo.Invalido("fluxo-incompleto") },
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.AvisarFluxoInvalido(
                "fluxo-incompleto",
                "Fluxo incompleto: abra a aba Fluxos",
            ),
            cmdInvalido,
        )
    }

    @Test
    fun `arrastar e segurar respeitam modo e estado, e durante SENDING so lateral cancela`() {
        val configAlternar = ConfigOrbeTeclado(copiarDaOrbePrincipal = false, modo = ModoDaOrbe.ALTERNAR)
        val configPtt = ConfigOrbeTeclado(copiarDaOrbePrincipal = false, modo = ModoDaOrbe.SEGURAR_PARA_FALAR)

        assertEquals(
            ComandoDaOrbeDoTeclado.EntrarModoArrasto,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.SEGURAR, configAlternar, estado = ButtonState.IDLE_READY),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.PausarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_CIMA, configAlternar, estado = ButtonState.RECORDING),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.RetomarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_CIMA, configAlternar, estado = ButtonState.PAUSED),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.AbrirApp(manterJanelaEnvioPtt = false),
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_BAIXO, configAlternar, estado = ButtonState.RECORDING),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.AbrirApp(manterJanelaEnvioPtt = true),
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_BAIXO, configPtt, estado = ButtonState.RECORDING),
        )

        // Durante SENDING, apenas ARRASTAR_LADO age (cancela).
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_CIMA, configAlternar, estado = ButtonState.SENDING),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.Nenhum,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_BAIXO, configAlternar, estado = ButtonState.SENDING),
        )
        assertEquals(
            ComandoDaOrbeDoTeclado.CancelarGravacao,
            ResolvedorDaOrbeDoTeclado.resolverInteracao(Interacao.ARRASTAR_LADO, configAlternar, estado = ButtonState.SENDING),
        )
    }
}

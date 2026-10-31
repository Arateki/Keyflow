package com.keyflow.fluxos

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.ComposeSmokeActivity
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.app.PortasPorTipo
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.execucao.ImagemHandle
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.i18n.tr
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Os três fluxos padrão de ponta a ponta no **serviço real** (`OverlayService` + `FluxoHost` + `ExecutorFluxo` + DataStore real),
 * com os motores trocados pelo único gancho que o serviço oferece (`portasDeFluxoForTest`, que troca as portas por etapa): o
 * gravador/STT, o LLM, a voz, a captura/OCR e o agente são falsos e determinísticos; o resto do caminho é o de produção. Cada espera
 * é por estado (`KeyFlowController.fluxo`) ou por contagem, nunca por `Thread.sleep`.
 */
@RunWith(AndroidJUnit4::class)
class FluxosEndToEndTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repo by lazy { SettingsRepository(context) }
    private lateinit var scenario: ActivityScenario<ComposeSmokeActivity>
    private lateinit var settings: Settings
    private lateinit var configAnterior: ConfigFluxos
    private lateinit var barrasAnteriores: com.keyflow.core.shortcuts.BarsConfig

    private val padroes = PadroesFluxo.semear()
    private val ouvir = padroes[0]
    private val lerTela = padroes[1]
    private val agente = padroes[2]
    private val chamadas = CopyOnWriteArrayList<Pair<TipoEtapa, Valor>>()
    private val avisos = CopyOnWriteArrayList<String>()
    private val liberadas = AtomicInteger()
    private var motor: suspend (Etapa, Valor) -> ResultadoEtapa<Valor> = { etapa, entrada -> respostaPadrao(etapa, entrada) }

    private fun imagem() = Valor.Imagem(object : ImagemHandle { override fun liberar() { liberadas.incrementAndGet() } })

    /** Ditar ouve "oi"; Capturar entrega uma imagem; Visão lê "texto da tela"; Perguntar e Agente respondem; Falar e Inserir terminam. */
    private fun respostaPadrao(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> = ResultadoEtapa.Ok(when (etapa) {
        is Etapa.Ditar -> Valor.Texto("oi")
        Etapa.Capturar -> imagem()
        is Etapa.Visao -> Valor.Texto("texto da tela")
        is Etapa.Perguntar -> Valor.Texto("resposta de " + (entrada as Valor.Texto).texto)
        is Etapa.Agente -> Valor.Texto("agente fez " + (entrada as Valor.Texto).texto)
        is Etapa.Falar, Etapa.Inserir -> entrada // terminam o fluxo entregando o texto que recebem
        else -> Valor.Nada
    })

    private suspend fun aguardar(motivo: String, condicao: () -> Boolean) {
        try { withTimeout(45_000) { while (!condicao()) delay(25) } }
        catch (erro: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(motivo, erro) }
    }

    @Before fun preparar() = runBlocking<Unit> {
        scenario = ActivityScenario.launch(Intent(context, ComposeSmokeActivity::class.java))
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        instrumentation.runOnMainSync { OverlayService.stop(context) }
        aguardar("serviço anterior não encerrou") { OverlayService.instanceForVisionTest == null }
        settings = repo.settingsFlow().first()
        configAnterior = repo.configFluxosOnce()
        barrasAnteriores = repo.barsConfigOnce()
        repo.saveSettings(settings.copy(overlayModeEnabled = false, overlayRecordingMode = RecordingMode.TOGGLE,
            shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME, shortcutOverflow = ShortcutOverflow.NEAREST_DOWN))
        repo.saveDisclosureConsent(DISCLOSURE_VERSION, false)
        OverlayService.portasDeFluxoForTest = {
            PortasPorTipo(TipoEtapa.entries.associateWith { object : PortaEtapa {
                override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
                    chamadas += etapa.tipo to entrada
                    return motor(etapa, entrada)
                }
            } })
        }
        OverlayService.avisoDeFluxoForTest = { mensagem, _ -> avisos += mensagem }
        OverlayService.errorBannerDurationMsForTest = 30_000
        instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
        finais.clear()
        observador.launch { KeyFlowController.fluxo.collect { if (it.fluxoIdOuNulo() != null) finais += it } }
    }

    @After fun limpar() = runBlocking<Unit> {
        observador.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        try {
            if (KeyFlowController.buttonState.value in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) {
                context.startService(OverlayService.actionIntent(context, com.keyflow.overlay.RecordingNotification.ACTION_CANCEL))
                aguardar("gravação não cancelou") { KeyFlowController.buttonState.value == ButtonState.CANCELLING }
                context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
            }
        } finally {
            instrumentation.runOnMainSync { OverlayService.stop(context) }
            aguardar("serviço não destruiu") { OverlayService.instanceForVisionTest == null }
            OverlayService.portasDeFluxoForTest = null
            OverlayService.avisoDeFluxoForTest = null
            OverlayService.errorBannerDurationMsForTest = null
            if (::settings.isInitialized) repo.saveSettings(settings)
            if (::configAnterior.isInitialized) repo.atualizarFluxosEBarras { _, _ -> configAnterior to barrasAnteriores }
            scenario.close()
        }
    }

    private suspend fun semear(fluxos: List<Fluxo>, gatilhos: List<Gatilho>, botoes: List<String> = emptyList()) =
        repo.atualizarFluxosEBarras { _, barras ->
            ConfigFluxos(fluxos, gatilhos) to barras.copy(collapsed = listOf(BarDef(botoes.map { BarButton.RunFlow(it) })))
        }

    private fun botao(id: String) = OverlayService.start(context,
        OverlayService.actionIntent(context, "com.keyflow.overlay.RUN_FLOW").putExtra("botao_id", id))

    private fun rodarAgora(id: String) = OverlayService.start(context,
        OverlayService.actionIntent(context, "com.keyflow.overlay.RUN_FLOW").putExtra("fluxo_id", id))

    /** Os estados finais publicados (o controlador volta a Ocioso depois, então só observar o valor atual perderia o fim). */
    private val finais = CopyOnWriteArrayList<EstadoExecucao>()
    private val observador = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private suspend fun terminou(id: String): EstadoExecucao {
        aguardar("o fluxo $id não terminou") { finais.any { it.fluxoIdOuNulo() == id } }
        return finais.last { it.fluxoIdOuNulo() == id }
    }

    private fun EstadoExecucao.fluxoIdOuNulo(): String? = when (this) {
        is EstadoExecucao.Concluido -> fluxoId
        is EstadoExecucao.Falhou -> fluxoId
        is EstadoExecucao.Cancelado -> fluxoId
        else -> null
    }

    private fun tipos() = chamadas.map { it.first }
    private fun entradaDe(tipo: TipoEtapa) = chamadas.first { it.first == tipo }.second

    @Test fun ouvirEResponderPeloBotaoPassaOTextoDeEtapaEmEtapa() = runBlocking<Unit> {
        semear(listOf(ouvir), listOf(Gatilho(Superficie.Barra("b-ouvir"), ouvir.id)), listOf("b-ouvir"))
        botao("b-ouvir")
        assertEquals(EstadoExecucao.Concluido(ouvir.id), terminou(ouvir.id))
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR), tipos())
        assertEquals(Valor.Texto("oi"), entradaDe(TipoEtapa.PERGUNTAR))
        assertEquals(Valor.Texto("resposta de oi"), entradaDe(TipoEtapa.FALAR))
        assertTrue(avisos.isEmpty())
    }

    @Test fun lerATelaEColarLiberaAImagemUmaVezEEntregaOTextoAoInserir() = runBlocking<Unit> {
        semear(listOf(lerTela), emptyList())
        rodarAgora(lerTela.id)
        assertEquals(EstadoExecucao.Concluido(lerTela.id), terminou(lerTela.id))
        assertEquals(listOf(TipoEtapa.CAPTURAR, TipoEtapa.VISAO, TipoEtapa.INSERIR), tipos())
        assertEquals(Valor.Texto("texto da tela"), entradaDe(TipoEtapa.INSERIR))
        assertEquals("a imagem sai da memória exatamente uma vez", 1, liberadas.get())
    }

    @Test fun pedirAoAgenteEColarRodaOAgenteComOTextoDitado() = runBlocking<Unit> {
        semear(listOf(agente), emptyList())
        rodarAgora(agente.id)
        assertEquals(EstadoExecucao.Concluido(agente.id), terminou(agente.id))
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.AGENTE, TipoEtapa.INSERIR), tipos())
        assertEquals(Valor.Texto("oi"), entradaDe(TipoEtapa.AGENTE))
        assertEquals(Valor.Texto("agente fez oi"), entradaDe(TipoEtapa.INSERIR))
    }

    @Test fun falhaNoMeioParaOFluxoAvisaELiberaAImagem() = runBlocking<Unit> {
        motor = { etapa, entrada ->
            if (etapa is Etapa.Visao) ResultadoEtapa.Falha(ErroEtapa(CausaErro.REDE, "sem rede", DestinoAba.VISAO)) else respostaPadrao(etapa, entrada)
        }
        semear(listOf(lerTela), emptyList())
        rodarAgora(lerTela.id)
        val fim = terminou(lerTela.id)
        assertTrue("terminou em $fim", fim is EstadoExecucao.Falhou && fim.etapa == TipoEtapa.VISAO)
        assertEquals(listOf(TipoEtapa.CAPTURAR, TipoEtapa.VISAO), tipos())
        aguardar("a falha não foi avisada") { avisos.isNotEmpty() }
        assertEquals(tr("O fluxo parou em “%s”: %s", tr("Visão"), "sem rede"), avisos.single())
        assertEquals(1, liberadas.get())
    }

    private suspend fun cancelarNaEtapa(fluxo: Fluxo, bloqueada: TipoEtapa) {
        val pronto = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        motor = { etapa, entrada ->
            if (etapa.tipo == bloqueada) {
                pronto.complete(Unit)
                try { CompletableDeferred<Unit>().await() } finally { cancelou.complete(Unit) }
            }
            respostaPadrao(etapa, entrada)
        }
        semear(listOf(fluxo), emptyList())
        rodarAgora(fluxo.id)
        withTimeout(15_000) { pronto.await() }
        OverlayService.cancelarFluxo()
        withTimeout(15_000) { cancelou.await() }
        assertEquals(EstadoExecucao.Cancelado(fluxo.id, fluxo.etapas.indexOfFirst { it.tipo == bloqueada }), terminou(fluxo.id))
        assertEquals("nada roda depois do cancelamento", bloqueada, tipos().last())
        assertTrue("cancelar não é erro: sem aviso", avisos.isEmpty())
    }

    @Test fun cancelarEmCadaEtapaPara() = runBlocking<Unit> {
        val todas = Fluxo("todas", "Todas", etapas = listOf(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar(), Etapa.Inserir))
        listOf(TipoEtapa.PERGUNTAR, TipoEtapa.FALAR).forEach { alvo ->
            chamadas.clear(); finais.clear(); avisos.clear()
            instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }; finais.clear()
            cancelarNaEtapa(todas, alvo)
            aguardar("o serviço não encerrou") { OverlayService.instanceForVisionTest == null }
        }
        chamadas.clear(); finais.clear(); avisos.clear()
        instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
        cancelarNaEtapa(agente, TipoEtapa.AGENTE)
        chamadas.clear(); finais.clear(); avisos.clear(); liberadas.set(0)
        instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
        cancelarNaEtapa(lerTela, TipoEtapa.VISAO)
        assertEquals("cancelar na Visão também libera a imagem", 1, liberadas.get())
    }

    @Test fun doisTouquesLigadosRodamOFluxoSemAlterarAsSettings() = runBlocking<Unit> {
        val config = semear(listOf(ouvir), listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), ouvir.id)))
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        aguardar("orbe não apareceu") { OverlayService.isRunningForUi }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        aguardar("o gatilho não chegou ao serviço") { service.configFluxosForTest == config }
        instrumentation.runOnMainSync { service.handleOrbShortcut(2) }
        assertEquals(EstadoExecucao.Concluido(ouvir.id), terminou(ouvir.id))
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR), tipos())
        assertEquals(OrbShortcutAction.TOGGLE_KEYBOARD, repo.settingsFlow().first().shortcut2Tap)
    }

    @Test fun restaurarNaoMexeNosGatilhosNemReligaAtalhos() = runBlocking<Unit> {
        val gatilhos = listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), ouvir.id), Gatilho(Superficie.Barra("b-ouvir"), ouvir.id))
        semear(listOf(ouvir.copy(etapas = listOf(Etapa.Ditar(), Etapa.Inserir)), lerTela), gatilhos, listOf("b-ouvir"))
        val depois = repo.atualizarFluxos(RestauradorFluxos::restaurar)
        assertEquals("o padrão editado volta ao original", ouvir.etapas, depois.fluxo(ouvir.id)!!.etapas)
        assertEquals("o padrão apagado volta", agente.etapas, depois.fluxos.first { it.padrao == agente.padrao }.etapas)
        assertEquals("os gatilhos não mudam", gatilhos, depois.gatilhos)
        assertEquals(gatilhos, repo.configFluxosOnce().gatilhos)
        assertTrue("restaurar não liga atalho ao fluxo recriado", depois.gatilhosDe(depois.fluxos.first { it.padrao == agente.padrao }.id).isEmpty())
    }

    @Test fun execucoesSeguidasNaoVazamValorNemEstado() = runBlocking<Unit> {
        semear(listOf(ouvir), emptyList())
        repeat(2) { vez ->
            chamadas.clear(); finais.clear()
            instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
            rodarAgora(ouvir.id)
            assertEquals("vez $vez", EstadoExecucao.Concluido(ouvir.id), terminou(ouvir.id))
            assertEquals("a execução $vez começa do zero", Valor.Nada, entradaDe(TipoEtapa.DITAR))
            aguardar("o serviço não encerrou") { OverlayService.instanceForVisionTest == null }
        }
        assertFalse(avisos.any())
    }
}

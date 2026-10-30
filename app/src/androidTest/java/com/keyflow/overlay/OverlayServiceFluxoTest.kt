package com.keyflow.overlay

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.ComposeSmokeActivity
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.RecordingMode
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.Gatilho
import com.keyflow.fluxos.Superficie
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.app.PortasPorTipo
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.i18n.tr
import com.keyflow.ui.SetupActivity
import com.keyflow.vision.VisionFlowState
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Serviço real e portas falsas: intents, foreground, gravação, cancelamento e gatilhos passam pelo mesmo caminho do app. */
@RunWith(AndroidJUnit4::class)
class OverlayServiceFluxoTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repo by lazy { SettingsRepository(context) }
    private lateinit var scenario: ActivityScenario<ComposeSmokeActivity>
    private lateinit var settings: com.keyflow.data.Settings
    private lateinit var configAnterior: ConfigFluxos
    private lateinit var barrasAnteriores: com.keyflow.core.shortcuts.BarsConfig
    private val chamadas = CopyOnWriteArrayList<TipoEtapa>()
    private val avisos = CopyOnWriteArrayList<String>()
    private val fluxo = Fluxo("f-servico", "Fluxo de teste", etapas = listOf(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Inserir))
    private var corpo: suspend (Etapa, Valor) -> ResultadoEtapa<Valor> = { etapa, _ ->
        chamadas += etapa.tipo
        ResultadoEtapa.Ok(Valor.Texto("texto"))
    }

    private suspend fun aguardar(motivo: String, condicao: () -> Boolean) {
        try { withTimeout(15_000) { while (!condicao()) delay(25) } }
        catch (erro: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(motivo, erro) }
    }

    @Before fun preparar() = runBlocking {
        scenario = ActivityScenario.launch(Intent(context, ComposeSmokeActivity::class.java))
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        instrumentation.runOnMainSync { OverlayService.stop(context) }
        aguardar("serviço anterior não encerrou") { OverlayService.instanceForVisionTest == null }
        settings = repo.settingsFlow().first()
        configAnterior = repo.configFluxosOnce()
        barrasAnteriores = repo.barsConfigOnce()
        repo.saveSettings(settings.copy(overlayModeEnabled = false, overlayRecordingMode = RecordingMode.TOGGLE))
        repo.saveDisclosureConsent(DISCLOSURE_VERSION, false)
        repo.atualizarFluxosEBarras { _, barras ->
            ConfigFluxos(listOf(fluxo), listOf(Gatilho(Superficie.Barra("b-servico"), fluxo.id))) to
                barras.copy(collapsed = listOf(com.keyflow.core.shortcuts.BarDef(listOf(com.keyflow.core.shortcuts.BarButton.RunFlow("b-servico")))))
        }
        OverlayService.portasDeFluxoForTest = {
            PortasPorTipo(TipoEtapa.entries.associateWith { object : PortaEtapa {
                override suspend fun executar(etapa: Etapa, entrada: Valor) = corpo(etapa, entrada)
            } })
        }
        OverlayService.avisoDeFluxoForTest = { mensagem, _ -> avisos += mensagem }
        OverlayService.errorBannerDurationMsForTest = 30_000
        instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
    }

    @After fun limpar() = runBlocking {
        try {
            if (KeyFlowController.buttonState.value in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) {
                context.startService(OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
                aguardar("gravação não cancelou") { KeyFlowController.buttonState.value == ButtonState.CANCELLING }
            }
            if (KeyFlowController.buttonState.value == ButtonState.CANCELLING) {
                context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
                aguardar("cancelamento não concluiu") { KeyFlowController.buttonState.value in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD) }
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

    private fun disparar(manual: Boolean = false, fluxoId: String = fluxo.id) = OverlayService.start(context,
        OverlayService.actionIntent(context, "com.keyflow.overlay.RUN_FLOW")
            .putExtra(if (manual) "fluxo_id" else "botao_id", if (manual) fluxoId else "b-servico"))

    @Test fun actionRunFlowExecutaAsEtapasEmOrdem() = runBlocking {
        disparar()
        aguardar("ACTION_RUN_FLOW não executou as três etapas") { chamadas.size == 3 }
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.INSERIR), chamadas.toList())
        assertTrue(avisos.isEmpty())
    }

    private fun bloquearPerguntar(pronto: CompletableDeferred<Unit>, fim: CompletableDeferred<Unit>, cancelou: CompletableDeferred<Unit>? = null) {
        corpo = { etapa, _ ->
            chamadas += etapa.tipo
            if (etapa is Etapa.Perguntar) {
                pronto.complete(Unit)
                try { fim.await() } finally { if (!fim.isCompleted) cancelou?.complete(Unit) }
            }
            ResultadoEtapa.Ok(Valor.Texto("texto"))
        }
    }

    @Test fun segundoDisparoRecusaSemCancelarEManualNaoGravaAtalho() = runBlocking {
        val antes = repo.configFluxosOnce()
        val pronto = CompletableDeferred<Unit>()
        val fim = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, fim, cancelou)
        disparar(manual = true)
        withTimeout(15_000) { pronto.await() }
        disparar()
        aguardar("segundo disparo não avisou") { avisos.contains(tr("Já há um fluxo em andamento")) }
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR), chamadas.toList())
        assertFalse(cancelou.isCompleted)
        assertEquals(antes, repo.configFluxosOnce())
        fim.complete(Unit)
        aguardar("primeiro fluxo foi interrompido") { chamadas.size == 3 }
    }

    @Test fun modoSoTecladoMantemForegroundEntreEtapasEAoFimEncerra() = runBlocking {
        val pronto = CompletableDeferred<Unit>()
        val fim = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, fim)
        disparar()
        withTimeout(15_000) { pronto.await() }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        assertFalse(OverlayService.isRunningForUi)
        instrumentation.runOnMainSync {
            service.coordinatorForTest.onTargetChanged(true)
            service.coordinatorForTest.onTargetChanged(false)
        }
        instrumentation.waitForIdleSync()
        assertSame("voltar a ocioso encerrou o serviço entre etapas", service, OverlayService.instanceForVisionTest)
        assertTrue(service.foregroundForTest)
        assertTrue(service.fluxoAtivoForTest)
        fim.complete(Unit)
        aguardar("serviço só-teclado não encerrou ao terminar") { OverlayService.instanceForVisionTest == null }
        assertEquals(EstadoExecucao.Ocioso, KeyFlowController.fluxo.value)
    }

    @Test fun ditarECapturarRecusamRecordingEPausedSemAlterarAudio() = runBlocking {
        val captura = Fluxo("f-captura", "Captura", etapas = listOf(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir))
        val config = repo.atualizarFluxos { it.copy(fluxos = it.fluxos + captura) }
        OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        aguardar("gravação não iniciou") { KeyFlowController.buttonState.value == ButtonState.RECORDING }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        aguardar("configuração de captura não chegou ao serviço") { service.configFluxosForTest == config }
        for (estado in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) {
            for (id in listOf(fluxo.id, captura.id)) {
                val antes = avisos.size
                disparar(manual = true, fluxoId = id)
                aguardar("fluxo não recusou gravação em $estado") { avisos.size > antes }
                assertEquals(tr("Termine a gravação atual antes de rodar o fluxo."), avisos.last())
                assertEquals(estado, KeyFlowController.buttonState.value)
                assertEquals(VisionFlowState.NONE, OverlayService.visionFlowStateForTest.value)
                assertTrue(chamadas.isEmpty())
            }
            if (estado == ButtonState.RECORDING) {
                context.startService(OverlayService.actionIntent(context, RecordingNotification.ACTION_PAUSE_RESUME))
                aguardar("gravação não pausou") { KeyFlowController.buttonState.value == ButtonState.PAUSED }
            }
        }
    }

    @Test fun falhaMostraBannerEToqueAbreAAbaCerta() = runBlocking {
        corpo = { etapa, _ ->
            chamadas += etapa.tipo
            if (etapa is Etapa.Perguntar) ResultadoEtapa.Falha(ErroEtapa(CausaErro.REDE, "falha sintética", DestinoAba.RACIOCINIO))
            else ResultadoEtapa.Ok(Valor.Texto("texto"))
        }
        disparar()
        aguardar("falha não publicou aviso") { avisos.isNotEmpty() }
        assertEquals(tr("O fluxo parou em “%s”: %s", tr("Perguntar"), "falha sintética"), avisos.single())
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR), chamadas.toList())
        val device = UiDevice.getInstance(instrumentation)
        val monitor = instrumentation.addMonitor(SetupActivity::class.java.name, null, false)
        try {
            val banner = device.wait(Until.findObject(By.textContains(avisos.single())), 15_000)
            assertNotNull("banner do fluxo não ficou visível", banner)
            banner!!.click()
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("toque no banner não abriu a configuração", activity)
            assertEquals("RACIOCINIO", activity!!.intent.getStringExtra("com.keyflow.config_tab"))
            instrumentation.runOnMainSync { activity.finish() }
            aguardar("a configuração aberta pelo banner não fechou") { activity.isDestroyed }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun toqueNaOrbeCancelaPerguntarERestauraGlifo() = runBlocking {
        val pronto = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, CompletableDeferred(), cancelou)
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        aguardar("orbe não apareceu") { OverlayService.isRunningForUi }
        disparar()
        withTimeout(15_000) { pronto.await() }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        val estadoAntes = KeyFlowController.buttonState.value
        instrumentation.runOnMainSync { assertEquals(TipoEtapa.PERGUNTAR, service.orbeForTest!!.fluxoEtapaForTest) }
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("glifo da etapa não ficou acessível na orbe", device.wait(Until.hasObject(By.desc(tr("Perguntar"))), 15_000))
        instrumentation.runOnMainSync { service.handleOrbIntent(GestureIntent.START) }
        withTimeout(15_000) { cancelou.await() }
        aguardar("cancelamento não chegou ao controlador") { KeyFlowController.fluxo.value is EstadoExecucao.Cancelado }
        instrumentation.runOnMainSync { assertNull(service.orbeForTest!!.fluxoEtapaForTest) }
        assertTrue("glifo não sumiu ao cancelar", device.wait(Until.gone(By.desc(tr("Perguntar"))), 15_000))
        assertEquals(estadoAntes, KeyFlowController.buttonState.value)
    }

    @Test fun arrastarOrbeDurantePerguntarNaoCancela() = runBlocking { conferirArrastoDurante(Etapa.Perguntar()) }

    @Test fun arrastarOrbeDuranteFalarNaoCancela() = runBlocking { conferirArrastoDurante(Etapa.Falar()) }

    @Test fun arrastarOrbeDuranteAgenteNaoCancela() = runBlocking { conferirArrastoDurante(Etapa.Agente()) }

    private suspend fun conferirArrastoDurante(etapaBloqueada: Etapa) {
        val posicaoAnterior = repo.overlayPositionFlow().first()
        val pronto = CompletableDeferred<Unit>()
        val fim = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        corpo = { etapa, _ ->
            chamadas += etapa.tipo
            if (etapa.tipo == etapaBloqueada.tipo) {
                pronto.complete(Unit)
                try { fim.await() } finally { if (!fim.isCompleted) cancelou.complete(Unit) }
            }
            ResultadoEtapa.Ok(Valor.Texto("texto"))
        }
        val config = repo.atualizarFluxos { it.copy(fluxos = listOf(fluxo.copy(
            etapas = listOf(Etapa.Ditar(), etapaBloqueada, Etapa.Inserir)))) }
        try {
            OverlayService.start(context, OverlayService.startOverlayIntent(context))
            aguardar("orbe não apareceu") { OverlayService.instanceForVisionTest?.orbeForTest != null }
            val service = checkNotNull(OverlayService.instanceForVisionTest)
            aguardar("configuração do arrasto não chegou") { service.configFluxosForTest == config }
            disparar()
            withTimeout(15_000) { pronto.await() }
            val estadoAntes = KeyFlowController.buttonState.value
            instrumentation.runOnMainSync {
                val view = checkNotNull(service.orbeForTest)
                val params = view.layoutParams as WindowManager.LayoutParams
                val xAntes = params.x
                val yAntes = params.y
                val agora = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { indice, acao ->
                    val evento = MotionEvent.obtain(agora, agora + indice * 10, acao,
                        if (indice == 0) 20f else 52f, if (indice == 0) 20f else 68f, 0)
                    try { view.onTouchEvent(evento) } finally { evento.recycle() }
                }
                assertEquals(xAntes + 32, params.x)
                assertEquals(yAntes + 48, params.y)
            }
            instrumentation.waitForIdleSync()
            assertFalse("reposicionar cancelou ${etapaBloqueada.tipo}", cancelou.isCompleted)
            assertTrue("reposicionar encerrou o fluxo", service.fluxoAtivoForTest)
            assertEquals(etapaBloqueada.tipo, (KeyFlowController.fluxo.value as EstadoExecucao.Rodando).etapa)
            assertEquals(estadoAntes, KeyFlowController.buttonState.value)
            assertTrue(avisos.isEmpty())
            fim.complete(Unit)
            aguardar("arrasto impediu a conclusão") { chamadas.size == 3 && !service.fluxoAtivoForTest }
        } finally { repo.saveOverlayPosition(posicaoAnterior) }
    }

    @Test fun actionRecordCancelaProcessamentoSemAbrirMicrofone() = runBlocking {
        val pronto = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, CompletableDeferred(), cancelou)
        disparar(manual = true)
        withTimeout(15_000) { pronto.await() }
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        withTimeout(15_000) { cancelou.await() }
        aguardar("cancelamento por intent não concluiu") { KeyFlowController.fluxo.value is EstadoExecucao.Cancelado }
        assertTrue(KeyFlowController.buttonState.value in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD))
    }

    @Test fun destruirServicoCancelaAPortaEmCurso() = runBlocking {
        val pronto = CompletableDeferred<Unit>()
        val cancelou = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, CompletableDeferred(), cancelou)
        disparar(manual = true)
        withTimeout(15_000) { pronto.await() }
        instrumentation.runOnMainSync { OverlayService.stop(context) }
        withTimeout(15_000) { cancelou.await() }
        aguardar("serviço continuou vivo após stop") { OverlayService.instanceForVisionTest == null }
        assertTrue(KeyFlowController.fluxo.value is EstadoExecucao.Cancelado)
    }


    @Test fun configuracaoAtualizadaAoVivoValeParaOProximoDisparo() = runBlocking {
        val pronto = CompletableDeferred<Unit>()
        val fim = CompletableDeferred<Unit>()
        bloquearPerguntar(pronto, fim)
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        aguardar("orbe não apareceu") { OverlayService.isRunningForUi }
        disparar(manual = true)
        withTimeout(15_000) { pronto.await() }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        val nova = repo.atualizarFluxos { it.copy(fluxos = listOf(fluxo.copy(etapas = listOf(Etapa.Ditar(), Etapa.Falar())))) }
        aguardar("mudança ao vivo não chegou ao serviço") { service.configFluxosForTest == nova }
        fim.complete(Unit)
        aguardar("execução anterior não terminou") { chamadas.size == 3 && !service.fluxoAtivoForTest }
        disparar(manual = true)
        aguardar("novo disparo não usou o fluxo salvo") { chamadas.size == 5 }
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.INSERIR, TipoEtapa.DITAR, TipoEtapa.FALAR), chamadas.toList())
    }


    private suspend fun servicoParaGestos(config: ConfigFluxos): OverlayService {
        repo.saveSettings(settings.copy(overlayModeEnabled = false, overlayRecordingMode = RecordingMode.TOGGLE,
            shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME,
            shortcutOverflow = ShortcutOverflow.NEAREST_DOWN))
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        aguardar("orbe não apareceu") { OverlayService.isRunningForUi }
        val service = checkNotNull(OverlayService.instanceForVisionTest)
        aguardar("configuração dos gestos não chegou") {
            service.configFluxosForTest == config &&
                service.settingsForTest.shortcut2Tap == OrbShortcutAction.TOGGLE_KEYBOARD &&
                service.settingsForTest.shortcut3Tap == OrbShortcutAction.SWITCH_IME &&
                service.settingsForTest.shortcutOverflow == ShortcutOverflow.NEAREST_DOWN
        }
        return service
    }

    private suspend fun comContadorDeExpansao(bloco: suspend (java.util.concurrent.atomic.AtomicInteger) -> Unit) = coroutineScope {
        val antes = KeyFlowController.keyboardVisible.value
        instrumentation.runOnMainSync { KeyFlowController.setKeyboardVisible(true) }
        val contador = java.util.concurrent.atomic.AtomicInteger()
        val coleta = launch(start = CoroutineStart.UNDISPATCHED) { KeyFlowController.toggleKeyboardExpand.collect { contador.incrementAndGet() } }
        try { bloco(contador) } finally {
            coleta.cancel()
            instrumentation.runOnMainSync { KeyFlowController.setKeyboardVisible(antes) }
        }
    }

    @Test fun doisToquesRodamFluxoSemAlternarExpansao() = runBlocking {
        val config = repo.atualizarFluxos { it.copy(gatilhos = it.gatilhos + Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), fluxo.id)) }
        val service = servicoParaGestos(config)
        comContadorDeExpansao { contador ->
            instrumentation.runOnMainSync { service.handleOrbShortcut(2) }
            aguardar("dois toques não executaram o fluxo") { chamadas.size == 3 }
            yield()
            assertEquals(0, contador.get())
            assertEquals(OrbShortcutAction.TOGGLE_KEYBOARD, repo.settingsFlow().first().shortcut2Tap)
            val desligada = repo.atualizarFluxos { atual ->
                atual.copy(gatilhos = atual.gatilhos.filterNot { it.superficie == Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2) })
            }
            aguardar("desligar o gatilho não chegou ao serviço") { service.configFluxosForTest == desligada }
            instrumentation.runOnMainSync { service.handleOrbShortcut(2) }
            aguardar("desligar o fluxo não devolveu a ação anterior") { contador.get() == 1 }
            assertEquals(3, chamadas.size)
        }
        assertTrue(avisos.isEmpty())
    }

    @Test fun tresToquesRodamFluxo() = runBlocking {
        val config = repo.atualizarFluxos { it.copy(gatilhos = it.gatilhos + Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3), fluxo.id)) }
        val service = servicoParaGestos(config)
        instrumentation.runOnMainSync { service.handleOrbShortcut(3) }
        aguardar("três toques não executaram o fluxo") { chamadas.size == 3 }
        assertTrue(avisos.isEmpty())
    }

    @Test fun semGatilhoDoisToquesPreservamAAcaoAnterior() = runBlocking {
        val service = servicoParaGestos(repo.configFluxosOnce())
        comContadorDeExpansao { contador ->
            instrumentation.runOnMainSync { service.handleOrbShortcut(2) }
            aguardar("ação de expansão não foi preservada") { contador.get() == 1 }
            assertTrue(chamadas.isEmpty())
        }
    }

    @Test fun quatroToquesComNearestDownRodamOFluxoDoTres() = runBlocking {
        val terceiro = Fluxo("f-tres", "Três toques", etapas = listOf(Etapa.Ditar(), Etapa.Falar()))
        val config = repo.atualizarFluxos { it.copy(fluxos = it.fluxos + terceiro, gatilhos = it.gatilhos + listOf(
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), fluxo.id),
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3), terceiro.id))) }
        val service = servicoParaGestos(config)
        instrumentation.runOnMainSync { service.handleOrbShortcut(4) }
        aguardar("overflow não executou o fluxo do três") { chamadas.size == 2 }
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.FALAR), chamadas.toList())
    }

    @Test fun fluxoInvalidoAvisaSemRodarAAcaoSombreada() = runBlocking {
        val config = repo.atualizarFluxos { it.copy(fluxos = listOf(fluxo.copy(etapas = listOf(Etapa.Perguntar()))),
            gatilhos = it.gatilhos + Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), fluxo.id)) }
        val service = servicoParaGestos(config)
        comContadorDeExpansao { contador ->
            instrumentation.runOnMainSync { service.handleOrbShortcut(2) }
            aguardar("fluxo incompleto não avisou") { avisos.contains(tr("Fluxo incompleto: abra a aba Fluxos")) }
            yield()
            assertEquals(0, contador.get())
            assertTrue(chamadas.isEmpty())
        }
    }

}

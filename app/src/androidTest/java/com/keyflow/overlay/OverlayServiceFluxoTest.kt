package com.keyflow.overlay

import android.Manifest
import android.content.Intent
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
        instrumentation.runOnMainSync { service.handleOrbIntent(GestureIntent.START) }
        withTimeout(15_000) { cancelou.await() }
        aguardar("cancelamento não chegou ao controlador") { KeyFlowController.fluxo.value is EstadoExecucao.Cancelado }
        instrumentation.runOnMainSync { assertNull(service.orbeForTest!!.fluxoEtapaForTest) }
        assertEquals(estadoAntes, KeyFlowController.buttonState.value)
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

}

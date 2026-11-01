package com.keyflow.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Looper
import android.app.ActivityManager
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.SettingsRepository
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.Botao
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.app.ProvedorDeAparencia
import com.keyflow.notificacao.app.RenderizadorDeOrbe
import com.keyflow.notificacao.app.NotificacaoDaOrbe
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NTF-T10: o serviço real recebe o clique da orbe e os dos botões da notificação (NTF-09..11, 14, 16, 38, 48, 49). Dirige o [OverlayService] pelas ações
 * (o mesmo caminho dos `PendingIntent`s da notificação) e observa o `KeyFlowController.buttonState`. O serviço é parado no `@After`.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceNotificacaoTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val repo get() = SettingsRepository(context)
    private var savedVersion = 0
    private var savedDeclined = false
    private var consentimentoSalvo = false

    @Before
    fun setUp() {
        pararServicoEEsperar()
        OverlayService.bloqueadaParaTeste = null
        OverlayService.notificacoesPermitidasParaTeste = null
        OverlayService.criarProvedorDeAparenciaParaTeste = null
        OverlayService.pedirConsentimentoVisaoParaTeste = null
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        OverlayService.cancelWindowMsForTest = 30_000L
        runBlocking {
            val s = repo.settingsFlow().first()
            savedVersion = s.disclosureAcceptedVersion
            savedDeclined = s.disclosureDeclined
            consentimentoSalvo = true
            repo.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
            repo.atualizarNotificacao { ConfigNotificacao() }
        }
        instrumentation.runOnMainSync {
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        }
        esperarGravacaoNova()
    }

    @After
    fun tearDown() {
        try {
            pararServicoEEsperar()
        } finally {
            OverlayService.cancelWindowMsForTest = null
            OverlayService.bloqueadaParaTeste = null
            OverlayService.notificacoesPermitidasParaTeste = null
            OverlayService.criarProvedorDeAparenciaParaTeste = null
            OverlayService.pedirConsentimentoVisaoParaTeste = null
            if (consentimentoSalvo) runBlocking {
                repo.atualizarNotificacao { ConfigNotificacao() }
                repo.saveDisclosureConsent(savedVersion, savedDeclined)
            }
        }
    }

    /** Estado global pode sobrar do teste anterior; só vale RECORDING de uma instância já criada. */
    private fun esperarGravacaoNova() = waitUntil({ "a nova instância não iniciou a gravação: ${KeyFlowController.buttonState.value}" }) {
        OverlayService.instanceForVisionTest != null && KeyFlowController.buttonState.value == ButtonState.RECORDING
    }

    private fun pararServicoEEsperar() {
        instrumentation.runOnMainSync { OverlayService.stop(context) }
        waitUntil({ "a instância anterior ainda não foi destruída" }) {
            OverlayService.instanceForVisionTest == null && !servicoRegistrado()
        }
        instrumentation.waitForIdleSync()
    }

    @Suppress("DEPRECATION") // getRunningServices ainda lista os serviços do próprio app, inclusive antes de onCreate.
    private fun servicoRegistrado(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == OverlayService::class.java.name }

    private fun toqueNaOrbe() = context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_NOTIF_ORB_TAP))

    private fun botao(b: Botao) {
        val acao = when (b) {
            Botao.PAUSAR, Botao.RETOMAR -> OverlayService.ACTION_NOTIF_PAUSE_RESUME
            Botao.DESCARTAR, Botao.ENCERRAR -> OverlayService.ACTION_NOTIF_CANCEL
            Botao.DESFAZER -> OverlayService.ACTION_NOTIF_UNDO
        }
        context.startService(OverlayService.actionIntent(context, acao).putExtra(NotificacaoDaOrbe.EXTRA_BOTAO, b.name))
    }

    /**
     * Manda o botão até o estado chegar: a configuração do DataStore chega ao serviço de forma assíncrona.
     * Repetir estes botões é idempotente, pois o host ignora o botão quando ele não pertence mais ao estado atual.
     */
    private fun botaoAte(b: Botao, vararg estados: ButtonState) {
        val fim = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < fim) {
            botao(b)
            val ate = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < ate) {
                if (KeyFlowController.buttonState.value in estados) return
                Thread.sleep(50)
            }
        }
        throw AssertionError("o botão $b não levou a ${estados.joinToString()}; estado atual: ${KeyFlowController.buttonState.value}")
    }

    private fun config(f: (ConfigNotificacao) -> ConfigNotificacao) = runBlocking { repo.atualizarNotificacao(f) }

    /** Provoca a falsa prontidão antiga na mesma tarefa da main, antes de o Android poder criar o serviço. */
    @Test
    fun recordingHerdadoNaoDispensaEsperarANovaInstancia() {
        pararServicoEEsperar()
        val estados = CopyOnWriteArrayList<ButtonState>()
        val observador = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            instrumentation.runOnMainSync {
                KeyFlowController.updateButtonState(ButtonState.RECORDING)
                observador.launch(start = CoroutineStart.UNDISPATCHED) {
                    KeyFlowController.buttonState.collect { estados += it }
                }
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
                assertEquals("a espera antiga aceitaria o valor herdado", ButtonState.RECORDING, KeyFlowController.buttonState.value)
                assertNull("o serviço ainda não pode ter sido criado na mesma tarefa da main", OverlayService.instanceForVisionTest)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NTF_CORRIDA esperaAntiga=aceita instancia=null\n") })
            }
            esperarGravacaoNova()
            instrumentation.runOnMainSync {
                assertTrue("onCreate precisa publicar o estado ocioso antes da gravação nova: $estados", estados.any {
                    it == ButtonState.IDLE_NO_FIELD || it == ButtonState.IDLE_READY
                })
                assertEquals(ButtonState.RECORDING, estados.last())
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NTF_CORRIDA transicoes=$estados instanciaNova=true\n") })
            }
        } finally {
            observador.cancel()
        }
    }

    @Test
    fun umToqueNaOrbeEnviaAGravacao() {
        toqueNaOrbe()
        waitUntilState(ButtonState.SENDING, ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, ButtonState.ERROR, ButtonState.PENDING_PASTE)
    }

    @Test
    fun pausarRetomarEBotaoAtrasadoIgnorado() {
        botaoAte(Botao.PAUSAR, ButtonState.PAUSED)
        botao(Botao.PAUSAR) // o botão era do estado gravando: ignorado
        Thread.sleep(600)
        assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
        botaoAte(Botao.RETOMAR, ButtonState.RECORDING)
    }

    @Test
    fun descartarAbreAJanelaDeDesfazerEDesfazerVoltaAPausado() {
        botaoAte(Botao.DESCARTAR, ButtonState.CANCELLING)
        botaoAte(Botao.DESFAZER, ButtonState.PAUSED)
    }

    @Test
    fun configSemToqueDeGravacaoEAceitaENenhumToqueGrava() {
        config { it.copy(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.Nenhuma, acao3Toques = AcaoDaOrbe.Nenhuma) }
        waitUntil({ "config não chegou ao serviço" }) { runBlocking { repo.configNotificacaoOnce().acao1Toque == AcaoDaOrbe.Nenhuma } }
        Thread.sleep(300)
        toqueNaOrbe()
        Thread.sleep(900)
        assertEquals("1 toque em Nenhuma não faz nada", ButtonState.RECORDING, KeyFlowController.buttonState.value)
    }

    @Test
    fun remapearGravarEnviarParaDoisToquesGravaPorEle() {
        config { it.copy(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.GravarEnviar) }
        Thread.sleep(500)
        toqueNaOrbe(); toqueNaOrbe()
        waitUntilState(ButtonState.SENDING, ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, ButtonState.ERROR, ButtonState.PENDING_PASTE)
    }

    /**
     * O bloqueio é simulado pelo gancho `bloqueadaParaTeste` (no lugar do `KeyguardManager`): mexer no bloqueio do emulador deixava o SystemUI em ANR e a
     * prova virava `Assume` quando o AVD não travava. O portão em si é coberto, com relógio virtual, em `NotificacaoHostTest`; aqui vai a fiação do serviço.
     */
    @Test
    fun comATelaBloqueadaEAOpcaoDesligadaNemAOrbeNemOsBotoesAgem() {
        OverlayService.bloqueadaParaTeste = { true }
        toqueNaOrbe()
        botao(Botao.PAUSAR)
        botao(Botao.DESCARTAR)
        Thread.sleep(1200)
        assertEquals("bloqueada + opção desligada: nada age", ButtonState.RECORDING, KeyFlowController.buttonState.value)
        // ligada, na mesma tela bloqueada: o botão age
        config { it.copy(permitirComTelaBloqueada = true) }
        waitUntil({ "config não chegou" }) { runBlocking { repo.configNotificacaoOnce().permitirComTelaBloqueada } }
        Thread.sleep(300)
        botaoAte(Botao.PAUSAR, ButtonState.PAUSED)
    }

    private fun servico() = checkNotNull(OverlayService.instanceForVisionTest)
    private fun acao(acao: String) = context.startService(OverlayService.actionIntent(context, acao))
    private fun assentarNotificacao() = waitUntil({ "a notificação não assentou" }, 15_000) { !servico().notificacaoPendenteParaTeste }
    private fun persistente() {
        acao(OverlayService.ACTION_START_OVERLAY)
        waitUntil({ "o modo persistente não iniciou" }) { OverlayService.isRunningForUi }
        assentarNotificacao()
    }
    private fun dispensar() {
        val n = servico().ultimaNotificacaoParaTeste ?: servico().notificacaoInicialParaTeste
        checkNotNull(n?.deleteIntent).send()
        waitUntil({ "deleteIntent não dispensou" }) { !servico().notificacaoVisivelParaTeste }
    }
    private fun preferencias() = runBlocking { repo.rawPreferencesOnce().asMap().toMap() }

    @Test
    fun dispensarNaoRepostaEmPausaRetomadaEEnvioENaoGravaNoDataStore() {
        persistente()
        val antes = preferencias()
        dispensar()
        val posts = servico().notificacoesPublicadasParaTeste
        botaoAte(Botao.PAUSAR, ButtonState.PAUSED)
        botaoAte(Botao.RETOMAR, ButtonState.RECORDING)
        toqueNaOrbe()
        waitUntilState(ButtonState.SENDING, ButtonState.ERROR, ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, ButtonState.PENDING_PASTE)
        val depoisDaJanela = android.os.SystemClock.uptimeMillis() + 300
        waitUntil({ "o publicador não passou da janela de coalescência" }) {
            android.os.SystemClock.uptimeMillis() >= depoisDaJanela && !servico().notificacaoPendenteParaTeste
        }
        assertFalse(servico().notificacaoVisivelParaTeste)
        assertEquals(posts, servico().notificacoesPublicadasParaTeste)
        assertTrue("dispensar alterou preferências", antes == preferencias())
        assertTrue("dispensar não encerra o primeiro plano", servico().foregroundForTest)
    }

    @Test
    fun novaEntradaEInicioDeGravacaoRecompoemADispensada() {
        persistente()
        for (entrada in listOf(OverlayService.ACTION_START_OVERLAY, OverlayService.ACTION_KEYBOARD_MIC, OverlayService.ACTION_RECORD)) {
            assentarNotificacao(); dispensar()
            acao(entrada)
            waitUntil({ "a entrada $entrada não repôs" }) { servico().notificacaoVisivelParaTeste }
            assentarNotificacao()
        }
        // Uma sessão persistente nova isola a prova do resultado da transcrição anterior.
        pararServicoEEsperar()
        instrumentation.runOnMainSync { OverlayService.start(context, OverlayService.startOverlayIntent(context)) }
        waitUntil({ "a sessão persistente nova não iniciou" }) { OverlayService.isRunningForUi }
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY)
        assentarNotificacao(); dispensar()
        instrumentation.runOnMainSync { servico().coordinatorForTest.onTap() }
        waitUntilState(ButtonState.RECORDING)
        waitUntil({ "a gravação nova não repôs" }) { servico().notificacaoVisivelParaTeste }
    }

    @Test
    fun visaoEAbaAbertaRepoemMasOAbaNaoIniciaServico() {
        persistente(); dispensar()
        OverlayService.pedirConsentimentoVisaoParaTeste = {}
        acao(OverlayService.ACTION_VISION)
        waitUntil({ "Visão não repôs" }) { servico().notificacaoVisivelParaTeste }
        assentarNotificacao(); dispensar()
        OverlayService.mostrarNotificacaoSeEmPrimeiroPlano(context)
        waitUntil({ "a aba não repôs" }) { servico().notificacaoVisivelParaTeste }
        pararServicoEEsperar()
        OverlayService.mostrarNotificacaoSeEmPrimeiroPlano(context)
        instrumentation.waitForIdleSync()
        assertNull(OverlayService.instanceForVisionTest)
        assertFalse(servicoRegistrado())
    }

    @Test
    fun mostrarDesligadoUsaSoAMinimaInclusiveNoPrimeiroPlanoECanaisSaoSilenciosos() {
        config { it.copy(mostrar = false) }
        waitUntil({ "config mínima não chegou" }) { !servico().configNotificacaoParaTeste.mostrar }
        assentarNotificacao()
        fun minima(n: Notification?) {
            assertNotNull(n)
            assertEquals(NotificacaoDaOrbe.CANAL_MINIMO, n!!.channelId)
            assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
            assertNull(n.contentView)
            assertTrue(n.actions.isNullOrEmpty())
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        }
        minima(servico().ultimaNotificacaoParaTeste ?: servico().notificacaoInicialParaTeste)
        pararServicoEEsperar()
        instrumentation.runOnMainSync { OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD)) }
        esperarGravacaoNova(); assentarNotificacao()
        minima(servico().notificacaoInicialParaTeste)
        minima(servico().ultimaNotificacaoParaTeste ?: servico().notificacaoInicialParaTeste)
        val canais = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, canais.getNotificationChannel("keyflow_overlay").importance)
        val pedido = servico().canaisDaNotificacao().single { it.id == NotificacaoDaOrbe.CANAL_MINIMO }
        assertEquals("o app pede MIN", NotificationManager.IMPORTANCE_MIN, pedido.importance)
        assertNull(pedido.sound)
        assertFalse(pedido.shouldVibrate())
        // AOSP 14, NotificationManagerService:6277–6293: o primeiro FGS promove MIN para LOW.
        val efetiva = canais.getNotificationChannel(NotificacaoDaOrbe.CANAL_MINIMO).importance
        assertTrue("importância do canal mínima ou promovida pelo sistema: $efetiva",
            efetiva == NotificationManager.IMPORTANCE_MIN || efetiva == NotificationManager.IMPORTANCE_LOW)
        @Suppress("DEPRECATION") // o canal existente pode guardar a escolha anterior do usuário; a publicação é silenciosa
        assertNull(servico().notificacaoInicialParaTeste!!.sound)
        assertTrue(servico().foregroundForTest)
    }

    @Test
    fun permissaoNegadaNaoImpedeOPrimeiroPlanoNemDerrubaOServico() {
        pararServicoEEsperar()
        OverlayService.notificacoesPermitidasParaTeste = { false }
        instrumentation.runOnMainSync { OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD)) }
        esperarGravacaoNova(); assentarNotificacao()
        assertNotNull(servico().notificacaoInicialParaTeste)
        assertTrue(servico().foregroundForTest)
        assertEquals(0, servico().notificacoesPublicadasParaTeste)
        OverlayService.notificacoesPermitidasParaTeste = { true }
        OverlayService.mostrarNotificacaoSeEmPrimeiroPlano(context)
        waitUntil({ "permissão concedida não permitiu repor" }) { servico().notificacoesPublicadasParaTeste > 0 }
    }

    @Test
    fun modoNoturnoDoSistemaRepostaETemaDoAppNaoMudaANotificacao() {
        assentarNotificacao()
        val s = servico()
        val antes = s.notificacoesPublicadasParaTeste
        val escuro = s.modoNoturnoNotificacaoParaTeste
        val nova = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (escuro) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        }
        instrumentation.runOnMainSync { s.onConfigurationChanged(nova) }
        waitUntil({ "modo noturno não re-postou" }) { s.notificacoesPublicadasParaTeste > antes }
        assertEquals(!escuro, s.modoNoturnoNotificacaoParaTeste)
        val settings = runBlocking { repo.settingsFlow().first() }
        try {
            val posts = s.notificacoesPublicadasParaTeste
            runBlocking { repo.saveSettings(settings.copy(themeDark = !settings.themeDark)) }
            waitUntil({ "tema do app não chegou" }) { s.settingsForTest.themeDark != settings.themeDark }
            assentarNotificacao()
            assertEquals("tema do app não é o da notificação", !escuro, s.modoNoturnoNotificacaoParaTeste)
            assertEquals(posts, s.notificacoesPublicadasParaTeste)
        } finally { runBlocking { repo.saveSettings(settings) } }
    }

    @Test
    fun renderForaDaMainCaiNoAtelieEDepoisNaMinimaSemImpedirOPrimeiroPlano() {
        for (falhaTotal in listOf(false, true)) {
            pararServicoEEsperar()
            val estilos = CopyOnWriteArrayList<com.keyflow.ui.orb.OrbStyle>()
            OverlayService.criarProvedorDeAparenciaParaTeste = { ctx ->
                ProvedorDeAparencia(ctx, skinsUnlocked = { true }, renderizar = { estilo, estado, lado, glifo ->
                    assertFalse("render na main", Looper.myLooper() == Looper.getMainLooper())
                    estilos += estilo
                    if (falhaTotal || estilo == com.keyflow.ui.orb.OrbStyle.GLITCH) throw OutOfMemoryError("simulado")
                    RenderizadorDeOrbe.renderizar(estilo, estado, lado, glifo)
                })
            }
            config { it.copy(copiarDaOrbePrincipal = false, skin = "glitch") }
            instrumentation.runOnMainSync { OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD)) }
            esperarGravacaoNova(); assentarNotificacao()
            assertTrue("não tentou o Ateliê", com.keyflow.ui.orb.OrbStyle.ATELIE in estilos)
            val n = servico().ultimaNotificacaoParaTeste ?: servico().notificacaoInicialParaTeste
            assertNotNull(n)
            assertEquals(if (falhaTotal) NotificacaoDaOrbe.CANAL_MINIMO else "keyflow_overlay", n!!.channelId)
            assertTrue(servico().foregroundForTest)
        }
    }

    private fun waitUntilState(vararg expected: ButtonState, timeoutMs: Long = 8000) =
        waitUntil({ "esperando ${expected.joinToString(" ou ")}, estado atual: ${KeyFlowController.buttonState.value}" }, timeoutMs) {
            KeyFlowController.buttonState.value in expected
        }

    private fun waitUntil(message: () -> String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message())
    }

    private fun shellOut(command: String): String {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try { java.io.FileInputStream(fd.fileDescriptor).readBytes().toString(Charsets.UTF_8) } finally { fd.close() }
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try { java.io.FileInputStream(fd.fileDescriptor).readBytes() } finally { fd.close() }
    }
}

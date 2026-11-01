package com.keyflow.ui.config

import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.R
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** O painel real, o DataStore e o serviço real; nenhum clique da prévia precisa abrir uma sessão. */
@RunWith(AndroidJUnit4::class)
class NotificacaoServicoPanelTest {
    @get:Rule val regra = createComposeRule()
    @get:Rule val permissoes: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto get() = instrumentacao.targetContext
    private val repo get() = SettingsRepository(contexto)
    private var anterior: ConfigNotificacao? = null
    private var ajustes: Settings? = null
    private val chamadas = mutableListOf<Intent>()
    private var encaminhar = false
    private val contextoPainel by lazy { object : ContextWrapper(contexto) {
        override fun startService(intent: Intent): ComponentName? {
            chamadas += intent
            return if (encaminhar) super.startService(intent) else ComponentName(contexto, OverlayService::class.java)
        }
    } }
    private fun <T> dados(bloco: suspend () -> T): T = runBlocking { withTimeout(5_000) { bloco() } }
    private fun servico() = checkNotNull(OverlayService.instanceForVisionTest)
    @Before fun preparar() {
        AppLocale.pinned = AppLanguage.PT
        parar()
        ajustes = dados { repo.settingsFlow().first() }
        anterior = dados { repo.configNotificacaoOnce() }
        dados {
            repo.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
            repo.saveOverlayModeEnabled(false)
            repo.atualizarNotificacao { ConfigNotificacao() }
        }
    }
    @After fun limpar() {
        try { parar() } finally {
            dados {
                anterior?.let { valor -> repo.atualizarNotificacao { valor } }
                ajustes?.let { valor ->
                    repo.saveDisclosureConsent(valor.disclosureAcceptedVersion, valor.disclosureDeclined)
                    repo.saveOverlayModeEnabled(valor.overlayModeEnabled)
                }
            }
            AppLocale.pinned = null
        }
    }
    @Suppress("DEPRECATION")
    private fun registrado() = contexto.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == OverlayService::class.java.name }
    private fun parar() {
        instrumentacao.runOnMainSync { OverlayService.stop(contexto) }
        regra.waitUntil(12_000) { OverlayService.instanceForVisionTest == null && !registrado() }
    }
    private fun iniciarGravacao() {
        instrumentacao.runOnMainSync { OverlayService.start(contexto, OverlayService.actionIntent(contexto, OverlayService.ACTION_RECORD)) }
        regra.waitUntil(15_000) { OverlayService.instanceForVisionTest?.foregroundForTest == true && KeyFlowController.buttonState.value == ButtonState.RECORDING }
        regra.waitUntil(15_000) { !servico().notificacaoPendenteParaTeste }
    }
    private fun mostrar() {
        regra.setContent {
            val registro = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registro, LocalContext provides contextoPainel) {
            KeyFlowTheme(dark = false) { Box(Modifier.fillMaxSize()) {
                NotificacaoPanel(Settings(), true, {}, {}, repository = repo)
            } }
        } }
        regra.waitUntil(10_000) { regra.onAllNodesWithTag(NOTIFICACAO_PANEL_TAG).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun abrirSemServicoNaoEnviaIntentNemCriaSessao() {
        mostrar()
        regra.waitUntil(10_000) { regra.onAllNodesWithTag("notificacao-previa-pronta").fetchSemanticsNodes().isNotEmpty() }
        regra.runOnIdle { assertTrue("abrir o painel enviou startService", chamadas.isEmpty()) }
        assertNull(OverlayService.instanceForVisionTest)
        assertFalse(registrado())
    }
    @Test fun abrirComNotificacaoDispensadaRepoeNoServicoExistente() {
        iniciarGravacao()
        val instancia = servico()
        val notificacao = instancia.ultimaNotificacaoParaTeste ?: instancia.notificacaoInicialParaTeste
        checkNotNull(notificacao?.deleteIntent).send()
        regra.waitUntil(5_000) { !instancia.notificacaoVisivelParaTeste }
        val antes = instancia.notificacoesPublicadasParaTeste
        encaminhar = true
        mostrar()
        regra.waitUntil(15_000) { instancia.notificacoesPublicadasParaTeste > antes && instancia.notificacaoVisivelParaTeste }
        regra.runOnIdle { assertEquals(listOf(OverlayService.ACTION_NOTIF_SHOW), chamadas.map { it.action }) }
        assertSame(instancia, servico())
    }
    @Test fun permitirTelaBloqueadaPersisteERepostaAVersaoPublicaReal() {
        iniciarGravacao(); encaminhar = true; mostrar()
        regra.waitUntil(15_000) { !servico().notificacaoPendenteParaTeste }
        val antes = servico().notificacoesPublicadasParaTeste
        regra.onNodeWithTag("notificacao-tela-bloqueada", useUnmergedTree = true).performScrollTo().assertIsEnabled().performClick()
        regra.waitUntil(15_000) { servico().configNotificacaoParaTeste.permitirComTelaBloqueada &&
            servico().notificacoesPublicadasParaTeste > antes && !servico().notificacaoPendenteParaTeste }
        assertTrue(dados { repo.configNotificacaoOnce().permitirComTelaBloqueada })
        val ultima = checkNotNull(servico().ultimaNotificacaoParaTeste)
        assertEquals(Notification.VISIBILITY_PRIVATE, ultima.visibility)
        instrumentacao.runOnMainSync {
            val view = checkNotNull(ultima.publicVersion.contentView).apply(contexto, FrameLayout(contexto))
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.notif_btn_esq).visibility)
            assertEquals("Pausar gravação", view.findViewById<View>(R.id.notif_btn_esq).contentDescription.toString())
        }
    }
}

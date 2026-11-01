package com.keyflow.ui.config

import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.keyflow.R
import com.keyflow.notificacao.app.ProvedorDeAparencia
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.skins.SkinPackFixtures
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.theme.KeyFlowTheme
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificacaoPanelTest {
    @get:Rule val regra = createComposeRule()
    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pasta: File
    private lateinit var store: SkinPackStore
    private var raiz: View? = null
    private class Dados(inicial: ConfigNotificacao = ConfigNotificacao(), origem: Settings = Settings()) {
        var config by mutableStateOf(inicial)
        var settings by mutableStateOf(origem)
        var escritas = 0
        var pedidos = 0
        var desbloqueios = 0
        var noite by mutableStateOf(false)
        fun salvar(f: (ConfigNotificacao) -> ConfigNotificacao) { config = f(config); escritas++ }
    }
    @Before fun preparar() {
        AppLocale.pinned = AppLanguage.PT
        pasta = SkinPackFixtures.tempRoot(contexto.cacheDir)
        store = SkinPackStore(File(pasta, "skins"))
    }
    @After fun limpar() { pasta.deleteRecursively(); AppLocale.pinned = null }
    private fun mostrar(d: Dados, permissao: Boolean = true, pagas: Boolean = false, dark: Boolean = false,
        contextoDaTela: Context = contexto) {
        regra.setContent {
            val registro = checkNotNull(LocalActivityResultRegistryOwner.current)
            val view = LocalView.current
            SideEffect { raiz = view.rootView }
            val configuracao = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (d.noite) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registro, LocalConfiguration provides configuracao, LocalContext provides contextoDaTela) {
                KeyFlowTheme(dark = dark) { Box(Modifier.width(360.dp)) {
                    NotificacaoPanel(d.config, d.settings, permissao, { d.pedidos++ }, d::salvar, skinsUnlocked = pagas,
                        onUnlockSkins = { d.desbloqueios++ }, onSettingsChange = { d.settings = it }, store = store)
                } }
            }
        }
    }
    private fun linha(): View? = raiz?.findViewWithTag<FrameLayout>("notificacao-previa-android")?.let {
        if (it.childCount == 1) it.getChildAt(0) else null
    }
    private fun esperarLinha(condicao: (View) -> Boolean = { true }) {
        regra.waitUntil(10_000) {
            var pronta = false
            regra.runOnIdle { pronta = linha()?.let(condicao) == true }
            pronta
        }
    }
    private fun no(tag: String) = regra.onNodeWithTag(tag, useUnmergedTree = true)
    private fun clicar(tag: String) { val n = no(tag); if (!n.isDisplayed()) n.performScrollTo(); n.assertIsEnabled().performClick() }
    private fun escolher(n: Int, acao: String) { clicar("notificacao-toque-$n"); clicar("shortcut-action-$acao") }
    private fun confirmar() = clicar("notificacao-substituir-confirmar")
    private fun propria() = ConfigNotificacao(copiarDaOrbePrincipal = false)
    @Test fun permissaoNegadaExplicaEPedeSemIniciarServico() {
        val d = Dados(); mostrar(d, permissao = false)
        no("notificacao-sem-permissao").assertIsDisplayed()
        clicar("notificacao-permissao"); assertEquals(1, d.pedidos); assertEquals(0, d.escritas)
        no("notificacao-permissao").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
    @Test fun mostrarDesligadoGravaSemMudarCopiarOuAsAcoes() {
        val d = Dados(); mostrar(d)
        clicar("notificacao-mostrar")
        assertEquals(ConfigNotificacao(mostrar = false), d.config)
        no("notificacao-mostrar").assertHeightIsAtLeast(48.dp)
    }
    @Test fun copiarAlternarExibeGravarEnviarEImpedeEdicaoDeSkinsEAcoes() {
        val d = Dados(origem = Settings(overlayRecordingMode = RecordingMode.TOGGLE, shortcut1Tap = OrbShortcutAction.NONE))
        mostrar(d)
        no("notificacao-skin-atelie").performScrollTo().assertIsNotEnabled()
        no("notificacao-skin-atelie").performClick()
        no("notificacao-toque-1").performScrollTo().assertIsNotEnabled()
            .assertContentDescriptionEquals("1 toque na notificação: Gravar / enviar")
        no("notificacao-toque-1").performClick()
        no(SHORTCUT_SHEET_TAG).assertDoesNotExist()
        assertEquals(0, d.escritas)
        no(SKIN_PACK_IMPORT_TAG).performScrollTo().assertIsNotEnabled()
    }
    @Test fun pttComNenhumaNaoInventaGravacaoEDesligarCopiaCongelaValores() {
        val origem = Settings(orbSkin = "sistema", overlayRecordingMode = RecordingMode.PUSH_TO_TALK,
            shortcut1Tap = OrbShortcutAction.NONE, shortcut2Tap = OrbShortcutAction.VISION_MODE, shortcut3Tap = OrbShortcutAction.NONE)
        val d = Dados(origem = origem); mostrar(d)
        no("notificacao-toque-1").performScrollTo().assertContentDescriptionEquals("1 toque na notificação: Nenhuma")
        no("notificacao-sem-gravar").performScrollTo().assertIsDisplayed()
        clicar("notificacao-copiar")
        assertFalse(d.config.copiarDaOrbePrincipal)
        assertEquals("sistema", d.config.skin)
        assertEquals(AcaoDaOrbe.Nenhuma, d.config.acao1Toque)
        assertEquals(AcaoDaOrbe.LerTela, d.config.acao2Toques)
        no("notificacao-toque-1").performScrollTo().assertIsEnabled()
    }
    @Test fun valoresCopiadosAcompanhamAOrigemSemDialogoEmSegundoPlano() {
        val d = Dados(); mostrar(d)
        regra.runOnIdle { d.settings = d.settings.copy(shortcut2Tap = OrbShortcutAction.VISION_MODE, orbSkin = "sistema") }
        no("notificacao-toque-2").performScrollTo().assertContentDescriptionEquals("2 toques na notificação: Ler a tela")
        no("notificacao-substituir").assertDoesNotExist()
        assertEquals(0, d.escritas)
    }
    @Test fun substituirAcaoNomeiaAnteriorNovaEToqueECancelarNaoGrava() {
        val d = Dados(propria()); mostrar(d)
        escolher(2, "gravar")
        regra.onNodeWithText("2 toques na notificação: substituir Retrair / expandir teclado por Gravar / enviar", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, d.escritas)
        clicar("notificacao-substituir-cancelar")
        assertEquals(propria(), d.config)
        escolher(2, "gravar"); confirmar()
        assertEquals(AcaoDaOrbe.GravarEnviar, d.config.acao2Toques)
        escolher(1, "nenhuma"); confirmar()
        assertEquals(AcaoDaOrbe.Nenhuma, d.config.acao1Toque)
    }
    @Test fun nenhumToqueDeGravacaoAvisaSemBloquearOutraEscolha() {
        val d = Dados(propria().copy(acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.Nenhuma))
        mostrar(d)
        no("notificacao-sem-gravar").performScrollTo().assertIsDisplayed()
        escolher(3, "ler-tela")
        assertEquals(AcaoDaOrbe.LerTela, d.config.acao3Toques)
        no("notificacao-ler-tela-3").performScrollTo().assertTextEquals("Ler a tela precisa da permissão de sobreposição e do aparelho desbloqueado.")
        no("notificacao-substituir").assertDoesNotExist()
    }
    @Test fun catalogoSoTemQuatroAcoesELerTelaTemAvisoAoLado() {
        val d = Dados(propria()); mostrar(d)
        clicar("notificacao-toque-1")
        for (id in listOf("nenhuma", "gravar", "teclado", "ler-tela")) no("shortcut-action-$id").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        no("shortcut-action-switch_ime").assertDoesNotExist()
        regra.onNodeWithText("Ler a tela precisa da permissão de sobreposição e do aparelho desbloqueado.", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun ligarCopiaPedeConfirmacaoGenericaEPreservaValoresProprios() {
        val anterior = propria().copy(acao1Toque = AcaoDaOrbe.LerTela)
        val d = Dados(anterior); mostrar(d)
        clicar("notificacao-copiar"); no("notificacao-substituir").assertIsDisplayed()
        clicar("notificacao-substituir-cancelar"); assertEquals(anterior, d.config)
        clicar("notificacao-copiar"); confirmar()
        assertTrue(d.config.copiarDaOrbePrincipal)
        assertEquals(AcaoDaOrbe.LerTela, d.config.acao1Toque)
    }
    @Test fun interacoesAusentesDizemOndeCadaUmaFoiParar() {
        mostrar(Dados())
        for ((id, trecho) in listOf("SEGURAR" to "alternar", "ARRASTAR_CIMA" to "Pausar/Retomar", "ARRASTAR_LADO" to "Descartar", "ARRASTAR_BAIXO" to "corpo")) {
            no("notificacao-ausente-$id").performScrollTo().assertTextContains(trecho, substring = true)
        }
    }
    @Test fun skinPagaBloqueadaAbreDesbloqueioSemSelecionar() {
        val d = Dados(propria()); mostrar(d)
        clicar("notificacao-skin-aero")
        assertEquals(1, d.desbloqueios); assertEquals("atelie", d.config.skin)
        no("notificacao-skin-aero").assertContentDescriptionEquals("Aero, skin bloqueada")
    }
    @Test fun skinPagaLiberadaSelecionaENaoAlteraAOrigem() {
        val d = Dados(propria()); mostrar(d, pagas = true, dark = true)
        clicar("notificacao-skin-aero")
        assertEquals("aero", d.config.skin); assertEquals("atelie", d.settings.orbSkin)
    }
    @Test fun pacoteDeNotificacaoApareceEOrbeSozinhaNaoEListada() {
        val notificacao = SkinPackFixtures.zip(listOf("manifest.json" to SkinPackFixtures.manifest("Notificação teste"), "notification_idle.svg" to SkinPackFixtures.svg()))
        val orbe = SkinPackFixtures.zip(SkinPackFixtures.pack("Só orbe teste"))
        val a = store.import(ByteArrayInputStream(notificacao)); val b = store.import(ByteArrayInputStream(orbe))
        assertTrue(a.installed); assertTrue(b.installed)
        val d = Dados(propria()); mostrar(d)
        regra.waitUntil(10_000) { regra.onAllNodesWithTag(skinPackCardTag(a.skinId!!.removePrefix("pack:")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        clicar(skinPackCardTag(a.skinId!!.removePrefix("pack:")))
        assertEquals(a.skinId, d.config.skin)
        no(skinPackCardTag(b.skinId!!.removePrefix("pack:"))).assertDoesNotExist()
    }
    @Test fun portaRealPersisteSomenteOJsonDaNotificacao() {
        val repo = SettingsRepository(contexto)
        val anterior = runBlocking { repo.configNotificacaoOnce() }
        val settingsAntes = runBlocking { repo.rawPreferencesOnce().asMap().filterKeys { it.name != "notification_config_json" } }
        try {
            runBlocking { repo.atualizarNotificacao { ConfigNotificacao() } }
            regra.setContent { KeyFlowTheme(dark = false) {
                NotificacaoPanel(Settings(), true, {}, {}, repository = repo)
            } }
            regra.waitUntil(10_000) { regra.onAllNodesWithTag("notificacao-mostrar", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            clicar("notificacao-mostrar")
            regra.waitUntil(10_000) { runBlocking { !repo.configNotificacaoOnce().mostrar } }
            assertEquals(settingsAntes, runBlocking { repo.rawPreferencesOnce().asMap().filterKeys { it.name != "notification_config_json" } })
        } finally { runBlocking { repo.atualizarNotificacao { anterior } } }
    }
    @Test fun previaRealMudaOsSeteEstadosComOsBotoesCertos() {
        mostrar(Dados())
        val casos = listOf(
            Triple("GRAVANDO", "Pausar gravação", "Descartar gravação"),
            Triple("PAUSADA", "Retomar gravação", "Descartar gravação"),
            Triple("TRANSCREVENDO", null, "Descartar gravação"),
            Triple("CANCELANDO", null, "Desfazer descarte"),
            Triple("ERRO", null, null), Triple("VISAO", null, "Encerrar a leitura da tela"), Triple("OCIOSA", null, null),
        )
        for ((estado, esquerda, direita) in casos) {
            clicar("notificacao-previa-estado"); clicar("notificacao-previa-estado-$estado")
            esperarLinha { view ->
                val a = view.findViewById<View>(R.id.notif_btn_esq)
                val b = view.findViewById<View>(R.id.notif_btn_dir)
                a.visibility == (if (esquerda == null) View.INVISIBLE else View.VISIBLE) &&
                    b.visibility == (if (direita == null) View.INVISIBLE else View.VISIBLE) &&
                    (esquerda == null || a.contentDescription.toString() == esquerda) &&
                    (direita == null || b.contentDescription.toString() == direita)
            }
            regra.runOnIdle {
                val view = linha()!!
                val imagem = view.findViewById<ImageView>(R.id.notif_orb)
                assertTrue(imagem.drawable is BitmapDrawable)
                assertFalse(imagem.performClick())
                for (id in listOf(R.id.notif_btn_esq, R.id.notif_btn_dir)) assertFalse(view.findViewById<View>(id).performClick())
            }
        }
    }
    @Test fun previaCongelaOCronometroEmDozeSegundos() {
        mostrar(Dados())
        clicar("notificacao-previa-estado"); clicar("notificacao-previa-estado-GRAVANDO")
        esperarLinha { it.findViewById<Chronometer>(R.id.notif_time).visibility == View.VISIBLE }
        regra.runOnIdle { assertEquals("00:12", linha()!!.findViewById<Chronometer>(R.id.notif_time).text.toString()) }
        val fim = android.os.SystemClock.uptimeMillis() + 1_100
        regra.waitUntil(5_000) { android.os.SystemClock.uptimeMillis() >= fim }
        regra.runOnIdle { assertEquals("00:12", linha()!!.findViewById<Chronometer>(R.id.notif_time).text.toString()) }
    }
    @Test fun previaSegueSkinEfetivaEModoDoSistemaMesmoComTemaDoAppDiferente() {
        val d = Dados(); mostrar(d, dark = true)
        esperarLinha { (it.background as? ColorDrawable)?.color == ProvedorDeAparencia.FUNDO_CLARO }
        var anterior: android.graphics.Bitmap? = null
        regra.runOnIdle { anterior = (linha()!!.findViewById<ImageView>(R.id.notif_orb).drawable as BitmapDrawable).bitmap }
        regra.runOnIdle { d.noite = true }
        esperarLinha { (it.background as? ColorDrawable)?.color == ProvedorDeAparencia.FUNDO_ESCURO }
        regra.runOnIdle { d.settings = d.settings.copy(orbSkin = "sistema") }
        esperarLinha { (it.background as? ColorDrawable)?.color != ProvedorDeAparencia.FUNDO_ESCURO &&
            !(it.findViewById<ImageView>(R.id.notif_orb).drawable as BitmapDrawable).bitmap.sameAs(anterior!!) }
        regra.runOnIdle { assertEquals("sistema", d.settings.orbSkin); assertEquals(0, d.escritas) }
    }
    @Test fun telaBloqueadaComecaDesligadaEPersisteSemAlterarOutrosCampos() {
        val d = Dados(); mostrar(d)
        assertFalse(d.config.permitirComTelaBloqueada)
        clicar("notificacao-tela-bloqueada")
        assertEquals(ConfigNotificacao(permitirComTelaBloqueada = true), d.config)
        no("notificacao-tela-bloqueada").assertHeightIsAtLeast(48.dp)
    }
    @Test fun ajustesDisparamOIntentDoCanalSemAbrirUmaJanelaDoSistema() {
        val capturados = mutableListOf<Intent>()
        val ctx = object : ContextWrapper(contexto) {
            override fun startActivity(intent: Intent) { capturados += intent }
        }
        mostrar(Dados(), contextoDaTela = ctx)
        clicar("notificacao-ajustes-sistema")
        assertEquals(1, capturados.size)
        val intent = capturados.single()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(contexto.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
            assertEquals("keyflow_overlay", intent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
            assertNotNull(contexto.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("keyflow_overlay"))
        } else {
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:${contexto.packageName}", intent.dataString)
        }
        no("notificacao-ajustes-sistema").assertContentDescriptionEquals("Ajustes de notificação do sistema")
    }

}

package com.keyflow.ui.config

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.KeyFlowController
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.*
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.orbe.LugarDaOrbe
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Atalhos, botão da barra e exclusão no modal (FLX-T20), com o repositório real num DataStore isolado. */
@RunWith(AndroidJUnit4::class)
class FluxosAtalhosTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var idioma: AppLanguage? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var arquivo: File
    private lateinit var repo: SettingsRepository
    private val settings = Settings(shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.NONE)
    private val a = Fluxo("a", "Ouvir", etapas = listOf(Etapa.Ditar(), Etapa.Inserir))
    private val b = Fluxo("b", "Ler a tela", etapas = listOf(Etapa.Capturar, Etapa.Inserir))
    private val incompleto = Fluxo("i", "Pela metade", etapas = listOf(Etapa.Inserir))
    private var fechou = 0
    private var visivel by mutableStateOf(true)
    private var cancelou = 0
    private var view: View? = null
    private var escuro by mutableStateOf(false)
    private var s by mutableStateOf(settings)

    @Before fun preparar() {
        instrumentation.runOnMainSync { idioma = AppLocale.pinned; AppLocale.pinned = AppLanguage.PT }
        arquivo = File(instrumentation.targetContext.filesDir, "test-datastore/atalhos-${UUID.randomUUID()}.preferences_pb")
        repo = SettingsRepository(instrumentation.targetContext, arquivo, "fluxos-atalhos-${UUID.randomUUID()}", scope,
            securePrefsFactory = { _, _ -> error("o modal abriu o cofre") }, credentialStoreFactory = { _, _ -> error("o modal abriu o cofre") })
    }

    @After fun limpar() {
        instrumentation.runOnMainSync { AppLocale.pinned = idioma; KeyFlowController.updateFluxo(EstadoExecucao.Ocioso) }
        scope.cancel(); arquivo.delete()
    }

    private fun semear(vararg f: Fluxo, gatilhos: List<Gatilho> = emptyList()) = runBlocking {
        repo.atualizarFluxos { ConfigFluxos(f.toList(), gatilhos) }
    }
    private fun abrir(id: String) = rule.setContent {
        FluxosCena(escuro, { view = it }) {
            if (visivel) FluxosModal(id, repo, s, onDismiss = { fechou++ }, onCancelar = { cancelou++ })
        }
    }
    private fun config() = runBlocking { repo.configFluxosOnce() }
    private fun barras() = runBlocking { repo.barsConfigOnce() }
    private fun chavasGravadas() = runBlocking { repo.rawPreferencesOnce().asMap().keys.map { it.name }.toSet() }
    private fun esperar(cond: () -> Boolean) = rule.waitUntil(15_000, cond)
    private fun toque(n: Int) = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, n)
    private fun tocar(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()
    private fun texto(i: Int) = rule.onNodeWithTag("fluxos-confirmacao-texto-$i", useUnmergedTree = true)

    @Test fun ligarOsDoisToquesAvisaEOSoLigaAoConfirmarSemTocarNasSettings() {
        semear(a); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(2)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("fluxos-atalho-estado-2", useUnmergedTree = true).performScrollTo().assertTextEquals("hoje: Retrair / expandir teclado")
        tocar(tagLigarAtalho(2))
        texto(1).assertTextEquals("Isto substitui “Retrair / expandir teclado” neste gesto da orbe flutuante.")
        assertNull(config().gatilho(toque(2)))
        tocar("fluxos-confirmacao-cancelar")                       // cancelar preserva
        rule.onNodeWithTag(tagLigarAtalho(2)).assertExists()
        assertNull(config().gatilho(toque(2)))
        tocar(tagLigarAtalho(2)); tocar("fluxos-confirmacao-ligar")
        esperar { config().gatilho(toque(2))?.fluxoId == "a" }
        assertEquals(OrbShortcutAction.TOGGLE_KEYBOARD, s.shortcut2Tap)       // sombreada, não trocada
        rule.onNodeWithTag(tagDesligarAtalho(2)).performScrollTo().performClick()
        esperar { config().gatilho(toque(2)) == null }
        assertTrue("só as chaves dos fluxos", chavasGravadas().all { it == "flows_json" || it == "flow_triggers_json" })
    }

    @Test fun superficieLivreOuAcaoNenhumaLigaDiretoSemAviso() {
        semear(a); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(3)).fetchSemanticsNodes().isNotEmpty() }
        tocar(tagLigarAtalho(3))                                    // 3 toques: ação "Nenhuma"
        esperar { config().gatilho(toque(3))?.fluxoId == "a" }
        rule.onAllNodesWithTag("fluxos-confirmacao-ligar").assertCountEquals(0)
    }

    @Test fun superficieOcupadaPorOutroFluxoNomeiaOFluxoETiraDele() {
        semear(a, b, gatilhos = listOf(Gatilho(toque(2), "b"))); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(2)).fetchSemanticsNodes().isNotEmpty() }
        tocar(tagLigarAtalho(2))
        texto(1).assertTextEquals("Isto substitui “Fluxo: Ler a tela” neste gesto da orbe flutuante.")
        tocar("fluxos-confirmacao-ligar")
        esperar { config().gatilho(toque(2))?.fluxoId == "a" }
        assertEquals(1, config().gatilhos.size)
    }

    @Test fun qualquerOutraAcaoMostraOMesmoAvisoGenerico() {
        s = settings.copy(shortcut3Tap = OrbShortcutAction.VISION_MODE)
        semear(a); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(3)).fetchSemanticsNodes().isNotEmpty() }
        tocar(tagLigarAtalho(3))
        texto(1).assertTextEquals("Isto substitui “Capturar a tela (Visão)” neste gesto da orbe flutuante.")
    }

    @Test fun incompletoNaoLigaMasAindaDesliga() {
        semear(incompleto, gatilhos = listOf(Gatilho(toque(3), "i"))); abrir("i")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(2)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("fluxos-atalhos-incompleto", useUnmergedTree = true).performScrollTo().assertTextEquals("Complete o fluxo para ligar atalhos")
        rule.onNodeWithTag(tagLigarAtalho(2)).assertIsNotEnabled()
        rule.onNodeWithTag(FLUXOS_BOTAO_ADICIONAR_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(tagDesligarAtalho(3)).assertIsEnabled()
    }

    @Test fun adicionarERemoverBotaoDaBarraNumaTransacao() {
        semear(a); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(FLUXOS_BOTAO_ADICIONAR_TAG).fetchSemanticsNodes().isNotEmpty() }
        tocar(FLUXOS_BOTAO_ADICIONAR_TAG)
        esperar { config().gatilhos.any { it.superficie is Superficie.Barra } }
        val id = (config().gatilhos.single().superficie as Superficie.Barra).botaoId
        val bars = barras()
        assertFalse(bars.mirrored)
        assertEquals(BarButton.RunFlow(id), bars.collapsed.first().buttons.last())
        assertEquals(BarButton.RunFlow(id), bars.expanded.first().buttons.last())
        assertEquals(setOf(id), bars.botoesDeFluxo())
        tocar(tagRemoverBotao(id))
        esperar { config().gatilhos.isEmpty() }
        assertTrue(barras().botoesDeFluxo().isEmpty())
    }

    @Test fun excluirPedeConfirmacaoESoltaGatilhosEBotoes() {
        // Botão e gatilho nascem juntos (o gatilho de barra sem botão seria limpo como órfão na gravação).
        runBlocking {
            repo.atualizarFluxosEBarras { _, bs ->
                ConfigFluxos(listOf(a, b), listOf(Gatilho(toque(2), "a"), Gatilho(Superficie.Barra("abc123"), "a"), Gatilho(toque(3), "b"))) to
                    BotoesDeFluxo.comBotao(bs, "abc123")
            }
        }
        assertEquals(setOf("abc123"), barras().botoesDeFluxo())
        instrumentation.runOnMainSync { KeyFlowController.updateFluxo(EstadoExecucao.Rodando("a", 0, TipoEtapa.DITAR)) }
        abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-excluir").fetchSemanticsNodes().isNotEmpty() }
        tocar("fluxos-excluir")
        assertEquals(2, config().fluxos.size)                       // ainda não excluiu
        tocar("fluxos-confirmacao-cancelar")
        assertEquals(0, cancelou)
        tocar("fluxos-excluir"); tocar("fluxos-excluir-confirmar")
        esperar { config().fluxo("a") == null }
        assertEquals(listOf(Gatilho(toque(3), "b")), config().gatilhos)
        assertTrue(barras().botoesDeFluxo().isEmpty())
        assertEquals("cancela o que estava rodando", 1, cancelou)
        esperar { fechou == 1 }
    }

    /** Segura uma escrita no DataStore (o bloco roda dentro da transação) até [liberar]: a exclusão fica pendente atrás dela. */
    private class EscritaSegura(private val repo: SettingsRepository, scope: CoroutineScope) {
        private val entrou = java.util.concurrent.CountDownLatch(1)
        private val seguro = java.util.concurrent.CountDownLatch(1)
        val job = scope.launch(Dispatchers.IO) {
            repo.atualizarFluxos { c ->
                entrou.countDown()
                seguro.await()
                c.copy(fluxos = c.fluxos.map { if (it.id == "b") it.copy(nome = "Renomeado") else it })
            }
        }
        fun esperarEntrar() = assertTrue("a escrita concorrente não começou", entrou.await(15, java.util.concurrent.TimeUnit.SECONDS))
        fun liberar() = seguro.countDown()
    }

    @Test fun excluirTerminaMesmoSeOModalSaiDaTelaEComOutraEscritaEmAndamento() {
        runBlocking {
            repo.atualizarFluxosEBarras { _, bs ->
                ConfigFluxos(listOf(a, b), listOf(Gatilho(toque(2), "a"), Gatilho(Superficie.Barra("abc123"), "a"), Gatilho(toque(3), "b"))) to
                    BotoesDeFluxo.comBotao(bs, "abc123")
            }
        }
        abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-excluir").fetchSemanticsNodes().isNotEmpty() }
        tocar("fluxos-excluir")
        val outra = EscritaSegura(repo, scope); outra.esperarEntrar()
        tocar("fluxos-excluir-confirmar")
        // A exclusão está na fila atrás da outra escrita: nada persistiu e o modal ainda não fechou.
        rule.runOnIdle { assertEquals("fechou antes de persistir", 0, fechou) }
        assertNotNull("o fluxo já sumiu com a escrita pendente", config().fluxo("a"))
        // O modal sai da composição (o que o ConfigScreen faz ao fechar) e só então a escrita concorrente termina.
        rule.runOnIdle { visivel = false }
        outra.liberar()
        runBlocking { outra.job.join() }
        esperar { config().fluxo("a") == null }
        assertEquals(listOf(Gatilho(toque(3), "b")), config().gatilhos)
        assertEquals("Renomeado", config().fluxo("b")?.nome)
        assertTrue("o botão do teclado saiu na mesma transação", barras().botoesDeFluxo().isEmpty())
    }

    @Test fun excluirSoFechaOModalDepoisDePersistir() {
        semear(a, b); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-excluir").fetchSemanticsNodes().isNotEmpty() }
        tocar("fluxos-excluir")
        val outra = EscritaSegura(repo, scope); outra.esperarEntrar()
        tocar("fluxos-excluir-confirmar")
        rule.runOnIdle { assertEquals("fechou antes de persistir", 0, fechou) }
        assertNotNull(config().fluxo("a"))
        outra.liberar()
        esperar { config().fluxo("a") == null }
        esperar { fechou == 1 }
        assertEquals("Renomeado", config().fluxo("b")?.nome)
    }

    @Test fun soAOrbeFlutuanteEOBotaoExistemNaEntregaDestaTask() {
        assertEquals(setOf(LugarDaOrbe.FLUTUANTE), lugaresDisponiveis())
        semear(a); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(2)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Orbe flutuante").assertExists()
        rule.onNodeWithText("Botão do teclado").assertExists()
        rule.onAllNodesWithText("Orbe da notificação").assertCountEquals(0)
        rule.onAllNodesWithText("Orbe do teclado").assertCountEquals(0)
    }

    @Test fun capturasDosAtalhos() {
        semear(a, b, gatilhos = listOf(Gatilho(toque(3), "b"))); abrir("a")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tagLigarAtalho(2)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("fluxos-atalho-estado-2", useUnmergedTree = true).performScrollTo()
        rule.waitForIdle()
        capturaFluxos("modal-atalhos-claro", checkNotNull(view))
        rule.runOnIdle { escuro = true }; rule.waitForIdle()
        capturaFluxos("modal-atalhos-escuro", checkNotNull(view))
        rule.runOnIdle { escuro = false }; rule.waitForIdle()
        tocar(tagLigarAtalho(3))
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-confirmacao-ligar").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        capturaFluxos("modal-atalho-ocupado", checkNotNull(view))
        tocar("fluxos-confirmacao-cancelar"); tocar(tagLigarAtalho(2))
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-confirmacao-ligar").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        capturaFluxos("modal-atalho-substitui", checkNotNull(view))
        tocar("fluxos-confirmacao-cancelar"); tocar("fluxos-excluir")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-excluir-confirmar").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        capturaFluxos("modal-excluir", checkNotNull(view))
    }
}

package com.keyflow.ui.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.app.InteracaoDoApp
import com.keyflow.computador.app.PedidoDeInteracao
import com.keyflow.computador.app.texto
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputadorDialogosTest {
    @get:Rule val regra = createComposeRule()
    private val antiga = ImpressaoDigital.de(byteArrayOf(1))
    private val nova = ImpressaoDigital.de(byteArrayOf(2))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun limpar() { scope.cancel() }
    private fun no(tag: String) = regra.onNodeWithTag(tag, useUnmergedTree = true)
    private fun varrerSegredo(segredo: String) {
        fun todos(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(n) + n.children.flatMap(::todos)
        val nos = regra.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::todos)
        assertTrue("segredo no semantics", nos.all { n -> n.config.none { (_, valor) -> segredo in valor.toString() } })
    }
    @Test fun primeiraConexaoSuspendeAteConfiar() {
        val interacao = InteracaoDoApp()
        regra.setContent { KeyFlowTheme(dark = false) { ComputadorDialogos(interacao) } }
        regra.waitForIdle()
        val r = scope.async { interacao.confirmarServidor("ssh.example.org", 2222, "ssh-ed25519", nova) }
        regra.waitUntil(5_000) { interacao.pedido.value != null }
        regra.onNodeWithText("ssh.example.org:2222", useUnmergedTree = true).assertIsDisplayed()
        regra.onNodeWithText(nova.texto, useUnmergedTree = true).assertIsDisplayed()
        assertFalse(r.isCompleted)
        no("computador-servidor-confiar").performClick()
        assertTrue(runBlocking { withTimeout(5_000) { r.await() } })
    }
    @Test fun primeiraConexaoCancelarRecusa() {
        val p = PedidoDeInteracao.ConfirmarServidor("ssh.example.org", 22, "ssh-ed25519", nova)
        regra.setContent { KeyFlowTheme(dark = false) { ConfirmarServidor(p) } }
        no("computador-servidor-cancelar").performClick()
        assertFalse(runBlocking { p.resposta.await() })
    }
    @Test fun servidorMudouCancelarEPadraoEExibeAsDuasImpressoes() {
        val p = PedidoDeInteracao.ServidorMudou("ssh.example.org", 22, "ssh-ed25519", antiga, nova)
        regra.setContent { KeyFlowTheme(dark = false) { ServidorMudou(p) } }
        no("computador-servidor-cancelar").assertIsFocused()
        no("computador-servidor-cancelar").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ação padrão: Cancelar"))
        regra.onNodeWithText("Impressão anterior: ${antiga.texto}", useUnmergedTree = true).assertIsDisplayed()
        regra.onNodeWithText("Nova impressão: ${nova.texto}", useUnmergedTree = true).assertIsDisplayed()
        no("computador-servidor-substituir").assertDoesNotExist()
        no("computador-servidor-cancelar").performKeyInput { pressKey(Key.Enter) }
        assertTrue(p.resposta.isCompleted)
        assertFalse(runBlocking { p.resposta.await() })
    }
    @Test fun substituirExigeSegundoPassoEOutraAcaoExplicita() {
        val p = PedidoDeInteracao.ServidorMudou("ssh.example.org", 22, "ssh-ed25519", antiga, nova)
        regra.setContent { KeyFlowTheme(dark = false) { ServidorMudou(p) } }
        no("computador-servidor-revisar").performClick()
        assertFalse(p.resposta.isCompleted)
        no("computador-servidor-cancelar").assertIsFocused()
        no("computador-servidor-substituir").performClick()
        assertTrue(runBlocking { p.resposta.await() })
    }
    @Test fun cancelarNoSegundoPassoNaoSubstitui() {
        val p = PedidoDeInteracao.ServidorMudou("ssh.example.org", 22, "ssh-ed25519", antiga, nova)
        regra.setContent { KeyFlowTheme(dark = false) { ServidorMudou(p) } }
        no("computador-servidor-revisar").performClick(); no("computador-servidor-cancelar").performClick()
        assertFalse(runBlocking { p.resposta.await() })
    }
    @Test fun senhaMascaradaNaoPublicaTextoNemInputText() {
        val p = PedidoDeInteracao.Senha()
        regra.setContent { KeyFlowTheme(dark = false) { PedirSenha(p) } }
        no("computador-senha").performTextReplacement("SENHA-FALSA-SENTINELA")
        varrerSegredo("SENHA-FALSA-SENTINELA")
        no("computador-senha").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.InputText))
        regra.onNodeWithText("SENHA-FALSA-SENTINELA", useUnmergedTree = true).assertDoesNotExist()
        no("computador-senha-continuar").performClick()
        assertEquals("SENHA-FALSA-SENTINELA", runBlocking { p.resposta.await() })
    }
    @Test fun cancelarSenhaRecusaSemEntregarOTexto() {
        val p = PedidoDeInteracao.Senha()
        regra.setContent { KeyFlowTheme(dark = false) { PedirSenha(p) } }
        no("computador-senha").performTextReplacement("SENHA-FALSA")
        no("computador-senha-cancelar").performClick()
        assertNull(runBlocking { p.resposta.await() })
    }
    @Test fun keyboardInteractiveMantemOrdemECamposProtegidosInclusiveComEco() {
        val p = PedidoDeInteracao.Codigo("Verificação", "Use os dados de teste", listOf("Código", "Senha"), listOf(true, false))
        regra.setContent { KeyFlowTheme(dark = false) { CodigoDeVerificacao(p) } }
        no("computador-codigo-0").performScrollTo().performTextReplacement("123456-FALSO")
        no("computador-codigo-1").performScrollTo().performTextReplacement("senha-ki-falsa")
        varrerSegredo("123456-FALSO"); varrerSegredo("senha-ki-falsa")
        no("computador-codigo-continuar").performClick()
        assertEquals(listOf("123456-FALSO", "senha-ki-falsa"), runBlocking { p.resposta.await() })
    }
    @Test fun sairDaTelaRecusaPedidoPendente() {
        val interacao = InteracaoDoApp()
        var mostrar by androidx.compose.runtime.mutableStateOf(true)
        regra.setContent { KeyFlowTheme(dark = false) { if (mostrar) ComputadorDialogos(interacao) } }
        regra.waitForIdle()
        val r = scope.async { interacao.pedirSenha() }
        regra.waitUntil(5_000) { interacao.pedido.value != null }
        regra.runOnIdle { mostrar = false }
        regra.waitForIdle()
        assertNull(runBlocking { withTimeout(5_000) { r.await() } })
        assertNull(interacao.pedido.value)
    }
    @Test fun chaveNaoAceitaOfereceCopiarSoAPublica() {
        var copiou = false
        var fechou = false
        regra.setContent { KeyFlowTheme(dark = false) { ErroComputador(CausaSsh.CHAVE_NAO_ACEITA, copiarPublica = { copiou = true }) { fechou = true } } }
        no("computador-erro-copiar-publica").performClick()
        assertTrue(copiou); assertTrue(fechou)
    }
    @Test fun todasAsCausasTemMensagemEOrientacaoSemExcecaoCrua() {
        for (causa in CausaSsh.entries) {
            if (causa == CausaSsh.SERVIDOR_NAO_CONFIADO) { assertNull(causa.texto()); assertNull(causa.orientacao()) }
            else { assertFalse(causa.texto().isNullOrBlank()); assertFalse(causa.orientacao().isNullOrBlank()) }
        }
        regra.setContent { KeyFlowTheme(dark = false) { ErroComputador(CausaSsh.DNS) {} } }
        regra.onNodeWithText("Não encontrei esse endereço.", useUnmergedTree = true).assertIsDisplayed()
        regra.onNodeWithText("Verifique o endereço e a conexão de rede.", useUnmergedTree = true).assertIsDisplayed()
    }
}

package com.keyflow.computador.app

import com.keyflow.computador.ImpressaoDigital
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InteracaoDoAppTest {
    private val impressao = ImpressaoDigital.de(byteArrayOf(1))
    @Test fun semTelaNenhumPedidoEsperaResposta() = runTest {
        val i = InteracaoDoApp()
        assertFalse(i.confirmarServidor("teste", 22, "ssh-ed25519", impressao))
        assertFalse(i.confirmarTrocaServidor("teste", 22, "ssh-ed25519", impressao, impressao))
        assertNull(i.pedirSenha())
        assertNull(i.pedirCodigo("", "", listOf("Código"), listOf(false)))
        assertNull(i.pedido.value)
    }
    @Test fun pedidosConcorrentesSaoSerializadosSemPerderResposta() = runTest {
        val i = InteracaoDoApp().also { it.temTela = true }
        val senha = async { i.pedirSenha() }
        val codigo = async { i.pedirCodigo("", "", listOf("Código"), listOf(false)) }
        runCurrent()
        assertTrue(i.pedido.value is PedidoDeInteracao.Senha)
        (i.pedido.value as PedidoDeInteracao.Senha).resposta.complete("senha-falsa")
        runCurrent()
        assertEquals("senha-falsa", senha.await())
        assertTrue(i.pedido.value is PedidoDeInteracao.Codigo)
        (i.pedido.value as PedidoDeInteracao.Codigo).resposta.complete(listOf("123456-falso"))
        runCurrent()
        assertEquals(listOf("123456-falso"), codigo.await())
        assertNull(i.pedido.value)
    }
    @Test fun perderTelaRecusaPedidoAtualEAFila() = runTest {
        val i = InteracaoDoApp().also { it.temTela = true }
        val senha = async { i.pedirSenha() }
        val confiar = async { i.confirmarServidor("teste", 22, "ssh-ed25519", impressao) }
        runCurrent()
        i.temTela = false
        runCurrent()
        assertNull(senha.await())
        assertFalse(confiar.await())
        assertNull(i.pedido.value)
    }
    @Test fun voltarATelaNaoReapresentaPedidoAntigoDaFila() = runTest {
        val i = InteracaoDoApp().also { it.temTela = true }
        val primeira = async { i.pedirSenha() }
        val segunda = async { i.pedirSenha() }
        runCurrent()
        i.temTela = false
        i.temTela = true
        runCurrent()
        assertNull(primeira.await())
        assertNull(segunda.await())
        assertNull(i.pedido.value)
    }
    @Test fun cancelarConexaoLimpaPedidoELiberaFila() = runTest {
        val i = InteracaoDoApp().also { it.temTela = true }
        val primeira = async { i.pedirSenha() }
        runCurrent()
        val pedido = i.pedido.value as PedidoDeInteracao.Senha
        primeira.cancelAndJoin()
        assertNull(i.pedido.value)
        assertTrue(pedido.resposta.isCompleted)
        val segunda = async { i.pedirSenha() }
        runCurrent()
        (i.pedido.value as PedidoDeInteracao.Senha).resposta.complete("outra-falsa")
        assertEquals("outra-falsa", segunda.await())
    }
    @Test fun trocaDeServidorTemRespostaPropriaECancelamentoSeguro() = runTest {
        val i = InteracaoDoApp().also { it.temTela = true }
        val r = async { i.confirmarTrocaServidor("teste", 2222, "ssh-ed25519", impressao, impressao) }
        runCurrent()
        val p = i.pedido.value as PedidoDeInteracao.ServidorMudou
        assertEquals(2222, p.porta)
        i.temTela = false
        assertFalse(r.await())
    }
}

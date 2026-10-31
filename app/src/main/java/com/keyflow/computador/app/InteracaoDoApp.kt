package com.keyflow.computador.app

import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.ssh.InteracaoComUsuario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** O que a conexão pergunta à pessoa. Cada pedido suspende até [PedidoDeInteracao.resposta] ser completada (a tela da T16 completa). */
sealed interface PedidoDeInteracao {
    val resposta: CompletableDeferred<*>

    class ConfirmarServidor(val host: String, val porta: Int, val algoritmo: String, val impressao: ImpressaoDigital) : PedidoDeInteracao {
        override val resposta = CompletableDeferred<Boolean>()
    }

    class Senha : PedidoDeInteracao {
        override val resposta = CompletableDeferred<String?>()
    }

    class Codigo(val nome: String, val instrucao: String, val perguntas: List<String>, val eco: List<Boolean>) : PedidoDeInteracao {
        override val resposta = CompletableDeferred<List<String>?>()
    }
}

/**
 * A [InteracaoComUsuario] de produção: publica o pedido em [pedido] e **suspende** até a tela responder. **Sem ninguém olhando** ([temTela]
 * falso), recusa na hora (servidor não confiado, sem senha, sem código): uma conexão nunca espera um diálogo que ninguém verá. Quem desenha os
 * diálogos é a T16; até lá nenhuma tela do app abre sessão sozinha, então esta recusa é o comportamento seguro.
 */
class InteracaoDoApp : InteracaoComUsuario {
    private val _pedido = MutableStateFlow<PedidoDeInteracao?>(null)
    val pedido: StateFlow<PedidoDeInteracao?> = _pedido
    @Volatile var temTela: Boolean = false

    private suspend fun <T> perguntar(p: PedidoDeInteracao, recusa: T, resposta: CompletableDeferred<T>): T {
        if (!temTela) return recusa
        _pedido.value = p
        return try { resposta.await() } finally { if (_pedido.value === p) _pedido.value = null }
    }

    override suspend fun confirmarServidor(host: String, porta: Int, algoritmo: String, impressao: ImpressaoDigital): Boolean {
        val p = PedidoDeInteracao.ConfirmarServidor(host, porta, algoritmo, impressao)
        return perguntar(p, false, p.resposta)
    }

    override suspend fun pedirSenha(): String? { val p = PedidoDeInteracao.Senha(); return perguntar(p, null, p.resposta) }

    override suspend fun pedirCodigo(nome: String, instrucao: String, perguntas: List<String>, eco: List<Boolean>): List<String>? {
        val p = PedidoDeInteracao.Codigo(nome, instrucao, perguntas, eco)
        return perguntar(p, null, p.resposta)
    }
}

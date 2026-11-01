package com.keyflow.computador.app

import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.ssh.InteracaoComUsuario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pedidos transitórios, nunca persistidos: a tela completa uma resposta e a conexão retoma. */
sealed interface PedidoDeInteracao {
    val resposta: CompletableDeferred<*>
    fun recusar()

    class ConfirmarServidor(val host: String, val porta: Int, val algoritmo: String, val impressao: ImpressaoDigital) : PedidoDeInteracao {
        override val resposta = CompletableDeferred<Boolean>()
        override fun recusar() { resposta.complete(false) }
    }
    class ServidorMudou(val host: String, val porta: Int, val algoritmo: String, val antiga: ImpressaoDigital, val nova: ImpressaoDigital) : PedidoDeInteracao {
        override val resposta = CompletableDeferred<Boolean>()
        override fun recusar() { resposta.complete(false) }
    }
    class Senha : PedidoDeInteracao {
        override val resposta = CompletableDeferred<String?>()
        override fun recusar() { resposta.complete(null) }
    }
    class Codigo(val nome: String, val instrucao: String, val perguntas: List<String>, val eco: List<Boolean>) : PedidoDeInteracao {
        override val resposta = CompletableDeferred<List<String>?>()
        override fun recusar() { resposta.complete(null) }
    }
}

/** Um pedido por vez; sem tela visível recusa imediatamente, inclusive se ela sair durante a espera. */
class InteracaoDoApp : InteracaoComUsuario {
    private val _pedido = MutableStateFlow<PedidoDeInteracao?>(null)
    val pedido: StateFlow<PedidoDeInteracao?> = _pedido
    private val fila = Mutex()
    private val trava = Any()
    private var geracaoDaTela = 0L
    @Volatile var temTela: Boolean = false
        set(valor) = synchronized(trava) {
            field = valor
            if (!valor) { geracaoDaTela++; _pedido.value?.recusar(); _pedido.value = null }
        }

    private suspend fun <T> perguntar(p: PedidoDeInteracao, recusa: T, resposta: CompletableDeferred<T>): T {
        val geracao = synchronized(trava) { geracaoDaTela }
        return fila.withLock {
            val publicada = synchronized(trava) { if (temTela && geracao == geracaoDaTela) { _pedido.value = p; true } else false }
            if (!publicada) return@withLock recusa
            try { resposta.await() }
            finally { synchronized(trava) { if (_pedido.value === p) _pedido.value = null }; p.recusar() }
        }
    }

    override suspend fun confirmarServidor(host: String, porta: Int, algoritmo: String, impressao: ImpressaoDigital): Boolean {
        val p = PedidoDeInteracao.ConfirmarServidor(host, porta, algoritmo, impressao)
        return perguntar(p, false, p.resposta)
    }
    override suspend fun confirmarTrocaServidor(host: String, porta: Int, algoritmo: String, antiga: ImpressaoDigital, nova: ImpressaoDigital): Boolean {
        val p = PedidoDeInteracao.ServidorMudou(host, porta, algoritmo, antiga, nova)
        return perguntar(p, false, p.resposta)
    }
    override suspend fun pedirSenha(): String? { val p = PedidoDeInteracao.Senha(); return perguntar(p, null, p.resposta) }
    override suspend fun pedirCodigo(nome: String, instrucao: String, perguntas: List<String>, eco: List<Boolean>): List<String>? {
        val p = PedidoDeInteracao.Codigo(nome, instrucao, perguntas, eco)
        return perguntar(p, null, p.resposta)
    }
}

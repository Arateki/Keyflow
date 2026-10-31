package com.keyflow.computador.ssh

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Exceção (de rede, da JVM ou do Ktor) → [CausaSsh]. Olha a cadeia de causas e, para os tipos de bibliotecas que a JVM pura não
 * conhece, o **nome** da classe. A mensagem da exceção nunca é lida: pode citar usuário, endereço ou dados da autenticação.
 */
object MapaDeErros {
    fun causaDe(erro: Throwable): CausaSsh {
        var atual: Throwable? = erro
        var profundidade = 0
        while (atual != null && profundidade++ < 10) {
            val nome = atual.javaClass.name
            if (nome.endsWith("TransportException")) causaPelaMensagem(atual.message)?.let { return it }
            when {
                atual is UnknownHostException || nome.endsWith("UnresolvedAddressException") -> return CausaSsh.DNS
                atual is ConnectException || nome.contains("ConnectionRefused") -> return CausaSsh.RECUSADA
                atual is SocketTimeoutException || atual is TimeoutCancellationException || nome.contains("Timeout") -> return CausaSsh.TEMPO
                atual is NoRouteToHostException || atual is SocketException || nome.contains("ClosedChannel") || nome.contains("Network") -> return CausaSsh.REDE
            }
            atual = atual.cause?.takeIf { it !== atual }
        }
        return CausaSsh.PROTOCOLO
    }

    /**
     * O transporte do cbssh só diz o motivo no texto (`Failed to connect … Errors: Connection refused`). A mensagem é lida **só aqui**,
     * para escolher a causa por palavras-chave; ela nunca é guardada, mostrada nem logada.
     */
    private fun causaPelaMensagem(mensagem: String?): CausaSsh? {
        val m = mensagem?.lowercase() ?: return null
        return when {
            "refused" in m -> CausaSsh.RECUSADA
            "could not resolve" in m || "unknown host" in m || "unresolved" in m -> CausaSsh.DNS
            "timed out" in m || "timeout" in m -> CausaSsh.TEMPO
            "unreachable" in m || "no route" in m || "network" in m -> CausaSsh.REDE
            else -> null
        }
    }
}

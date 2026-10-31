package com.keyflow.computador.ssh

import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.chaves.Assinador
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/** Tamanho do terminal pedido ao servidor (`pty-req` e `window-change`). */
data class PtyInfo(val colunas: Int, val linhas: Int, val largura: Int = 0, val altura: Int = 0, val termo: String = "xterm-256color")

/**
 * O que a conexão precisa saber do app. [senhaLembrada] vem do cofre e nunca é impressa ([toString] a esconde); [assinador] só existe para
 * host com chave. [confiarSozinho] é `false` nas sessões do **agente**: servidor novo ou mudado nunca é aceito sem o usuário (CMP-53).
 */
class DestinoSsh(
    val host: Host,
    val senhaLembrada: String? = null,
    val assinador: Assinador? = null,
    val confiarSozinho: Boolean = true,
) {
    override fun toString() = "DestinoSsh(host=${host.id}, ***)"
}

/** Quem responde ao que só o usuário pode responder. Cada chamada **suspende** até a resposta; cancelar a coroutine vale como recusa. */
interface InteracaoComUsuario {
    suspend fun confirmarServidor(host: String, porta: Int, algoritmo: String, impressao: ImpressaoDigital): Boolean
    suspend fun pedirSenha(): String?
    suspend fun pedirCodigo(nome: String, instrucao: String, perguntas: List<String>, eco: List<Boolean>): List<String>?
}

/** Acontecimentos da conexão, para a sessão e a tela acompanharem. Nenhum carrega segredo. */
sealed interface EventoConexao {
    data object Conectando : EventoConexao
    data class AguardandoServidor(val algoritmo: String, val impressao: ImpressaoDigital) : EventoConexao
    data class ServidorMudou(val antiga: ImpressaoDigital, val nova: ImpressaoDigital) : EventoConexao
    data object Autenticando : EventoConexao
    data object Aberta : EventoConexao
    data class Caiu(val causa: CausaSsh) : EventoConexao
}

sealed interface ResultadoConexao {
    data object Aberta : ResultadoConexao
    data class Falhou(val causa: CausaSsh) : ResultadoConexao
}

/** O que o "Testar conexão" mostra (CMP-08). */
sealed interface ResultadoTeste {
    data class Ok(val algoritmoDoServidor: String, val impressao: ImpressaoDigital, val metodo: String, val latenciaMs: Long) : ResultadoTeste
    data class Falhou(val causa: CausaSsh) : ResultadoTeste
}

/** A porta da conexão SSH: o resto do app e os testes de unidade só conhecem isto, não o cbssh (CMP-17). */
interface ConexaoSsh {
    val eventos: SharedFlow<EventoConexao>

    /** Saída do shell (stdout e stderr, em ordem de chegada). Termina quando a conexão cai ou é fechada. */
    val saida: Flow<ByteArray>

    suspend fun conectar(destino: DestinoSsh, pty: PtyInfo): ResultadoConexao

    /** TCP + troca de chaves + verificação do servidor + autenticação, **sem abrir o shell**. */
    suspend fun testar(destino: DestinoSsh): ResultadoTeste

    suspend fun escrever(bytes: ByteArray)
    suspend fun redimensionar(pty: PtyInfo)
    suspend fun fechar()
}

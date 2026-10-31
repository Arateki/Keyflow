package com.keyflow.computador.sessao

import com.keyflow.computador.ssh.CausaSsh

enum class DonoDaSessao { USUARIO, AGENTE }

enum class MotivoEncerramento { USUARIO, OCIOSIDADE, HOST_REMOVIDO, AGENTE_PARADO, SISTEMA }

/** Máquina de estados da sessão (design §6). */
sealed interface EstadoSessao {
    data object Conectando : EstadoSessao
    data object AguardandoServidor : EstadoSessao
    data object Autenticando : EstadoSessao
    data object Aberta : EstadoSessao
    data class Reconectando(val tentativa: Int) : EstadoSessao

    /** Biometria fora da janela de 5 minutos: a reconexão espera o usuário (notificação "toque para reconectar"). */
    data object PrecisaDoUsuario : EstadoSessao
    data class Encerrada(val motivo: MotivoEncerramento) : EstadoSessao
    data class Falhou(val causa: CausaSsh) : EstadoSessao

    val terminal: Boolean get() = this is Encerrada || this is Falhou

    companion object {
        /** As transições que a máquina permite. Tudo fora disto é um estado impossível. */
        fun permitida(de: EstadoSessao, para: EstadoSessao): Boolean {
            if (de == para) return true
            if (de.terminal) return false
            if (para is Encerrada || para is Falhou) return true
            return when (de) {
                Conectando -> para == AguardandoServidor || para == Autenticando || para == Aberta
                AguardandoServidor -> para == Autenticando || para == Aberta
                Autenticando -> para == Aberta
                Aberta -> para is Reconectando || para == PrecisaDoUsuario
                is Reconectando -> para is Reconectando || para == Aberta || para == PrecisaDoUsuario
                PrecisaDoUsuario -> para is Reconectando || para == Conectando
                is Encerrada, is Falhou -> false
            }
        }
    }
}

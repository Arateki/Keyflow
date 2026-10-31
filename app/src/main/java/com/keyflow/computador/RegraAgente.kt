package com.keyflow.computador

/** Resultado de [RegraAgente.podeMarcar]: sim, ou o motivo de não poder (CMP-09). */
sealed interface PodeMarcar {
    data object Sim : PodeMarcar
    data class Nao(val motivo: MotivoAgente) : PodeMarcar
}

enum class MotivoAgente(val frase: String) {
    PERGUNTA_SEMPRE("Este servidor pede a autenticação na hora; o agente não tem como digitá-la. Guarde uma chave ou a senha."),
    SENHA_NAO_LEMBRADA("A senha não está guardada. Ligue \"lembrar senha\" para o agente poder usar este servidor."),
    CHAVE_COM_PASSPHRASE("Esta chave pede a senha dela na hora; o agente não pode usá-la."),
}

/** A marca "Pode ser usado pelo agente" só vale para host cuja autenticação o **app** resolve sozinho (CMP-09, D5). */
object RegraAgente {
    /**
     * @param chavePedePassphrase diz se a chave [AutenticacaoHost.Chave.keyId] precisa de passphrase digitada na hora. As chaves
     * do app (Keystore/envelope) nunca guardam passphrase, então o padrão é não.
     */
    fun podeMarcar(host: Host, chavePedePassphrase: (String) -> Boolean = { false }): PodeMarcar = when (val a = host.autenticacao) {
        AutenticacaoHost.Perguntar -> PodeMarcar.Nao(MotivoAgente.PERGUNTA_SEMPRE)
        AutenticacaoHost.Senha -> if (host.lembrarSenha) PodeMarcar.Sim else PodeMarcar.Nao(MotivoAgente.SENHA_NAO_LEMBRADA)
        is AutenticacaoHost.Chave -> if (chavePedePassphrase(a.keyId)) PodeMarcar.Nao(MotivoAgente.CHAVE_COM_PASSPHRASE) else PodeMarcar.Sim
    }
}

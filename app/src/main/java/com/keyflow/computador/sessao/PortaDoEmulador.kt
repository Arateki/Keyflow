package com.keyflow.computador.sessao

/** O que a sessão precisa do emulador da tela (implementada na T13 pelo `LigacaoEmulador`; a sessão não conhece o termlib). */
interface PortaDoEmulador {
    /** Bytes que chegaram do servidor (ou texto local, como o separador de reconexão). */
    fun escreverNaTela(bytes: ByteArray)
    fun tamanhoAtual(): com.keyflow.computador.ssh.PtyInfo
}

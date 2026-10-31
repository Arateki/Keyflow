package com.keyflow.computador.ssh

/** Por que uma conexão falhou ou caiu (tabela "Erros de conexão" do spec, CMP-21). A mensagem nunca vem da exceção: é [frase]. */
enum class CausaSsh(val frase: String?) {
    DNS("Não encontrei esse endereço."),
    RECUSADA("O servidor recusou a conexão."),
    TEMPO("O servidor não respondeu a tempo."),
    REDE("Sem conexão de rede."),

    /** O usuário cancelou a confirmação da impressão digital: não é erro, encerra calado. */
    SERVIDOR_NAO_CONFIADO(null),

    /** Sessão do agente diante de servidor novo: só o usuário confirma, no aplicativo. */
    SERVIDOR_PEDE_CONFIRMACAO("Confirme o servidor no aplicativo."),
    CHAVE_DO_SERVIDOR_MUDOU("A identificação deste servidor mudou."),
    AUTENTICACAO("Usuário ou senha incorretos."),
    CHAVE_NAO_ACEITA("O servidor não aceitou esta chave. Autorize a chave pública no servidor."),
    SEM_ALGORITMO("Este servidor só oferece algoritmos antigos que o KeyFlow não usa."),
    BIOMETRIA("Autenticação cancelada."),
    BIOMETRIA_INVALIDADA("A chave foi invalidada pela biometria."),
    DESCONECTADO("O servidor encerrou a conexão."),
    ENCERRADA_PELO_SISTEMA("O Android encerrou a sessão."),
    PROTOCOLO("Falha na comunicação com o servidor."),
}

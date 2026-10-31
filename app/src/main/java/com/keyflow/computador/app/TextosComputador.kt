package com.keyflow.computador.app

import com.keyflow.computador.sessao.MotivoRecusa
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.i18n.tr

/**
 * As frases em português do núcleo (`ssh/`, `sessao/`, validações) viram texto da interface **aqui**: cada uma passa por `tr` com o
 * literal à mostra (é assim que o `TranslationsTest` as enxerga), e o `when` é exaustivo — enum novo sem frase não compila.
 */
fun CausaSsh.texto(): String? = when (this) {
    CausaSsh.DNS -> tr("Não encontrei esse endereço.")
    CausaSsh.RECUSADA -> tr("O servidor recusou a conexão.")
    CausaSsh.TEMPO -> tr("O servidor não respondeu a tempo.")
    CausaSsh.REDE -> tr("Sem conexão de rede.")
    CausaSsh.SERVIDOR_NAO_CONFIADO -> null
    CausaSsh.SERVIDOR_PEDE_CONFIRMACAO -> tr("Confirme o servidor no aplicativo.")
    CausaSsh.CHAVE_DO_SERVIDOR_MUDOU -> tr("A identificação deste servidor mudou.")
    CausaSsh.AUTENTICACAO -> tr("Usuário ou senha incorretos.")
    CausaSsh.CHAVE_NAO_ACEITA -> tr("O servidor não aceitou esta chave. Autorize a chave pública no servidor.")
    CausaSsh.SEM_ALGORITMO -> tr("Este servidor só oferece algoritmos antigos que o KeyFlow não usa.")
    CausaSsh.BIOMETRIA -> tr("Autenticação cancelada.")
    CausaSsh.BIOMETRIA_INVALIDADA -> tr("A chave foi invalidada pela biometria.")
    CausaSsh.DESCONECTADO -> tr("O servidor encerrou a conexão.")
    CausaSsh.ENCERRADA_PELO_SISTEMA -> tr("O Android encerrou a sessão.")
    CausaSsh.PROTOCOLO -> tr("Falha na comunicação com o servidor.")
}

fun MotivoRecusa.texto(): String = when (this) {
    MotivoRecusa.LIMITE_TOTAL -> tr("O limite é de 8 sessões abertas. Feche uma para abrir outra.")
    MotivoRecusa.LIMITE_DO_AGENTE -> tr("O agente já tem 3 sessões abertas.")
}

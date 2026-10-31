package com.keyflow.computador.app

import com.keyflow.computador.MotivoAgente
import com.keyflow.computador.MotivoHost
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
fun MotivoHost.texto(): String = when (this) {
    MotivoHost.NOME_VAZIO -> tr("Dê um nome ao servidor.")
    MotivoHost.NOME_LONGO -> tr("O nome pode ter no máximo 40 caracteres.")
    MotivoHost.NOME_REPETIDO -> tr("Já existe um servidor com esse nome.")
    MotivoHost.ENDERECO_VAZIO -> tr("Informe o endereço do servidor.")
    MotivoHost.ENDERECO_LONGO -> tr("O endereço pode ter no máximo 253 caracteres.")
    MotivoHost.ENDERECO_INVALIDO -> tr("O endereço não é um nome, um IPv4 nem um IPv6 válido.")
    MotivoHost.IPV6_SEM_COLCHETES -> tr("Endereço IPv6 vai entre colchetes, por exemplo [::1].")
    MotivoHost.PORTA_INVALIDA -> tr("A porta vai de 1 a 65535.")
    MotivoHost.USUARIO_VAZIO -> tr("Informe o usuário.")
    MotivoHost.USUARIO_LONGO -> tr("O usuário pode ter no máximo 64 caracteres.")
    MotivoHost.USUARIO_INVALIDO -> tr("O usuário não pode ter espaços nem caracteres de controle.")
    MotivoHost.LISTA_CHEIA -> tr("O limite é de 50 servidores.")
}

fun MotivoAgente.texto(): String = when (this) {
    MotivoAgente.PERGUNTA_SEMPRE -> tr("Este servidor pede a autenticação na hora; o agente não tem como digitá-la. Guarde uma chave ou a senha.")
    MotivoAgente.SENHA_NAO_LEMBRADA -> tr("A senha não está guardada. Ligue \"lembrar senha\" para o agente poder usar este servidor.")
    MotivoAgente.CHAVE_COM_PASSPHRASE -> tr("Esta chave pede a senha dela na hora; o agente não pode usá-la.")
}

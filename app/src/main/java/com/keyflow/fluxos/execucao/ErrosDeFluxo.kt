package com.keyflow.fluxos.execucao

import com.keyflow.core.ErrorKind
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.speech.SpeechAvailability
import com.keyflow.speech.SpeechError

/**
 * A tabela "Erros" da spec (FLX-27) em código: cada erro dos motores vira uma [CausaErro] com a frase curta, já em `tr`, e a aba que
 * resolve. Puro (JVM). **Nunca** repassa o corpo cru de uma resposta: das mensagens dos motores só entra a que o próprio app escreveu
 * (a do Local e a da Voz), nunca a de [com.keyflow.reasoning.ReasoningEvent.Error.message].
 */
object ErrosDeFluxo {

    // ---- Transcrição (Ditar) ----

    fun deTranscricao(kind: ErrorKind, mensagem: String): ErroEtapa = when (kind) {
        ErrorKind.CONFIG_MISSING -> ErroEtapa(CausaErro.INDISPONIVEL, tr("Configure um provedor de voz na aba Transcrição."), DestinoAba.TRANSCRICAO)
        ErrorKind.AUTH -> autenticacao(DestinoAba.TRANSCRICAO)
        ErrorKind.NETWORK -> rede()
        ErrorKind.HTTP -> ErroEtapa(CausaErro.REDE, tr("Erro no servidor de transcrição."))
        ErrorKind.TIMEOUT -> tempo()
        ErrorKind.TOO_LARGE -> ErroEtapa(CausaErro.MOTOR, tr("Áudio muito longo para transcrever."))
        // O erro local já traz a causa específica, escrita pelo app para a pessoa (modelo, memória…).
        ErrorKind.LOCAL -> ErroEtapa(CausaErro.MOTOR, mensagem.ifBlank { tr("Falha na transcrição local.") }, DestinoAba.TRANSCRICAO)
        ErrorKind.EMPTY_AUDIO -> ErroEtapa(CausaErro.VAZIO, tr("Nada foi captado."))
    }

    // ---- Raciocínio (Perguntar / Agente) e Visão ----

    fun deRaciocinio(kind: ReasoningError): ErroEtapa = deMotorDeTexto(kind, DestinoAba.RACIOCINIO, tr("O modelo não conseguiu responder."))

    fun deVisao(kind: ReasoningError): ErroEtapa = deMotorDeTexto(kind, DestinoAba.VISAO, tr("O modelo não conseguiu ler a imagem."))

    private fun deMotorDeTexto(kind: ReasoningError, aba: DestinoAba, generica: String): ErroEtapa = when (kind) {
        ReasoningError.OOM -> memoria(aba)
        ReasoningError.MODEL_MISSING -> ErroEtapa(CausaErro.INDISPONIVEL, tr("O modelo ainda não foi baixado."), aba)
        ReasoningError.LOCAL -> ErroEtapa(CausaErro.INDISPONIVEL, tr("O modelo no aparelho não está disponível."), aba)
        ReasoningError.NETWORK -> rede()
        ReasoningError.UNAUTHORIZED, ReasoningError.NOT_ELIGIBLE -> autenticacao(aba)
        ReasoningError.RATE_LIMIT -> limite()
        ReasoningError.ENGINE -> ErroEtapa(CausaErro.MOTOR, generica)
    }

    /** O motivo de não haver motor pronto na origem da etapa, sem baixar nada (FLX-26). */
    fun indisponivel(motivo: UnavailableReason, aba: DestinoAba): ErroEtapa = ErroEtapa(
        CausaErro.INDISPONIVEL,
        when (motivo) {
            UnavailableReason.NO_LOCAL_MODEL -> tr("Nenhum modelo local selecionado.")
            UnavailableReason.MODEL_NOT_INSTALLED -> tr("O modelo ainda não foi baixado.")
            UnavailableReason.NOT_CONNECTED -> tr("Conecte o provedor.")
            UnavailableReason.NO_MODEL_CHOSEN -> tr("Escolha um modelo.")
        },
        aba,
    )

    /** A origem do `ModeloRef` não existe nesta aba (ou é a legada de créditos): a etapa falha, sem cair em outra origem. */
    fun modeloInvalido(aba: DestinoAba): ErroEtapa = ErroEtapa(CausaErro.INDISPONIVEL, tr("O modelo escolhido para esta etapa não existe mais."), aba)

    fun respostaEmBranco(): ErroEtapa = ErroEtapa(CausaErro.VAZIO, tr("O modelo não respondeu."))

    fun semTextoNaImagem(): ErroEtapa = ErroEtapa(CausaErro.VAZIO, tr("Nenhum texto encontrado."))

    // ---- Voz (Falar) ----

    fun deFala(kind: SpeechError, mensagem: String): ErroEtapa = when (kind) {
        SpeechError.MODEL_MISSING, SpeechError.TTS_DATA_MISSING, SpeechError.NOT_READY ->
            ErroEtapa(CausaErro.INDISPONIVEL, mensagem.ifBlank { tr("A voz não está disponível.") }, DestinoAba.VOZ)
        SpeechError.NETWORK -> rede()
        SpeechError.UNAUTHORIZED -> autenticacao(DestinoAba.VOZ)
        SpeechError.RATE_LIMIT -> limite()
        // A mensagem do SpeechEvent.Error já vem traduzida e curta, sem corpo cru de resposta (FENG-02).
        SpeechError.ENGINE -> ErroEtapa(CausaErro.MOTOR, mensagem.ifBlank { tr("A voz falhou.") }, DestinoAba.VOZ)
    }

    fun vozIndisponivel(disponibilidade: SpeechAvailability): ErroEtapa = ErroEtapa(
        CausaErro.INDISPONIVEL,
        when (disponibilidade) {
            SpeechAvailability.READY -> tr("A voz não está disponível.")
            SpeechAvailability.NO_LOCAL_MODEL -> tr("Nenhum modelo local selecionado.")
            SpeechAvailability.MODEL_NOT_INSTALLED -> tr("O modelo ainda não foi baixado.")
            SpeechAvailability.NOT_CONNECTED -> tr("Conecte o provedor.")
            SpeechAvailability.NO_ENDPOINT -> tr("Informe o endpoint da voz.")
            SpeechAvailability.NO_VOICE -> tr("Informe o ID da voz.")
        },
        DestinoAba.VOZ,
    )

    // ---- comuns ----

    fun rede() = ErroEtapa(CausaErro.REDE, tr("Sem conexão de rede."))

    fun tempo() = ErroEtapa(CausaErro.TEMPO, tr("Tempo esgotado."))

    fun limite() = ErroEtapa(CausaErro.LIMITE, tr("Limite de uso do provedor atingido."))

    fun autenticacao(aba: DestinoAba?) = ErroEtapa(CausaErro.AUTENTICACAO, tr("Falha de autenticação — verifique a conexão do provedor."), aba)

    fun memoria(aba: DestinoAba?) = ErroEtapa(CausaErro.MEMORIA, tr("Memória insuficiente para este modelo. Tente um menor."), aba)

    fun motor() = ErroEtapa(CausaErro.MOTOR, tr("Algo deu errado nesta etapa."))
}

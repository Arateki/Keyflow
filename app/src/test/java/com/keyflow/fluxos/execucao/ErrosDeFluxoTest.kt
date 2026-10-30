package com.keyflow.fluxos.execucao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ErrorKind
import com.keyflow.speech.SpeechAvailability
import org.junit.Test

/** A tabela "Erros" da spec (FLX-27) em código: transcrição, indisponibilidade e as frases que nunca levam corpo cru. */
class ErrosDeFluxoTest {
    @Test
    fun `transcription errors map to causes, tabs and phrases`() {
        val esperado = mapOf(
            ErrorKind.CONFIG_MISSING to CausaErro.INDISPONIVEL, ErrorKind.AUTH to CausaErro.AUTENTICACAO,
            ErrorKind.NETWORK to CausaErro.REDE, ErrorKind.HTTP to CausaErro.REDE, ErrorKind.TIMEOUT to CausaErro.TEMPO,
            ErrorKind.TOO_LARGE to CausaErro.MOTOR, ErrorKind.LOCAL to CausaErro.MOTOR, ErrorKind.EMPTY_AUDIO to CausaErro.VAZIO,
        )
        for (kind in ErrorKind.entries) assertThat(ErrosDeFluxo.deTranscricao(kind, "x").causa).isEqualTo(esperado.getValue(kind))
        assertThat(ErrosDeFluxo.deTranscricao(ErrorKind.AUTH, "x").destino).isEqualTo(DestinoAba.TRANSCRICAO)
        assertThat(ErrosDeFluxo.deTranscricao(ErrorKind.EMPTY_AUDIO, "").mensagem).isEqualTo("Nada foi captado.")
        // Só o erro local carrega a frase do app; os demais ignoram a mensagem crua.
        assertThat(ErrosDeFluxo.deTranscricao(ErrorKind.LOCAL, "Memória baixa para o modelo local").mensagem).isEqualTo("Memória baixa para o modelo local")
        assertThat(ErrosDeFluxo.deTranscricao(ErrorKind.HTTP, "corpo cru 500").mensagem).doesNotContain("corpo cru")
    }

    @Test
    fun `every speech availability other than ready has its own phrase and the Voz tab`() {
        for (a in SpeechAvailability.entries.filter { it != SpeechAvailability.READY }) {
            val erro = ErrosDeFluxo.vozIndisponivel(a)
            assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
            assertThat(erro.destino).isEqualTo(DestinoAba.VOZ)
            assertThat(erro.mensagem).isNotEmpty()
        }
    }
}

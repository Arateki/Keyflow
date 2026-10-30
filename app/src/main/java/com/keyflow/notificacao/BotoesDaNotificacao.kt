package com.keyflow.notificacao

import com.keyflow.core.ButtonState
import com.keyflow.i18n.trKey
import com.keyflow.orbe.ArrastosDaOrbe
import com.keyflow.orbe.EfeitoDeArrasto
import com.keyflow.orbe.Interacao

enum class Botao { PAUSAR, RETOMAR, DESCARTAR, DESFAZER, ENCERRAR }

/** Os botões ao lado da orbe: a orbe fica sempre no centro e o espaço do botão ausente é reservado (design §3.1, §6). */
data class Botoes(val esquerda: Botao?, val direita: Botao?) {
    val todos: List<Botao> get() = listOfNotNull(esquerda, direita)

    companion object {
        val NENHUM = Botoes(null, null)
    }
}

/**
 * Deriva os botões da notificação da tabela dos arrastos da orbe flutuante (NTF-48..50, design §3.1):
 * `ARRASTAR_CIMA` → esquerda; `ARRASTAR_LADO` → direita; `ABRIR_APP` → **sem botão** (é o toque no corpo, NTF-13). Acrescentos do autor
 * (A15), fora da derivação: `CANCELLING` → direita = Desfazer; Visão ativa → direita = Encerrar. Os botões seguem Alternar qualquer que
 * seja o modo de gravação da orbe flutuante (A17: a notificação não tem PTT).
 */
object BotoesDaNotificacao {
    fun para(estado: ButtonState, visaoAtiva: Boolean): Botoes {
        if (visaoAtiva) return Botoes(esquerda = null, direita = Botao.ENCERRAR)
        if (estado == ButtonState.CANCELLING) return Botoes(esquerda = null, direita = Botao.DESFAZER)
        val arrastos = ArrastosDaOrbe.comEfeito(estado)
        return Botoes(
            esquerda = arrastos[Interacao.ARRASTAR_CIMA]?.let(::botaoDe),
            direita = arrastos[Interacao.ARRASTAR_LADO]?.let(::botaoDe),
        )
    }

    /** O botão de um efeito de arrasto, ou `null` para o que não vira botão ([EfeitoDeArrasto.ABRIR_APP]). */
    fun botaoDe(efeito: EfeitoDeArrasto): Botao? = when (efeito) {
        EfeitoDeArrasto.PAUSAR -> Botao.PAUSAR
        EfeitoDeArrasto.RETOMAR -> Botao.RETOMAR
        EfeitoDeArrasto.DESCARTAR -> Botao.DESCARTAR
        EfeitoDeArrasto.ABRIR_APP -> null
    }

    /** A descrição de acessibilidade do botão (chave de `tr()`, NTF-45). */
    fun descricao(botao: Botao): String = when (botao) {
        Botao.PAUSAR -> trKey("Pausar gravação")
        Botao.RETOMAR -> trKey("Retomar gravação")
        Botao.DESCARTAR -> trKey("Descartar gravação")
        Botao.DESFAZER -> trKey("Desfazer descarte")
        Botao.ENCERRAR -> trKey("Encerrar a leitura da tela")
    }
}

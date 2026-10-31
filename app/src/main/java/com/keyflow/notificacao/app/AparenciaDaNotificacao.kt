package com.keyflow.notificacao.app

import android.graphics.Bitmap
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.GlifoDaOrbe

/** O que a `RemoteViews` precisa de uma skin num estado (design §7). */
data class AparenciaDaNotificacao(
    /** A orbe já com o glifo do estado. */
    val orbe: Bitmap,
    /** A tinta do tempo no centro e dos glifos dos botões, ARGB. */
    val tinta: Int,
    /** O fundo da linha (ARGB), ou `null` para o do sistema. */
    val fundo: Int?,
)

/** Uma fonte de orbe que não é o desenho da orbe flutuante: o pacote de skin (NTF-34, T8). Devolve `null` se não tem o que desenhar. */
interface FonteDeOrbe {
    fun desenhar(aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, ladoPx: Int, escuro: Boolean): AparenciaDaNotificacao?
}

/** Nem o Ateliê desenhou: o host cai na notificação mínima (NTF-30). */
class AparenciaIndisponivel(causa: Throwable? = null) : RuntimeException("a aparência da notificação não pôde ser desenhada", causa)

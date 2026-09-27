package com.keyflow.transcription.partial

import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** O que a UI junto à orbe precisa saber do parcial (PAR-04/05). */
data class PartialSnapshot(
    /** Texto acumulado, com as edições do usuário. */
    val text: String,
    /** Há trecho esperando ou em transcrição. */
    val transcribing: Boolean,
    /** Trechos que falharam e serão tentados de novo (na próxima pausa ou no envio — PAR-09). */
    val failed: Int,
)

/**
 * Buffer de uma sessão com transcrição parcial ao pausar (G12, PAR-03/06/09). Cada trecho fechado numa
 * pausa entra na fila e é transcrito pela origem ativa, um por vez, na ordem. O texto de cada trecho
 * vai para o **fim** do buffer — edições do usuário são a verdade e nunca são sobrescritas.
 *
 * A ordem é preservada mesmo com falha: um trecho que falhou segura os seguintes (já transcritos, mas
 * ainda não anexados) até ser refeito — na próxima pausa ou no envio. Nada daqui toca clipboard nem
 * inserção: quem entrega é o coordinator, com o resultado de [finish].
 *
 * Confinado a uma thread (o scope do coordinator, Main no app).
 */
class PartialSession(
    private val scope: CoroutineScope,
    private val transcribe: suspend (File) -> TranscriptionResult,
    private val onChange: (PartialSnapshot) -> Unit = {},
) {
    private enum class State { PENDING, TRANSCRIBING, DONE, FAILED }

    private class Segment(val file: File) {
        var state = State.PENDING
        var text = ""
        var error: TranscriptionResult.Error? = null
    }

    private val segments = mutableListOf<Segment>()
    private var appended = 0
    private var buffer = ""
    private var worker: Job? = null

    val text: String get() = buffer

    /** Arquivos dos trechos da sessão, em ordem (para descartar ou reter). */
    val files: List<File> get() = segments.map { it.file }

    val isEmpty: Boolean get() = segments.isEmpty() && buffer.isBlank()

    fun snapshot() = PartialSnapshot(
        text = buffer,
        transcribing = segments.any { it.state == State.PENDING || it.state == State.TRANSCRIBING },
        failed = segments.count { it.state == State.FAILED },
    )

    /** Um trecho acabou de ser fechado (pausa): refaz os que falharam antes e transcreve este. */
    fun enqueue(file: File) {
        retryFailed()
        segments += Segment(file)
        notifyChange()
        kick()
    }

    /** O usuário editou o texto (PAR-06): o que ele escreveu passa a ser o buffer. */
    fun edit(newText: String) {
        buffer = newText
        notifyChange()
    }

    /**
     * Envio (PAR-07/09): refaz os trechos que falharam, espera a fila esvaziar e devolve o texto
     * completo. Se algum trecho continuar falhando, devolve o erro dele (tratamento de erro atual). Sem
     * nada falado, EMPTY_AUDIO.
     */
    suspend fun finish(): TranscriptionResult {
        retryFailed()
        kick()
        worker?.join()
        segments.firstOrNull { it.state == State.FAILED }?.error?.let { return it }
        return if (buffer.isBlank()) {
            TranscriptionResult.Error(ErrorKind.EMPTY_AUDIO, "nenhum trecho com fala")
        } else {
            TranscriptionResult.Success(buffer)
        }
    }

    /** Fim da sessão (entregue ou cancelada de vez): para a fila e apaga os trechos. */
    fun discard() {
        worker?.cancel()
        worker = null
        segments.forEach { it.file.delete() }
        segments.clear()
        appended = 0
        buffer = ""
    }

    private fun retryFailed() {
        segments.filter { it.state == State.FAILED }.forEach {
            it.state = State.PENDING
            it.error = null
        }
    }

    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = segments.firstOrNull { it.state == State.PENDING } ?: break
                next.state = State.TRANSCRIBING
                notifyChange()
                when (val result = transcribe(next.file)) {
                    is TranscriptionResult.Success -> {
                        next.text = result.text.trim()
                        next.state = State.DONE
                    }
                    is TranscriptionResult.Error -> {
                        next.error = result
                        next.state = State.FAILED
                    }
                }
                appendReady()
                notifyChange()
            }
        }
    }

    /** Anexa, em ordem, os trechos prontos a partir do último anexado; para no primeiro que não está. */
    private fun appendReady() {
        while (appended < segments.size && segments[appended].state == State.DONE) {
            val piece = segments[appended].text
            if (piece.isNotEmpty()) buffer = if (buffer.isBlank()) piece else buffer.trimEnd() + " " + piece
            appended++
        }
    }

    private fun notifyChange() = onChange(snapshot())
}

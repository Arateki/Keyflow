package com.keyflow.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/** Estado da gravação (REC-01/02). */
enum class RecorderState { IDLE, RECORDING, PAUSED }

class AudioRecorder(
    context: Context,
    private val outputDir: File = File(context.cacheDir, "keyflow-recordings"),
    private val minDurationMs: Long = 300L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    // Duração ativa = soma dos segmentos gravados (sem contar o tempo em pausa).
    private var accumulatedMs: Long = 0L
    private var segmentStartMs: Long = 0L

    // Modo segmentado (transcrição parcial ao pausar, PAR-02): cada pausa fecha um arquivo.
    private var segmented = false
    private var segmentActiveMs: Long = 0L
    private val finished = mutableListOf<File>()

    var state: RecorderState = RecorderState.IDLE
        private set

    /** Segmentos já fechados nesta sessão (modo segmentado), em ordem. Vazio fora dele. */
    val segments: List<File> get() = finished.toList()

    /**
     * @param segmented PAR-02: cada [pause] finaliza o arquivo do trecho (stop de verdade, para poder
     * transcrevê-lo) e cada [resume] começa um arquivo novo. Desligado, uma sessão é um arquivo só.
     */
    fun start(segmented: Boolean = false) {
        check(state == RecorderState.IDLE) { "Recording already started" }
        outputDir.mkdirs()
        this.segmented = segmented
        finished.clear()
        accumulatedMs = 0L
        openSegment()
    }

    /**
     * Pausa a gravação (REC-01). Sem efeito se não estiver gravando. No modo segmentado, fecha o
     * trecho e o devolve — null se ficou curto demais ou o encoder não gerou nada.
     */
    fun pause(): File? {
        val mediaRecorder = recorder ?: return null
        if (state != RecorderState.RECORDING) return null
        val now = clock()
        accumulatedMs += now - segmentStartMs
        state = RecorderState.PAUSED
        if (!segmented) {
            mediaRecorder.pause()
            return null
        }
        segmentActiveMs += now - segmentStartMs
        return closeSegment()
    }

    /** Retoma (REC-02). Sem efeito se não estiver pausada. No modo segmentado, abre um arquivo novo. */
    fun resume() {
        if (state != RecorderState.PAUSED) return
        if (segmented) {
            openSegment()
            return
        }
        val mediaRecorder = recorder ?: return
        mediaRecorder.resume()
        segmentStartMs = clock()
        state = RecorderState.RECORDING
    }

    /**
     * Encerra. Fora do modo segmentado, devolve o arquivo da sessão (null se curta demais). No modo
     * segmentado, devolve o trecho em curso — null se parou pausado (o último já saiu no [pause]).
     */
    fun stop(): File? {
        if (segmented) {
            val last = if (state == RecorderState.RECORDING) {
                segmentActiveMs += clock() - segmentStartMs
                closeSegment()
            } else {
                null
            }
            clearState()
            return last
        }
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        val durationMs = activeDurationMs()

        return try {
            mediaRecorder.stop()
            mediaRecorder.release()
            clearState()

            if (file == null || durationMs < minDurationMs || !file.exists() || file.length() == 0L) {
                file?.delete()
                null
            } else {
                file
            }
        } catch (e: RuntimeException) {
            mediaRecorder.release()
            clearState()
            file?.delete()
            null
        }
    }

    /** Descarta a gravação: encerra o recorder e apaga o(s) arquivo(s), sem devolver (cancelar — Fase 2a). */
    fun discard() {
        val mediaRecorder = recorder
        val file = outputFile
        if (mediaRecorder == null && finished.isEmpty()) return
        mediaRecorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        file?.delete()
        finished.forEach { it.delete() }
        clearState()
    }

    /** Duração ativa acumulada da sessão, atravessando pausas e segmentos (REC-06). */
    fun activeDurationMs(): Long =
        accumulatedMs + if (state == RecorderState.RECORDING) clock() - segmentStartMs else 0L

    private fun openSegment() {
        val file = File(outputDir, "keyflow-${System.currentTimeMillis()}-${finished.size}.m4a")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        outputFile = file
        segmentActiveMs = 0L
        segmentStartMs = clock()
        state = RecorderState.RECORDING
    }

    /** Fecha o arquivo do trecho atual; entra em [segments] se tem áudio de verdade. */
    private fun closeSegment(): File? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val ok = runCatching { mediaRecorder.stop() }.isSuccess
        mediaRecorder.release()
        if (!ok || file == null || segmentActiveMs < minDurationMs || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        finished += file
        return file
    }

    private fun clearState() {
        recorder = null
        outputFile = null
        accumulatedMs = 0L
        segmentStartMs = 0L
        segmentActiveMs = 0L
        segmented = false
        state = RecorderState.IDLE
    }

    private companion object {
        const val AUDIO_BIT_RATE = 64_000
        // 16 kHz (NAT-02): é a taxa que os modelos de voz usam — o local e os da nuvem. Gravar a 44,1 kHz só
        // aumentava o arquivo e obrigava a reamostrar antes da inferência local.
        const val AUDIO_SAMPLE_RATE = 16_000
    }
}

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

    var state: RecorderState = RecorderState.IDLE
        private set

    fun start() {
        check(recorder == null) { "Recording already started" }

        outputDir.mkdirs()
        val file = File(outputDir, "keyflow-${System.currentTimeMillis()}.m4a")
        val mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = mediaRecorder
        outputFile = file
        accumulatedMs = 0L
        segmentStartMs = clock()
        state = RecorderState.RECORDING
    }

    /** Pausa a gravação (MediaRecorder.pause, API 24+). Sem efeito se não estiver gravando (REC-01). */
    fun pause() {
        val mediaRecorder = recorder ?: return
        if (state != RecorderState.RECORDING) return
        mediaRecorder.pause()
        accumulatedMs += clock() - segmentStartMs
        state = RecorderState.PAUSED
    }

    /** Retoma a gravação pausada (MediaRecorder.resume). Sem efeito se não estiver pausada (REC-02). */
    fun resume() {
        val mediaRecorder = recorder ?: return
        if (state != RecorderState.PAUSED) return
        mediaRecorder.resume()
        segmentStartMs = clock()
        state = RecorderState.RECORDING
    }

    fun stop(): File? {
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

    private fun activeDurationMs(): Long =
        accumulatedMs + if (state == RecorderState.RECORDING) clock() - segmentStartMs else 0L

    private fun clearState() {
        recorder = null
        outputFile = null
        accumulatedMs = 0L
        segmentStartMs = 0L
        state = RecorderState.IDLE
    }

    private companion object {
        const val AUDIO_BIT_RATE = 64_000
        const val AUDIO_SAMPLE_RATE = 44_100
    }
}

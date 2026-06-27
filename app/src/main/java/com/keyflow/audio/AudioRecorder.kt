package com.keyflow.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

class AudioRecorder(
    context: Context,
    private val outputDir: File = File(context.cacheDir, "keyflow-recordings"),
    private val minDurationMs: Long = 300L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

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
        startedAtMs = clock()
    }

    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        val durationMs = clock() - startedAtMs

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

    private fun clearState() {
        recorder = null
        outputFile = null
        startedAtMs = 0L
    }

    private companion object {
        const val AUDIO_BIT_RATE = 64_000
        const val AUDIO_SAMPLE_RATE = 44_100
    }
}

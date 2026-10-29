package com.keyflow.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Toca os buffers PCM de uma fala (FENG-04). O motor não conhece quem toca; a tela injeta um [SpeechPlayer]. */
interface SpeechPlayer {
    /** Escreve um buffer PCM 16-bit mono; bloqueia (fora da main) enquanto o buffer do sistema está cheio. */
    suspend fun write(pcm: ByteArray, sampleRate: Int)

    /** Volta quando tudo o que foi escrito já tocou. */
    suspend fun finish()

    /** Corta o som agora e libera. Pode ser chamado mais de uma vez. */
    fun stop()
}

/** `AudioTrack` em stream (FENG-04). Parar corta o som. */
class AudioTrackPlayer : SpeechPlayer {
    private var track: AudioTrack? = null
    private var frames = 0L

    override suspend fun write(pcm: ByteArray, sampleRate: Int) {
        if (pcm.isEmpty() || sampleRate <= 0) return
        withContext(Dispatchers.IO) {
            val t = track ?: create(sampleRate).also { track = it; it.play() }
            var offset = 0
            while (offset < pcm.size) {
                val n = t.write(pcm, offset, pcm.size - offset)
                if (n <= 0) break
                offset += n
            }
            frames += pcm.size / 2
        }
    }

    override suspend fun finish() {
        val t = track ?: return
        val total = frames.toInt()
        if (total <= 0) return
        suspendCancellableCoroutine { cont ->
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            })
            if (t.playbackHeadPosition >= total) cont.resume(Unit) else t.setNotificationMarkerPosition(total)
            t.stop() // em stream, o que já foi escrito termina de tocar
        }
    }

    override fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        t.release()
    }

    private fun create(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}

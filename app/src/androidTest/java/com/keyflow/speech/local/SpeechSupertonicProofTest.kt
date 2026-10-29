package com.keyflow.speech.local

import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.VoiceLicenseAcceptance
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prova opt-in exclusivamente no emulador. Prepara os pesos oficiais como fixture antes de medir a síntese;
 * o :speech recebe somente caminhos e continua sob StrictMode sem rede. Nenhum peso entra no APK ou na JVM.
 */
@RunWith(AndroidJUnit4::class)
class SpeechSupertonicProofTest {
    @Test fun fraseEmPortugues() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val classes = InstrumentationRegistry.getArguments().getString("class").orEmpty()
        assumeTrue(classes.split(',').any { it.endsWith("SpeechSupertonicProofTest#fraseEmPortugues") })
        assumeTrue("prova restrita ao emulador", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "supertonic-prova").apply { mkdirs() }
        val config = File(dir, "tts.json")
        val index = File(dir, "unicode_indexer.json")
        val acceptance = VoiceLicenseAcceptance(File(dir, "aceite-fixture.txt"))
        val spec = ModelCatalog.SUPERTONIC_3
        var verified = false
        var calls = 0
        var peak = 0L
        val connection = SpeechConnection(context, lingerMs = 0)
        val endpoint = object : SpeechEndpoint {
            private val delegate = AidlSpeechEndpoint(connection, spec.recommendedRamMb)
            override suspend fun speak(model: File, tokens: File, text: String, speed: Float,
                                       onPcm: (ByteArray, Int) -> Unit): SpeechOutcome {
                calls++
                val result = delegate.speak(model, tokens, text, speed, onPcm)
                if (result is SpeechOutcome.Ok) peak = result.peakRssKb
                return result
            }
        }
        val engine = LocalSpeechEngine(files = {
            SupertonicFiles.acceptedPair(config, index, acceptance.isAccepted(spec.id), verified)
        }, endpoint = endpoint)
        try {
            // Sem aceite/instalação, nem binder nem download. O aceite abaixo pertence só à fixture autorizada desta prova.
            File(dir, "aceite-fixture.txt").delete()
            assertEquals(SpeechError.MODEL_MISSING, (engine.speak(SpeechRequest("Olá", 1f)).toList().single() as SpeechEvent.Error).kind)
            assertEquals(0, calls)
            acceptance.accept(spec.id)
            val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()
            for (file in spec.files) {
                val target = File(dir, file.fileName)
                if (!target.isFile || target.length() != file.bytes || sha(target) != file.sha256) {
                    val part = File(dir, "${file.fileName}.part")
                    http.newCall(Request.Builder().url(file.url).build()).execute().use { response ->
                        check(response.isSuccessful) { "preparo ${file.fileName}: HTTP ${response.code}" }
                        response.body!!.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                    }
                    assertEquals(file.sha256, sha(part))
                    assertEquals(file.bytes, part.length())
                    check(part.renameTo(target))
                }
                status("Preparado ${file.fileName}: ${target.length()} bytes")
            }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            verified = true
            val start = System.nanoTime()
            val events = withTimeout(300_000) { engine.speak(SpeechRequest("Olá. Esta é a voz do KeyFlow.", 1f)).toList() }
            val wall = (System.nanoTime() - start) / 1_000_000
            assertTrue("erro de síntese: $events", events.none { it is SpeechEvent.Error })
            val audio = events.filterIsInstance<SpeechEvent.Audio>()
            val first = events.filterIsInstance<SpeechEvent.FirstAudio>().single()
            val done = events.filterIsInstance<SpeechEvent.Done>().single()
            val pcm = ByteArrayOutputStream().apply { audio.forEach { write(it.pcm) } }.toByteArray()
            assertTrue(pcm.size > 4000)
            assertEquals(44100, audio.first().sampleRate)
            assertTrue(peak > 0 && done.durationMs > 200)
            val wav = wav(pcm, 44100)
            File(context.cacheDir, "supertonic-prova.wav").writeBytes(wav)
            Base64.encodeToString(wav, Base64.NO_WRAP).chunked(32_768).forEachIndexed { i, chunk -> status("SUPERTONIC_WAV_$i:$chunk") }
            status("Supertonic: primeiroMs=${first.latencyMs}; duracaoMs=${done.durationMs}; paredeMs=$wall; rtf=${wall.toDouble() / done.durationMs}; picoRssKb=$peak; taxa=44100; amostras=${pcm.size / 2}; idioma=pt; speed=1.0")
        } finally {
            connection.close()
            // Somente os arquivos desta fixture, no cache do emulador; não toca modelos de outras provas.
            dir.deleteRecursively()
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun status(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
    private fun wav(pcm: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}

package com.keyflow.speech.local

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os dois P2 da revisão do motor local: o `:speech` morre no meio da frase, e o Job já está
 * cancelado quando o `speak` seria enviado. Os dois têm de falhar no código que só desliga o
 * binder e chama `speak` mesmo assim.
 */
class SpeechClientFaultTest {

    /** Serviço local. `queryLocalInterface` devolve esta instância: no JVM o `Binder` de verdade não gruda. */
    private class DyingService : ISpeechService.Stub() {
        var death: IBinder.DeathRecipient? = null
        val spoke = AtomicBoolean(false)
        val pcmBeforeDeath = AtomicInteger()
        override fun queryLocalInterface(descriptor: String): IInterface = this
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            death = recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
        override fun speak(
            modelPath: String,
            tokensPath: String,
            text: String,
            speed: Float,
            ramEstimateMb: Int,
            cb: ISpeechCallback,
        ) {
            spoke.set(true)
            cb.onPcm(byteArrayOf(1, 0, 0, 0), 22050)
            pcmBeforeDeath.incrementAndGet()
            // O processo morre aqui: nem onDone nem onError.
        }
        override fun cancel() = Unit
    }

    private fun files(): Pair<File, File> {
        val dir = File.createTempFile("speech", "").apply { delete(); mkdirs() }
        val model = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(1)) }
        val tokens = File(dir, "tokens.txt").apply { writeText("a 1\n") }
        return model to tokens
    }

    @Test
    fun morteNoMeioDaSinteseResumeComEngine() = runBlocking {
        val service = DyingService()
        var binder: IBinder? = null
        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.arateki.keyflow"
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                binder = service
                conn.onServiceConnected(ComponentName(packageName, "com.keyflow.speech.local.SpeechService"), service)
                return true
            }
            override fun unbindService(conn: ServiceConnection) = Unit
        }
        val connection = SpeechConnection(context, lingerMs = Long.MAX_VALUE)
        val endpoint = AidlSpeechEndpoint(connection, ramEstimateMb = 256)
        val (model, tokens) = files()
        val done = CompletableDeferred<SpeechOutcome>()
        val job = launch(Dispatchers.Unconfined) {
            try {
                done.complete(endpoint.speak(model, tokens, "olá", 1f) { _, _ -> })
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        try {
            assertTrue("o endpoint falso nem começou a síntese", service.spoke.get())
            assertEquals(1, service.pcmBeforeDeath.get())
            val death = service.death
            assertTrue("o bind não registrou o DeathRecipient", death != null && binder != null)
            death!!.binderDied()
            assertTrue(
                "a morte do :speech no meio da síntese não retomou a fala",
                done.isCompleted,
            )
            val outcome = done.await() as SpeechOutcome.Err
            assertEquals(SpeechProtocol.ENGINE, outcome.kind)
            val uses = SpeechConnection::class.java.getDeclaredField("uses").apply { isAccessible = true }.getInt(connection)
            assertEquals(0, uses)
        } finally {
            job.cancel()
            connection.close()
        }
    }

    @Test
    fun jobJaCanceladoAntesDoSpeakNaoSintetiza() = runBlocking {
        val voice = object : LocalVoice {
            var calls = 0
            override fun synthesize(
                model: File,
                tokens: File,
                text: String,
                speed: Float,
                cancelled: () -> Boolean,
                emit: (pcm: ByteArray, sampleRate: Int) -> Unit,
            ) {
                calls++
                if (cancelled()) return
                emit(byteArrayOf(1, 0), 22050)
            }
            override fun close() = Unit
        }
        val core = SpeechServiceCore(
            voice = voice,
            availableMb = { 1000L },
            scheduler = object : SpeechIdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit) = SpeechCancellable {}
            },
            executor = Executor { it.run() },
        )
        val speaks = AtomicInteger()
        val service = object : ISpeechService.Stub() {
            override fun queryLocalInterface(descriptor: String): IInterface = this
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
            override fun speak(
                modelPath: String,
                tokensPath: String,
                text: String,
                speed: Float,
                ramEstimateMb: Int,
                cb: ISpeechCallback,
            ) {
                speaks.incrementAndGet()
                core.speak(
                    SpeechJob(modelPath, tokensPath, text, speed, ramEstimateMb),
                    object : SpeechServiceCore.Listener {
                        override fun onPcm(pcm: ByteArray, sampleRate: Int) = cb.onPcm(pcm, sampleRate)
                        override fun onDone(peakRssKb: Long) = cb.onDone(peakRssKb)
                        override fun onError(kind: Int, detail: String) = cb.onError(kind, detail)
                    },
                )
            }
            override fun cancel() = core.cancel()
        }
        val connection = SpeechConnection(
            object : ContextWrapper(null) {
                override fun getPackageName(): String = "com.arateki.keyflow"
            },
            lingerMs = Long.MAX_VALUE,
        )
        SpeechConnection::class.java.getDeclaredField("service").apply {
            isAccessible = true
            set(connection, service)
        }
        val (model, tokens) = files()
        val endpoint = AidlSpeechEndpoint(connection, ramEstimateMb = 256)
        val parent = Job()
        val job = launch(parent + Dispatchers.Unconfined) {
            parent.cancel()
            endpoint.speak(model, tokens, "texto longo demais para esperar o VITS", 1f) { _, _ -> }
        }
        job.join()
        connection.close()
        assertEquals(
            "Job já cancelado: speak=${speaks.get()} sínteses=${voice.calls}",
            "0/0",
            "${speaks.get()}/${voice.calls}",
        )
    }
}

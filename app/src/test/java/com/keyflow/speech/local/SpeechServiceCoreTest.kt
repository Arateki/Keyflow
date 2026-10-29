package com.keyflow.speech.local

import android.os.IInterface
import android.os.RemoteException
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FLOC-04/05: sem arquivo não há sessão; o backend falso não abre rede; a ociosidade fecha a voz. */
class SpeechServiceCoreTest {

    private class FakeVoice : LocalVoice {
        var calls = 0
        var closed = 0
        var sockets = 0
        var sawCancelled = false
        var boom: Throwable? = null
        override fun synthesize(
            model: File,
            tokens: File,
            text: String,
            speed: Float,
            cancelled: () -> Boolean,
            emit: (pcm: ByteArray, sampleRate: Int) -> Unit,
        ) {
            calls++
            if (cancelled()) {
                sawCancelled = true
                return
            }
            boom?.let { throw it }
            emit(byteArrayOf(1, 0), 22050)
        }

        override fun close() {
            closed++
        }
    }

    private class Recorder : SpeechServiceCore.Listener {
        val pcm = ArrayList<ByteArray>()
        var done = 0
        var peak = -1L
        val errors = ArrayList<Int>()
        override fun onPcm(pcm: ByteArray, sampleRate: Int) {
            this.pcm += pcm
        }
        override fun onDone(peakRssKb: Long) {
            done++
            peak = peakRssKb
        }
        override fun onError(kind: Int, detail: String) {
            errors += kind
        }
    }

    private class FakeScheduler : SpeechIdleScheduler {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, block: () -> Unit): SpeechCancellable {
            val entry = delayMs to block
            pending += entry
            return SpeechCancellable { pending.remove(entry) }
        }
        fun fire() = pending.toList().forEach { it.second() }
    }

    private val direct = Executor { it.run() }

    private fun files(present: Boolean): Pair<File, File> {
        val dir = File.createTempFile("speech", "").apply { delete(); mkdirs() }
        val model = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        if (present) {
            model.writeBytes(byteArrayOf(1))
            tokens.writeText("a 1\n")
        }
        return model to tokens
    }

    private fun core(voice: LocalVoice, scheduler: FakeScheduler, available: Long? = 1000L, executor: Executor = direct) =
        SpeechServiceCore(
            voice = voice,
            availableMb = { available },
            scheduler = scheduler,
            executor = executor,
            peakRssKb = { 42L },
        )

    @Test
    fun arquivoAusenteNaoChamaOBackendNemAbreSocket() {
        val voice = FakeVoice()
        val (model, tokens) = files(false)
        val listener = Recorder()
        core(voice, FakeScheduler()).speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(listOf(SpeechProtocol.MODEL_MISSING), listener.errors)
        assertEquals(0, voice.calls)
        assertEquals(0, voice.sockets)
        assertEquals(0, listener.done)
    }

    @Test
    fun comArquivoEntregaPcmEFechaNaOciosidade() {
        val voice = FakeVoice()
        val scheduler = FakeScheduler()
        val (model, tokens) = files(true)
        val listener = Recorder()
        core(voice, scheduler).speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(1, voice.calls)
        assertEquals(0, voice.sockets)
        assertEquals(1, listener.pcm.size)
        assertEquals(1, listener.done)
        assertEquals(42L, listener.peak)
        assertEquals(SpeechProtocol.IDLE_UNLOAD_MS, scheduler.pending.single().first)
        scheduler.fire()
        assertEquals(1, voice.closed)
    }

    @Test
    fun ramInsuficienteNaoAbreSessao() {
        val voice = FakeVoice()
        val (model, tokens) = files(true)
        val listener = Recorder()
        core(voice, FakeScheduler(), available = 10L).speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(listOf(SpeechProtocol.NOT_ENOUGH_RAM), listener.errors)
        assertEquals(0, voice.calls)
    }

    @Test
    fun semMemoriaViraOutOfMemoryEFechaAVoz() {
        val voice = FakeVoice().apply { boom = OutOfMemoryError("voz") }
        val (model, tokens) = files(true)
        val listener = Recorder()
        core(voice, FakeScheduler()).speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(listOf(SpeechProtocol.OUT_OF_MEMORY), listener.errors)
        assertEquals(1, voice.closed)
        assertEquals(0, listener.done)
    }

    @Test
    fun canceladoNaFilaNaoRodaNemResponde() {
        val voice = FakeVoice()
        val queue = ArrayDeque<Runnable>()
        val (model, tokens) = files(true)
        val listener = Recorder()
        val core = core(voice, FakeScheduler(), executor = Executor { queue.add(it) })
        core.speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        core.cancel()
        queue.forEach { it.run() }
        assertEquals(0, voice.calls)
        assertTrue(listener.errors.isEmpty())
        assertEquals(0, listener.done)
    }

    @Test
    fun erroNativoViraEngineENaoEscapa() {
        val voice = FakeVoice().apply { boom = UnsatisfiedLinkError("libonnxruntime") }
        val (model, tokens) = files(true)
        val listener = Recorder()
        core(voice, FakeScheduler()).speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(listOf(SpeechProtocol.ENGINE), listener.errors)
        assertEquals(1, voice.closed)
        assertEquals(0, listener.done)
    }

    @Test
    fun remoteExceptionNoPedacoCancelaOResto() {
        val voice = object : LocalVoice {
            var calls = 0
            var emitted = 0
            override fun synthesize(
                model: File,
                tokens: File,
                text: String,
                speed: Float,
                cancelled: () -> Boolean,
                emit: (pcm: ByteArray, sampleRate: Int) -> Unit,
            ) {
                calls++
                emit(byteArrayOf(1, 0), 22050)
                emitted++
                if (cancelled()) return
                emit(byteArrayOf(2, 0), 22050)
                emitted++
            }
            override fun close() = Unit
        }
        val (model, tokens) = files(true)
        lateinit var service: SpeechServiceCore
        val callback = object : ISpeechCallback.Stub() {
            var done = 0
            override fun queryLocalInterface(descriptor: String): IInterface = this
            override fun onPcm(pcm: ByteArray, sampleRate: Int) {
                throw RemoteException("buffer cheio")
            }
            override fun onDone(peakRssKb: Long) {
                done++
            }
            override fun onError(kind: Int, message: String) = Unit
        }
        val listener = SpeechCallbackListener(callback) { service.cancel() }
        service = core(voice, FakeScheduler())
        service.speak(SpeechJob(model.path, tokens.path, "oi", 1f, 256), listener)
        assertEquals(1, voice.emitted)
        assertEquals(0, callback.done)
    }
}

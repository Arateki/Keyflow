package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.EngineType
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InferenceServiceCoreTest {
    private val parakeet = EngineModel("parakeet", EngineType.TRANSDUCER, "enc", "dec", "join", "tokens", null, 10)
    private val direct = Executor { it.run() }

    private class FakeEngine : SpeechEngine {
        var loaded: EngineModel? = null
        val events = mutableListOf<String>()
        var warm: () -> Unit = {}
        override fun isLoaded(model: EngineModel) = loaded == model
        override fun load(model: EngineModel) { loaded = model; events += "carga:${model.modelId}" }
        override fun unload() { loaded = null; events += "descarga" }
        override fun transcribe(samples: FloatArray): String {
            if (samples.size == 16_000 && samples.all { it == 0f }) {
                events += "aquecimento"
                warm()
                return "resultado descartável"
            }
            events += "real"
            return "fala real"
        }
    }

    private fun core(engine: FakeEngine, executor: Executor = direct, exists: Boolean = true, ram: Long = 1000) =
        InferenceServiceCore(engine, executor, { exists }, { ram })

    private fun preload(core: InferenceServiceCore, model: EngineModel = parakeet): Result<String> {
        var result: Result<String>? = null
        core.preload({ model }) { result = it }
        return checkNotNull(result)
    }

    @Test
    fun `pre carga aquece depois de carregar e descarta o texto`() {
        val engine = FakeEngine()
        assertThat(preload(core(engine)).getOrThrow()).isEmpty()
        assertThat(engine.events).containsExactly("descarga", "carga:parakeet", "aquecimento").inOrder()
    }

    @Test
    fun `cada carga aquece uma vez e a troca permite novo aquecimento`() {
        val engine = FakeEngine()
        val service = core(engine)
        preload(service)
        preload(service)
        preload(service, parakeet.copy(modelId = "outro"))
        preload(service, parakeet)
        assertThat(engine.events.count { it == "aquecimento" }).isEqualTo(3)
        assertThat(engine.events.count { it.startsWith("carga:") }).isEqualTo(3)
    }

    @Test
    fun `pedido real espera o aquecimento sem bloquear quem enviou nem misturar resultado`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val engine = FakeEngine().apply {
            warm = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val service = core(engine, executor)
        var result: Result<String>? = null
        try {
            service.preload({ parakeet }) { }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            service.transcribe({ parakeet }, { result = it; done.countDown() }) { recognizer, _ ->
                recognizer.transcribe(floatArrayOf(.5f))
            }
            assertThat(done.count).isEqualTo(1)
            release.countDown()
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(result!!.getOrThrow()).isEqualTo("fala real")
            assertThat(engine.events).containsExactly("descarga", "carga:parakeet", "aquecimento", "real").inOrder()
        } finally {
            release.countDown()
            service.shutdown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `falha descartavel inclusive OOM nao perde modelo nem impede pedido real`() {
        listOf<Throwable>(IllegalStateException("falha falsa"), OutOfMemoryError("OOM falso")).forEach { failure ->
            val engine = FakeEngine().apply { warm = { throw failure } }
            val service = core(engine)
            assertThat(preload(service).isSuccess).isTrue()
            assertThat(preload(service).isSuccess).isTrue()
            var result: Result<String>? = null
            service.transcribe({ parakeet }, { result = it }) { recognizer, _ -> recognizer.transcribe(floatArrayOf(.5f)) }
            assertThat(result!!.getOrThrow()).isEqualTo("fala real")
            assertThat(engine.loaded).isEqualTo(parakeet)
            assertThat(engine.events.count { it == "aquecimento" }).isEqualTo(1)
        }
    }

    @Test
    fun `carga solicitada por audio real nao acrescenta inferencia descartavel`() {
        val engine = FakeEngine()
        val service = core(engine)
        service.transcribe({ parakeet }, { assertThat(it.getOrThrow()).isEqualTo("fala real") }) { recognizer, _ ->
            recognizer.transcribe(floatArrayOf(.5f))
        }
        preload(service)
        assertThat(engine.events).containsExactly("descarga", "carga:parakeet", "real").inOrder()
    }

    @Test
    fun `whisper nao paga outro encoder com padding antes do audio real`() {
        val engine = FakeEngine()
        val service = core(engine)
        preload(service, parakeet.copy(modelId = "whisper-base-int8", engineType = EngineType.WHISPER))
        preload(service, parakeet.copy(modelId = "whisper-small-int8", engineType = EngineType.WHISPER))
        assertThat(engine.events).doesNotContain("aquecimento")
    }

    @Test
    fun `arquivo ausente e guarda de RAM impedem carga e aquecimento`() {
        listOf(false to 1000L, true to 0L).forEach { (exists, ram) ->
            val engine = FakeEngine()
            assertThat(preload(core(engine, exists = exists, ram = ram)).isFailure).isTrue()
            assertThat(engine.events.any { it.startsWith("carga:") || it == "aquecimento" }).isFalse()
        }
    }

    @Test
    fun `falha na carga nao aquece e pode tentar novamente`() {
        val fake = FakeEngine()
        var attempts = 0
        val engine = object : SpeechEngine by fake {
            override fun load(model: EngineModel) {
                if (attempts++ == 0) throw IllegalArgumentException("carga falsa")
                fake.load(model)
            }
        }
        val service = InferenceServiceCore(engine, direct, { true }, { 1000 })
        assertThat(preload(service).isFailure).isTrue()
        assertThat(fake.events).doesNotContain("aquecimento")
        assertThat(preload(service).isSuccess).isTrue()
        assertThat(fake.events.count { it == "aquecimento" }).isEqualTo(1)
    }
}

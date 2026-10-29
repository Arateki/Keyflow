package com.keyflow.reasoning.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.keyflow.reasoning.GenerationOptions
import java.io.File
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prova opt-in: baixa o peso oficial, portanto nunca roda na suíte normal. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalApi::class)
class LiteRtBenchmarkDeviceTest {
    @Test fun medeFlagEDeterminismo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("selecione explicitamente o método", InstrumentationRegistry.getArguments().getString("class").orEmpty()
            .split(',').any { it.endsWith("LiteRtBenchmarkDeviceTest#medeFlagEDeterminismo") })
        fun note(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBancadaLiteRt: $text\n") })
        val root = File(instrumentation.targetContext.filesDir, "bancada-qwen-prova").apply { mkdirs() }
        val file = File(root, "Qwen3-0.6B.litertlm")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            URL(URL_MODEL).openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }.getInputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            assertEquals(613722264L, file.length())
            assertEquals("c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", digest.digest().joinToString("") { "%02x".format(it) })
            val config = EngineConfig(modelPath = file.path, backend = Backend.CPU(threadCount = 2), cacheDir = ":nocache", maxNumTokens = 1024)
            ExperimentalFlags.enableBenchmark = false
            Engine(config).use { engine ->
                engine.initialize()
                val format = LiteRtConfigs.textFormat(engine)
                val cfg = LiteRtConfigs.conversation(null, format = format, options = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 32))
                engine.createConversation(cfg).use { conversation ->
                    assertTrue(conversation.sendMessage(LiteRtConfigs.user("Responda somente: oi", format)).toString().isNotBlank())
                    val noFlag = runCatching { conversation.getBenchmarkInfo() }
                    note("sem flag: ${noFlag.exceptionOrNull()?.javaClass?.simpleName ?: "métricas disponíveis"}")
                    assertTrue("a consulta sem flag deveria falhar", noFlag.isFailure)
                }
            }
            ExperimentalFlags.enableBenchmark = true
            Engine(config).use { engine ->
                engine.initialize()
                val format = LiteRtConfigs.textFormat(engine)
                val results = (1..3).map { n ->
                    val cfg = LiteRtConfigs.conversation(null, format = format,
                        options = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 32))
                    engine.createConversation(cfg).use { c ->
                        val response = c.sendMessage(LiteRtConfigs.user("Responda somente: oi", format)).toString()
                        val metrics = c.getBenchmarkInfo()
                        note("execução $n: resposta=$response; entrada=${metrics.lastPrefillTokenCount}; saída=${metrics.lastDecodeTokenCount}; taxa=${metrics.lastDecodeTokensPerSecond}")
                        response
                    }
                }
                assertTrue("resposta vazia", results.all { it.isNotBlank() })
                note("três saídas idênticas: ${results.distinct().size == 1}")
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
            root.deleteRecursively()
        }
    }
    companion object {
        private const val URL_MODEL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/fed81d17517e6ea64362e917ecf7e5accff12492/Qwen3-0.6B.litertlm"
    }
}

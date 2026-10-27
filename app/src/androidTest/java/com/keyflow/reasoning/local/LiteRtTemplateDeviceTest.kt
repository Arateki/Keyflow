package com.keyflow.reasoning.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in por método explícito: baixa 614 MB; a suíte comum não carrega pesos nem acessa a rede. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalApi::class)
class LiteRtTemplateDeviceTest {
    @Test fun qwenFed81d1PreservaSistemaEPedidoEGeraResposta() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("selecione explicitamente este método pelo pool", InstrumentationRegistry.getArguments()
            .getString("class").orEmpty().split(',').any { it.endsWith("LiteRtTemplateDeviceTest#qwenFed81d1PreservaSistemaEPedidoEGeraResposta") })
        val root = File(instrumentation.targetContext.filesDir, "qwen-template-e2e").apply { mkdirs() }
        fun note(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nQwenTemplate: $text\n") })
        val file = File(root, "Qwen3-0.6B.litertlm")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            URL(URL_MODEL).openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }.getInputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            assertEquals(613722264L, file.length())
            assertEquals("c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", digest.digest().joinToString("") { "%02x".format(it) })
            note("arquivo conferido; inicializando CPU")
            Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(threadCount = 2), cacheDir = ":nocache", maxNumTokens = 1024)).use { engine ->
                val loadStart = System.nanoTime()
                engine.initialize()
                note("inicialização ${(System.nanoTime() - loadStart) / 1_000_000} ms")
                val format = LiteRtConfigs.textFormat(engine)
                listOf(null, "Sistema de teste: responda em português.").forEach { system ->
                    engine.createConversation(LiteRtConfigs.conversation(system, format = format).copy(maxOutputToken = 96)).use { conversation ->
                        val raw = conversation.renderMessageIntoString(LiteRtConfigs.user(PROMPT))
                        note("uma parte preserva pedido=${raw.contains(PROMPT)}; formato=$format; render=$raw")
                        assertFalse("regressão deve expor a perda no formato anterior", raw.contains(PROMPT))
                        val rendered = conversation.renderMessageIntoString(LiteRtConfigs.user(PROMPT, format))
                        assertTrue("template perdeu o pedido: $rendered", rendered.contains(PROMPT))
                        if (system != null) assertTrue("template perdeu o sistema", conversation.renderPrefaceIntoString().contains(system))
                        val done = CountDownLatch(1)
                        val failure = AtomicReference<Throwable?>()
                        val text = StringBuilder()
                        val start = System.nanoTime()
                        var firstMs = -1L
                        conversation.sendMessageAsync(LiteRtConfigs.user(PROMPT, format), object : MessageCallback {
                            override fun onMessage(message: Message) {
                                if (firstMs < 0) firstMs = (System.nanoTime() - start) / 1_000_000
                                text.append(message.toString())
                            }
                            override fun onDone() = done.countDown()
                            override fun onError(throwable: Throwable) { failure.set(throwable); done.countDown() }
                        })
                        assertTrue("geração passou de cinco minutos", done.await(5, TimeUnit.MINUTES))
                        failure.get()?.let { throw AssertionError("falha nativa", it) }
                        note("sistema=${system != null}; primeiro=$firstMs ms; total=${(System.nanoTime() - start) / 1_000_000} ms; resposta=$text")
                        assertTrue("resposta vazia", text.isNotBlank())
                        assertTrue("modelo não respondeu ao texto ditado: $text", text.contains("amanhã", ignoreCase = true))
                    }
                }
                templatesPreservamTexto(engine, ::note)
            }
        } finally { root.deleteRecursively() }
    }
    /** Mesmo renderizador nativo, só troca Jinja; não afirma medir inferência dos modelos grandes. */
    private fun templatesPreservamTexto(engine: Engine, note: (String) -> Unit) {
        val previous = ExperimentalFlags.overwritePromptTemplate
        try {
            mapOf("qwen-legado" to LiteRtTextFormat.SINGLE, "gemma-e2b" to LiteRtTextFormat.SINGLE,
                "qwen3-4b" to LiteRtTextFormat.SINGLE, "qwen2p5" to LiteRtTextFormat.PARTS).forEach { (name, expected) ->
                ExperimentalFlags.overwritePromptTemplate = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("litert-templates/$name.jinja").bufferedReader().use { it.readText() }
                val format = LiteRtConfigs.textFormat(engine)
                assertEquals(name, expected, format)
                engine.createConversation(LiteRtConfigs.conversation("Sistema: ação", format = format)).use { conversation ->
                    val rendered = conversation.renderMessageIntoString(LiteRtConfigs.user(PROMPT, format))
                    assertTrue("$name perdeu o pedido", rendered.contains(PROMPT))
                    assertTrue("$name perdeu o sistema", conversation.renderPrefaceIntoString().contains("Sistema: ação"))
                }
                note("template $name: $format preserva sistema e pedido")
            }
        } finally { ExperimentalFlags.overwritePromptTemplate = previous }
    }
    companion object {
        private const val URL_MODEL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/fed81d17517e6ea64362e917ecf7e5accff12492/Qwen3-0.6B.litertlm"
        private const val PROMPT = "Corrija a pontuação e as maiúsculas deste texto ditado, sem mudar as palavras, e responda só com o texto corrigido: oi joão tudo bem amanhã a gente se vê às três da tarde no escritório pode ser"
    }
}

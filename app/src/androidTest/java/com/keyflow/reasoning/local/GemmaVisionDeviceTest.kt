package com.keyflow.reasoning.local

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.ComputeBackend
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.ui.config.VisionTestImage
import com.keyflow.vision.VisionImage
import com.keyflow.vision.LocalVisionEngine
import com.keyflow.vision.VisionResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in explícito: baixa 2,59 GB, valida SHA e roda o backend de produção na CPU. Fora da suíte comum e do CI. */
@RunWith(AndroidJUnit4::class)
class GemmaVisionDeviceTest {
    @Test fun e2bNaCpuLeAImagemDeExemplo() {
        val selected = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
        assumeTrue("selecione explicitamente o método pelo pool", selected.any {
            it.endsWith("GemmaVisionDeviceTest#e2bNaCpuLeAImagemDeExemplo")
        })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun note(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nGemmaVision: $message\n")
        })
        val root = File(context.filesDir, "gemma-visao-optin").apply {
            if (exists()) check(deleteRecursively())
            check(mkdirs())
        }
        val spec = ModelCatalog.GEMMA_4_E2B
        val source = checkNotNull(spec.file(FileRole.MODEL))
        val cachePrefix = "gemma-visao-prova-${android.os.Process.myPid()}"
        val file = File(root, "$cachePrefix.litertlm")
        val backend = LiteRtBackend(File(root, "cache").apply { mkdirs() }.path)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            note("ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}; RAM total=${memory.totalMem / 1048576} MB; livre=${memory.availMem / 1048576} MB; CPU; LiteRT-LM 0.16.1")
            assertTrue("modelo não cabe segundo a guarda normal", RamGuard.enoughFor(ComputeBackend.CPU,
                memory.availMem / 1048576, spec.recommendedRamMb / 2, memory.totalMem / 1048576))
            assertTrue("espaço insuficiente para a prova", root.usableSpace > source.bytes + 300_000_000L)
            val downloadStart = System.nanoTime()
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var announced = 0L
            URL(source.url).openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }.getInputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= source.bytes) { "download maior que o catálogo" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        if (received - announced >= 256_000_000) { announced = received; note("download $received/${source.bytes} bytes") }
                    }
                }
            }
            assertEquals(source.bytes, file.length())
            assertEquals(source.sha256, digest.digest().joinToString("") { "%02x".format(it) })
            note("arquivo conferido; download ${(System.nanoTime() - downloadStart) / 1_000_000} ms; inicializando encoder e modelo na CPU")
            val model = LlmModels.from(spec, root, backend = ComputeBackend.CPU).copy(modelPath = file.path, vision = true)
            val loadStart = System.nanoTime()
            backend.load(model)
            note("carga fria ${(System.nanoTime() - loadStart) / 1_000_000} ms")
            val bitmap = VisionTestImage.bitmap()
            val image = try { VisionImage.encode(bitmap) } finally { bitmap.recycle() }
            val prompt = "Qual é a palavra final da primeira linha da imagem? Responda somente com essa palavra."
            note("pergunta sintética=$prompt")
            val response = StringBuilder()
            val start = System.nanoTime()
            var firstMs = -1L
            val future = worker.submit {
                backend.generate(ReasoningRequest(user = prompt, image = image)) { delta ->
                    if (firstMs < 0) firstMs = (System.nanoTime() - start) / 1_000_000
                    response.append(delta)
                }
            }
            try { future.get(10, TimeUnit.MINUTES) } finally { if (!future.isDone) backend.cancel() }
            note("primeiro trecho=$firstMs ms; geração=${(System.nanoTime() - start) / 1_000_000} ms; resposta sintética=$response")
            assertTrue("resposta vazia", response.isNotBlank())
            assertTrue("não leu a palavra na imagem sintética: $response", response.toString().replace(" ", "").contains("KeyFlow", ignoreCase = true))
            // Confere também a composição de produção: arquivo de imagem, AIDL, guarda e StrictMode do :reasoning.
            backend.unload()
            val directCache = File(root, "cache")
            val cacheBytes = directCache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            note("cache da fase direta=$cacheBytes bytes; espaço livre antes=${root.usableSpace} bytes")
            assertTrue("não liberou o cache temporário da fase direta", directCache.deleteRecursively())
            note("espaço livre após liberar o cache da fase direta=${root.usableSpace} bytes")
            val connection = ReasoningConnection(context, lingerMs = 0)
            try {
                val transport = LocalReasoningEngine(spec.id, { model }, connection,
                    imageDirectory = File(context.cacheDir, "reasoning-images"))
                val ipcStart = System.nanoTime()
                val actual = runBlocking { withTimeout(300_000) { LocalVisionEngine(transport).ask(image, prompt) } }
                note("pipeline :reasoning com StrictMode de rede; total=${(System.nanoTime() - ipcStart) / 1_000_000} ms; resposta sintética=$actual")
                assertTrue("motor de produção não respondeu: $actual", actual is VisionResult.Text)
                assertTrue((actual as VisionResult.Text).text.replace(" ", "").contains("KeyFlow", ignoreCase = true))
            } finally { connection.close() }
        } finally {
            backend.cancel(); worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS)
            backend.unload(); root.deleteRecursively()
            // Só o cache de pesos desta prova (nome único), nunca os caches de modelos instalados pelo usuário.
            context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(cachePrefix) }.forEach { it.deleteRecursively() }
        }
    }
}

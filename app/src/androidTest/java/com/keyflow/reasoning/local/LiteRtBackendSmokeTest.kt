package com.keyflow.reasoning.local

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.HttpDownloadGateway
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * O LLM de verdade (RRUN-08), opt-in: argumento de instrumentação `llmModelE2E=true`. Baixa o modelo do catálogo pela
 * fila de downloads do app — o que também confere o tamanho e o SHA-256 do catálogo contra o arquivo real —, instala e
 * gera pelo `:reasoning` de verdade (LiteRT-LM, CPU). Roda em arm64 e x86_64 (as ABIs que a biblioteca publica).
 *
 * Outros argumentos (todos opcionais):
 *  - `llmModelId=<id do catálogo>`: o modelo de texto a provar (padrão: o Qwen3 0.6B). Os de ~1,7–2,6 GB não cabem no
 *    disco do emulador do pool pelo caminho do downloader (arquivo baixado + cópia da instalação): empurre-os direto para
 *    `files/models-llm-e2e/<id>/` com `adb exec-in run-as …` e escreva o `manifest.json` ao lado — com o modelo já
 *    instalado, o teste pula o download.
 *  - `llmTimeoutMs=<ms>`: tempo máximo de cada geração (padrão 300 000; o DeepSeek R1 "pensa" antes de responder).
 *  - `llmIgnoreRamGuard=true`: desliga a guarda de RAM do app (o `RamGuard` recusa carregar com menos RAM livre que a
 *    estimativa do catálogo). O emulador do pool tem 2,5 GB no total e ~0,7 GB livres: sem isto, os modelos de 1,7 GB ou mais
 *    nem chegam ao motor. Os pesos são mapeados em memória (páginas de arquivo, descartáveis), então carrega — mais devagar.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtBackendSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val spec = ModelCatalog.byId(args.getString("llmModelId") ?: ModelCatalog.QWEN3_0_6B.id)
        ?: error("llmModelId fora do catálogo: ${args.getString("llmModelId")}")
    private val timeoutMs = args.getString("llmTimeoutMs")?.toLong() ?: 300_000L
    private val ignoreRamGuard = args.getString("llmIgnoreRamGuard") == "true"
    private val store = ModelStore(File(context.filesDir, "models-llm-e2e"))
    private val connection = ReasoningConnection(context, lingerMs = 60_000)

    /** O modelo do catálogo, descarregado logo após cada geração; sem estimativa de RAM (0) se a guarda for ignorada. */
    private fun loadedModel() = LlmModels.from(spec, store.dirFor(spec.id)).let {
        if (ignoreRamGuard) it.copy(idleUnloadMs = 0, ramEstimateMb = 0) else it.copy(idleUnloadMs = 0)
    }

    private fun engine() = LocalReasoningEngine(
        modelId = spec.id,
        modelFor = { id -> if (id == spec.id) loadedModel() else null },
        connection = connection,
    )

    @Before
    fun modelInstalled() {
        assumeTrue(
            "opt-in: -Pandroid.testInstrumentationRunnerArguments.llmModelE2E=true",
            InstrumentationRegistry.getArguments().getString("llmModelE2E") == "true",
        )
        assumeTrue(
            "o LiteRT-LM só publica arm64-v8a e x86_64 (ABIs deste aparelho: ${Build.SUPPORTED_ABIS.toList()})",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" },
        )
        if (store.isInstalled(spec.id)) return
        val downloader = ModelDownloader(
            HttpDownloadGateway(File(context.filesDir, "model-downloads-llm-e2e.json")),
            File(context.getExternalFilesDir(null), "model-tmp-llm-e2e"),
            store,
            isMetered = { false },
        )
        assertEquals(StartResult.Started, downloader.start(spec, allowMetered = true))
        val deadline = System.currentTimeMillis() + 20 * 60_000
        while (true) {
            val state = downloader.snapshot(spec.id)?.state
            if (state == DownloadState.FILES_DONE) break
            assertTrue("download falhou: $state", state != DownloadState.FAILED)
            assertTrue("download passou de 20 min", System.currentTimeMillis() < deadline)
            Thread.sleep(1_000)
        }
        downloader.finish(spec.id)
        assertTrue("o modelo não instalou (SHA-256/tamanho do catálogo?)", store.isInstalled(spec.id))
    }

    @Test
    fun theRealModelAnswersInAFlowThroughTheReasoningProcess() = runBlocking {
        val started = System.nanoTime()
        var firstMs = -1L
        val events = withTimeout(timeoutMs) {
            engine().generate(ReasoningRequest(user = "Diga olá em uma frase curta."))
                .onEach { if (firstMs < 0 && it is ReasoningEvent.Delta) firstMs = (System.nanoTime() - started) / 1_000_000 }
                .toList()
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000
        val error = events.filterIsInstance<ReasoningEvent.Error>().firstOrNull()
        assertTrue("erro do motor real: $error", error == null)
        val text = events.filterIsInstance<ReasoningEvent.Delta>().joinToString("") { it.text }
        assertTrue("resposta vazia", text.isNotBlank())
        // O Testar e os futuros consumidores querem a resposta, não o raciocínio interno do modelo: sem `<think>` no texto.
        assertTrue("a resposta traz o raciocínio interno do modelo: ${text.take(120)}", !text.contains("<think>"))
        assertEquals(ReasoningEvent.Done, events.last())
        // Medição informativa (base da RSN-T19): `adb logcat -s LiteRtSmoke`.
        android.util.Log.i("LiteRtSmoke", "${spec.id} · ${Build.SUPPORTED_ABIS.first()} · 1º trecho ${firstMs} ms · total ${totalMs} ms · ${events.size} eventos · \"${text.take(160)}\"")
        // Vários trechos, não um bloco só: é streaming de verdade.
        assertTrue("só ${events.size} evento(s)", events.size > 2)
    }

    /** Review Focus 4 com o motor real: cancelar no meio para a geração e a seguinte roda. */
    @Test
    fun cancellingTheRealGenerationStopsItAndTheNextOneRuns() = runBlocking {
        val e = engine()
        withTimeout(timeoutMs) {
            e.generate(ReasoningRequest(user = "Escreva uma história longa sobre um teclado que aprende a ouvir.")).first()
        }
        val next = withTimeout(timeoutMs) { e.generate(ReasoningRequest(user = "Diga olá.")).toList() }
        assertNotNull(next.lastOrNull())
        assertEquals(ReasoningEvent.Done, next.last())
    }
}

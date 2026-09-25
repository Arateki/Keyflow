package com.keyflow.transcription.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.models.CompletionResult
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import com.keyflow.data.models.SystemDownloadGateway
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Ponta a ponta com o modelo real (opt-in: argumento de instrumentação `localModelE2E=true`, ligado no
 * CI): baixa o Whisper base do catálogo pelo DownloadManager, confere SHA, instala e transcreve fala
 * de verdade no `:inference`. Fixtures: `test_wavs` do sherpa-onnx (LibriSpeech, CC BY 4.0).
 *
 * O áudio longo (~50 s) repete as duas frases; sem o [WhisperChunker], o sherpa-onnx descartaria tudo
 * depois de 30 s e "heaven" apareceria uma vez só — medido no mesmo modelo com o sherpa-onnx 1.13.8.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineEndToEndTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val spec = ModelCatalog.WHISPER_BASE

    @Before
    fun modelInstalled() {
        assumeTrue(
            "opt-in: -Pandroid.testInstrumentationRunnerArguments.localModelE2E=true",
            InstrumentationRegistry.getArguments().getString("localModelE2E") == "true",
        )
        val store = ModelStore(storeDir(context))
        if (store.isInstalled(spec.id)) return
        val downloader = ModelDownloader(
            SystemDownloadGateway(context),
            File(context.getExternalFilesDir(null), "model-tmp-e2e"),
            store,
            isMetered = { false },
        )
        assertEquals(StartResult.Started, downloader.start(spec, allowMetered = true))
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (true) {
            val state = downloader.snapshot(spec.id)?.state
            if (state == DownloadState.FILES_DONE) break
            check(state != DownloadState.FAILED) { "download do ${spec.id} falhou" }
            check(System.currentTimeMillis() < deadline) { "download do ${spec.id} não terminou em 15 min" }
            Thread.sleep(1_000)
        }
        assertEquals(CompletionResult.Installed, downloader.finish(spec.id))
    }

    private fun transcribe(asset: String): String {
        val audio = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input -> audio.outputStream().use { input.copyTo(it) } }
        val model = EngineModel.from(spec, ModelStore(storeDir(context)).dirFor(spec.id), "en")
        InferenceClient(context).use { client ->
            val reply = client.call(300) { engine, cb -> engine.transcribe(model.toParcel(), audio.absolutePath, cb) }
            check(reply is EngineReply.Success) { "transcrição falhou: $reply" }
            return reply.text.lowercase()
        }
    }

    @Test
    fun shortSpeech() {
        val text = transcribe("audio/speech-en-short.m4a")

        assertTrue(text, "nightfall" in text && "yellow lamps" in text)
    }

    @Test
    fun speechLongerThanWhisperWindowIsTranscribedWhole() {
        val text = transcribe("audio/speech-en-long.m4a")

        assertEquals(text, 2, Regex("nightfall").findAll(text).count())
        assertEquals(text, 2, Regex("heaven").findAll(text).count())
    }

    companion object {
        private fun storeDir(context: android.content.Context) = File(context.filesDir, "models-e2e")

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            storeDir(context).deleteRecursively()
            File(context.getExternalFilesDir(null), "model-tmp-e2e").deleteRecursively()
        }
    }
}

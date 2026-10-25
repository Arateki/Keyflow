package com.keyflow.transcription.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.ActivityManager
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.models.CompletionResult
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import com.keyflow.data.models.HttpDownloadGateway
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Ponta a ponta com o modelo real (opt-in: argumento de instrumentação `localModelE2E=true`, ligado no
 * CI): baixa o Whisper base do catálogo pela fila de downloads do app, confere SHA, instala e transcreve fala
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
            HttpDownloadGateway(File(context.filesDir, "model-downloads-e2e.json")),
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
        val audio = copyAsset(asset)
        val model = EngineModel.from(spec, ModelStore(storeDir(context)).dirFor(spec.id), "en")
        InferenceClient(context).use { client ->
            val started = android.os.SystemClock.elapsedRealtime()
            val reply = client.call(300) { engine, cb -> engine.transcribe(model.toParcel(), audio.absolutePath, cb) }
            check(reply is EngineReply.Success) { "transcrição falhou: $reply" }
            // Só as fixtures deste teste: deixa texto e duração na evidência de `am instrument`.
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "\nE2E $asset ($elapsed ms): ${reply.text}\n")
            })
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

    /**
     * NAT-04/05: o `:inference` morre no meio de uma transcrição (o SO matou por memória, crash nativo)
     * → quem esperava recebe erro local, não fica pendurado; a próxima refaz bind e carga e funciona.
     */
    @Test
    fun inferenceProcessDyingMidTranscriptionFailsThatCallAndTheNextOneWorks() = runBlocking {
        val store = ModelStore(storeDir(context))
        val transcriber = LocalTranscriber(context, LocalModelResolver(store))
        val selection = EffectiveConfig.Local(spec.id, "en")
        val long = copyAsset("audio/speech-en-long.m4a")
        try {
            val inFlight = async(kotlinx.coroutines.Dispatchers.IO) { transcriber.transcribe(selection, long) }
            val pid = waitForInferencePid()
            // Conta a partir do processo SUBIR, não da chamada: medido no A32 (2026-09-28), 500 ms depois de
            // subir o `:inference` ainda não tinha recebido o pedido — morria antes, o sistema recriava o
            // serviço e a transcrição terminava bem no processo novo (4/4). A chamada longa dura ~33 s
            // (carga 2,3 s + decodificação 3 s + transcrição 28 s): 3 s depois, a morte cai no meio dela.
            delay(3_000)
            android.os.Process.killProcess(pid)

            assertEquals(LocalErrors.error(LocalErrors.PROCESS_DIED), inFlight.await())

            val again = transcriber.transcribe(selection, copyAsset("audio/speech-en-short.m4a"))
            assertTrue(again.toString(), again is TranscriptionResult.Success && "nightfall" in again.text.lowercase())
        } finally {
            transcriber.release()
        }
    }

    private fun copyAsset(asset: String): File {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun waitForInferencePid(): Int {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.processName == "${context.packageName}:inference" }
                ?.let { return it.pid }
            check(System.currentTimeMillis() < deadline) { ":inference não subiu em 30 s" }
            Thread.sleep(100)
        }
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

package com.keyflow.transcription.local

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelFile
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * NM-T9: o cliente de verdade contra o `:inference` de verdade, sem modelo grande — o modelo "instalado"
 * perde um arquivo depois da instalação, e o engine responde MODEL_MISSING pelo binder.
 */
@RunWith(AndroidJUnit4::class)
class LocalTranscriberDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "models-client-test-${System.nanoTime()}")
    private val store = ModelStore(root)
    private val spec = ModelSpec(
        id = "tiny", displayName = "Tiny", summary = "", languages = "",
        engineType = EngineType.WHISPER, acceptsLanguageHint = true, recommendedRamMb = 1,
        files = listOf(
            ModelFile(FileRole.ENCODER, "enc.onnx", "https://x/enc", "a".repeat(64), 3),
            ModelFile(FileRole.DECODER, "dec.onnx", "https://x/dec", "b".repeat(64), 3),
            ModelFile(FileRole.TOKENS, "tokens.txt", "https://x/tok", "c".repeat(64), 3),
        ),
    )
    private val transcriber = LocalTranscriber(context, LocalModelResolver(store) { if (it == spec.id) spec else null })
    private val selection = EffectiveConfig.Local("tiny", "pt")
    private val audio = File(context.cacheDir, "client-test.m4a").apply { writeBytes(ByteArray(10)) }

    @After
    fun cleanUp() {
        transcriber.release()
        root.deleteRecursively()
        audio.delete()
    }

    /** Instalado para o store (em memória), mas sem o encoder no disco — só o `:inference` percebe. */
    private fun installThenLoseTheEncoder() {
        val src = File(context.cacheDir, "client-test-src").apply { mkdirs() }
        spec.files.forEach { File(src, it.fileName).writeText("xyz") }
        store.install(spec, src)
        src.deleteRecursively()
        File(store.dirFor("tiny"), "enc.onnx").delete()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "não aconteceu em 15 s: $what" }
            Thread.sleep(100)
        }
    }

    private fun inferencePid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == "${context.packageName}:inference" }?.pid

    @Test
    fun selectedButNotInstalledIsConfigMissingWithoutBinding() = runBlocking {
        val result = transcriber.transcribe(selection, audio)

        assertEquals(ErrorKind.CONFIG_MISSING, (result as TranscriptionResult.Error).kind)
        assertFalse(transcriber.isBound)
    }

    @Test
    fun engineErrorsComeBackAsLocalErrorsThroughTheBinder() = runBlocking {
        installThenLoseTheEncoder()

        val result = transcriber.transcribe(selection, audio)

        assertEquals(LocalErrors.error(LocalErrors.message(EngineErrorKind.MODEL_MISSING)), result)
        assertTrue(transcriber.isBound)
    }

    @Test
    fun afterTheInferenceProcessDiesTheNextCallBindsAgain() = runBlocking {
        installThenLoseTheEncoder()
        transcriber.transcribe(selection, audio)
        val pid = checkNotNull(inferencePid()) { ":inference deveria estar de pé" }

        android.os.Process.killProcess(pid)
        waitUntil("o cliente notar a morte do :inference") { !transcriber.isBound }
        val result = transcriber.transcribe(selection, audio)

        assertEquals(ErrorKind.LOCAL, (result as TranscriptionResult.Error).kind)
        assertEquals(LocalErrors.message(EngineErrorKind.MODEL_MISSING), result.message)
        assertTrue(inferencePid() != null && inferencePid() != pid)
    }

    @Test
    fun releaseUnbinds() = runBlocking {
        installThenLoseTheEncoder()
        transcriber.transcribe(selection, audio)

        transcriber.release()

        assertFalse(transcriber.isBound)
    }
}

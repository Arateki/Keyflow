package com.keyflow.transcription.local

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.models.ModelCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * NM-T8: AIDL + binder de verdade, entre o processo do app (onde o teste roda) e o `:inference` — sem
 * modelo no disco, o caminho inteiro termina em MODEL_MISSING pelo callback.
 */
@RunWith(AndroidJUnit4::class)
class InferenceServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = InferenceClient(context)
    private val absent = EngineModel.from(ModelCatalog.WHISPER_BASE, File(context.filesDir, "no-such-model"), "pt")

    @After
    fun unbind() = client.close()

    @Test
    fun serviceRunsInItsOwnProcess() {
        client.engine

        assertNull("o binder deveria ser um proxy remoto", client.binder!!.queryLocalInterface(ITranscriptionEngine.DESCRIPTOR))
        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        val inference = processes.single { it.processName == "${context.packageName}:inference" }
        assertNotEquals(android.os.Process.myPid(), inference.pid)
    }

    @Test
    fun transcribeWithoutTheModelOnDiskReportsModelMissing() {
        val reply = client.call(30) { engine, cb -> engine.transcribe(absent.toParcel(), "/nowhere.m4a", cb) }

        assertTrue(reply is EngineReply.Error)
        assertEquals(EngineErrorKind.MODEL_MISSING, (reply as EngineReply.Error).kind)
    }

    @Test
    fun preloadWithoutTheModelOnDiskReportsModelMissing() {
        val reply = client.call(30) { engine, cb -> engine.preload(absent.toParcel(), cb) }

        assertEquals(EngineErrorKind.MODEL_MISSING, (reply as EngineReply.Error).kind)
    }
}

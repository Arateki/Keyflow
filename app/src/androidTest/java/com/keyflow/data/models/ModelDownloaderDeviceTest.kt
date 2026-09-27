package com.keyflow.data.models

import android.app.DownloadManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * NM-T6/T7 com o DownloadManager de verdade, baixando de um servidor HTTP no próprio aparelho. O host é
 * `localhost` porque o network security config do app libera texto puro só para ele — e o
 * DownloadManager aplica a política de quem pediu o download.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer().apply { start() }
    private val suffix = System.nanoTime()
    private val tmpRoot = File(context.getExternalFilesDir(null), "model-tmp-test-$suffix")
    private val store = ModelStore(File(context.filesDir, "models-test-$suffix"))
    private val gateway = SystemDownloadGateway(context)
    private val downloader = ModelDownloader(gateway, tmpRoot, store, isMetered = { false }, catalog = { specFor(it) })

    private var specs = mutableMapOf<String, ModelSpec>()

    private fun specFor(id: String) = specs[id]

    private fun oneFileSpec(id: String, bytes: Long, sha256: String = "0".repeat(64)): ModelSpec {
        val url = server.url("/$id.bin").newBuilder().host("localhost").build().toString()
        return ModelSpec(
            id = id, displayName = "Teste", summary = "", languages = "",
            engineType = EngineType.WHISPER, acceptsLanguageHint = true, recommendedRamMb = 1,
            files = listOf(ModelFile(FileRole.TOKENS, "$id.bin", url, sha256, bytes)),
        ).also { specs[id] = it }
    }

    @After
    fun cleanUp() {
        specs.keys.forEach { downloader.cancel(it) }
        tmpRoot.deleteRecursively()
        File(context.filesDir, "models-test-$suffix").deleteRecursively()
        server.shutdown()
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("não aconteceu em ${timeoutMs}ms: $what")
    }

    @Test
    fun smallFileDownloadsToTheModelTmpDir() {
        val body = ByteArray(4_096) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = oneFileSpec("small", body.size.toLong())

        assertEquals(StartResult.Started, downloader.start(spec, allowMetered = false))
        waitFor(60_000, "download concluir") { downloader.snapshot("small")?.state == DownloadState.FILES_DONE }

        assertEquals(body.size.toLong(), File(downloader.tmpDirFor("small"), "small.bin").length())
        assertEquals(body.size.toLong(), downloader.snapshot("small")!!.bytesDone)
    }

    @Test
    fun cancelMidDownloadRemovesTheRequestAndThePartialFile() {
        val size = 400_000
        // ~40 KB/s: o download fica em andamento tempo suficiente para ver progresso e cancelar.
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(size))).throttleBody(4_096, 100, TimeUnit.MILLISECONDS),
        )
        val spec = oneFileSpec("slow", size.toLong())

        assertEquals(StartResult.Started, downloader.start(spec, allowMetered = false))
        waitFor(60_000, "download andar") {
            downloader.snapshot("slow")?.let { it.state == DownloadState.RUNNING && it.bytesDone > 0 } == true
        }
        val requestId = downloader.readRecord("slow")!!.values.single()

        downloader.cancel("slow")

        assertNull(downloader.snapshot("slow"))
        assertFalse(File(tmpRoot, "slow").exists())
        assertTrue(gateway.query(listOf(requestId)).getValue(requestId).status == RowStatus.FAILED)
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun completedDownloadWithTheCatalogShaIsInstalled() {
        val body = ByteArray(8_192) { (it * 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = oneFileSpec("good", body.size.toLong(), sha(body))

        downloader.start(spec, allowMetered = false)
        waitFor(60_000, "download concluir") { downloader.snapshot("good")?.state == DownloadState.FILES_DONE }

        assertEquals(CompletionResult.Installed, downloader.finish("good"))
        assertTrue(store.isInstalled("good"))
        assertTrue(File(store.dirFor("good"), "good.bin").readBytes().contentEquals(body))
        assertFalse(File(tmpRoot, "good").exists())
        assertNull(downloader.activeModelId())
    }

    @Test
    fun completedDownloadWithAnotherShaIsDiscarded() {
        val body = ByteArray(8_192) { (it * 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = oneFileSpec("bad", body.size.toLong(), sha(body.copyOf().also { it[0] = 42 }))

        downloader.start(spec, allowMetered = false)
        waitFor(60_000, "download concluir") { downloader.snapshot("bad")?.state == DownloadState.FILES_DONE }
        val requestId = downloader.readRecord("bad")!!.values.single()

        assertEquals(CompletionResult.Failed(DownloadFailure.CORRUPTED), downloader.finish("bad"))
        assertFalse(store.isInstalled("bad"))
        assertFalse(File(tmpRoot, "bad").exists())
        assertEquals(DownloadFailure.CORRUPTED, downloader.failures.value["bad"])
        assertEquals(RowStatus.FAILED, gateway.query(listOf(requestId)).getValue(requestId).status)
    }

    /** O broadcast de conclusão chega ao receiver do manifest — é ele que instala com o app fechado. */
    @Test
    fun downloadCompleteReachesTheManifestReceiver() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).setPackage(context.packageName),
            0,
        )

        assertEquals(listOf(ModelDownloadReceiver::class.java.name), receivers.map { it.activityInfo.name })
        assertEquals("android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS", receivers.single().activityInfo.permission)
    }
}

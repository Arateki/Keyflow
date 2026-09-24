package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NM-T6: pré-checagens, agregação de progresso e o ciclo início → progresso → cancelamento. */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = ModelSpec(
        id = "fake",
        displayName = "Fake",
        summary = "",
        languages = "",
        engineType = EngineType.WHISPER,
        acceptsLanguageHint = true,
        recommendedRamMb = 1,
        files = listOf(
            ModelFile(FileRole.ENCODER, "enc.onnx", "https://x/enc.onnx", "a".repeat(64), 1_000),
            ModelFile(FileRole.DECODER, "dec.onnx", "https://x/dec.onnx", "b".repeat(64), 3_000),
            ModelFile(FileRole.TOKENS, "tokens.txt", "https://x/tokens.txt", "c".repeat(64), 100),
        ),
    )

    private class FakeGateway : DownloadGateway {
        var nextId = 1L
        val enqueued = mutableListOf<Triple<String, File, Boolean>>()
        val rows = mutableMapOf<Long, DownloadRow>()
        val removed = mutableListOf<Long>()

        override fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long {
            enqueued += Triple(url, destination, allowMetered)
            val id = nextId++
            rows[id] = DownloadRow(id, RowStatus.PENDING, 0)
            return id
        }

        override fun query(ids: List<Long>) = ids.associateWith { rows[it] ?: DownloadRow(it, RowStatus.FAILED, 0) }

        override fun remove(ids: List<Long>) {
            removed += ids
            ids.forEach { rows.remove(it) }
        }
    }

    private class FakeVolumes(var free: Long = Long.MAX_VALUE / 4, var same: Boolean = true) : VolumeStats {
        override fun freeBytes(dir: File) = free
        override fun sameVolume(a: File, b: File) = same
    }

    private val gateway = FakeGateway()
    private val volumes = FakeVolumes()
    private var metered = false

    private fun downloader(tmpRoot: File? = File(tmp.root, "model-tmp")) = ModelDownloader(
        gateway = gateway,
        tmpRoot = tmpRoot,
        store = ModelStore(File(tmp.root, "models")),
        isMetered = { metered },
        volumes = volumes,
        catalog = { if (it == spec.id) spec else null },
    )

    // ── política pura ──────────────────────────────────────────────────────────

    @Test
    fun `same volume needs room for the download and the installed copy`() {
        assertThat(DownloadPolicy.requiredBytes(1_000, sameVolume = true)).isEqualTo(2_200)
        assertThat(DownloadPolicy.requiredBytes(1_000, sameVolume = false)).isEqualTo(1_200)
    }

    @Test
    fun `aggregate counts finished files whole and partial ones by bytes`() {
        val progress = DownloadPolicy.aggregate(
            spec,
            mapOf(
                "enc.onnx" to DownloadRow(1, RowStatus.SUCCESSFUL, 1_000),
                "dec.onnx" to DownloadRow(2, RowStatus.RUNNING, 1_500),
                "tokens.txt" to DownloadRow(3, RowStatus.PENDING, 0),
            ),
        )

        assertThat(progress.state).isEqualTo(DownloadState.RUNNING)
        assertThat(progress.bytesDone).isEqualTo(2_500)
        assertThat(progress.fraction).isWithin(1e-6f).of(2_500f / 4_100)
    }

    @Test
    fun `aggregate states`() {
        fun state(vararg rows: DownloadRow) =
            DownloadPolicy.aggregate(spec, spec.files.map { it.fileName }.zip(rows).toMap()).state
        val ok = DownloadRow(0, RowStatus.SUCCESSFUL, 0)

        assertThat(state(ok, ok, ok)).isEqualTo(DownloadState.FILES_DONE)
        assertThat(state(ok, DownloadRow(0, RowStatus.FAILED, 0), ok)).isEqualTo(DownloadState.FAILED)
        assertThat(state(ok, DownloadRow(0, RowStatus.PAUSED, 0, PauseReason.WAITING_FOR_NETWORK), ok))
            .isEqualTo(DownloadState.WAITING_FOR_NETWORK)
        assertThat(state(ok, DownloadRow(0, RowStatus.PAUSED, 0, PauseReason.WAITING_FOR_WIFI), ok))
            .isEqualTo(DownloadState.WAITING_FOR_WIFI)
        assertThat(state(ok, DownloadRow(0, RowStatus.PENDING, 0), ok)).isEqualTo(DownloadState.QUEUED)
    }

    @Test
    fun `a request missing from the system counts as failed`() {
        assertThat(DownloadPolicy.aggregate(spec, emptyMap()).state).isEqualTo(DownloadState.FAILED)
    }

    // ── ciclo com o downloader ─────────────────────────────────────────────────

    @Test
    fun `start enqueues one request per file into the model tmp dir and records them`() {
        val downloader = downloader()

        assertThat(downloader.start(spec, allowMetered = false)).isEqualTo(StartResult.Started)

        assertThat(gateway.enqueued.map { it.second.name }).containsExactly("enc.onnx", "dec.onnx", "tokens.txt")
        assertThat(gateway.enqueued.map { it.second.parentFile!!.name }.toSet()).containsExactly("fake")
        assertThat(downloader.activeModelId()).isEqualTo("fake")
        assertThat(downloader.readRecord("fake")).containsExactly("enc.onnx", 1L, "dec.onnx", 2L, "tokens.txt", 3L)
    }

    @Test
    fun `only one download at a time`() {
        val downloader = downloader()
        downloader.start(spec, allowMetered = false)

        assertThat(downloader.start(spec.copy(id = "other"), allowMetered = false))
            .isEqualTo(StartResult.AnotherDownloadActive("fake"))
    }

    @Test
    fun `metered network asks first and then goes with the flag`() {
        metered = true
        val downloader = downloader()

        assertThat(downloader.start(spec, allowMetered = false)).isEqualTo(StartResult.NeedsMeteredConfirmation(4_100))
        assertThat(gateway.enqueued).isEmpty()

        assertThat(downloader.start(spec, allowMetered = true)).isEqualTo(StartResult.Started)
        assertThat(gateway.enqueued.all { it.third }).isTrue()
    }

    @Test
    fun `not enough space blocks before enqueuing`() {
        volumes.free = 5_000

        assertThat(downloader().start(spec, allowMetered = false)).isEqualTo(StartResult.NotEnoughSpace(9_020, 5_000))
        assertThat(gateway.enqueued).isEmpty()
    }

    @Test
    fun `without external storage there is nothing to download into`() {
        assertThat(downloader(tmpRoot = null).start(spec, allowMetered = false)).isEqualTo(StartResult.StorageUnavailable)
    }

    @Test
    fun `snapshot follows the system rows`() {
        val downloader = downloader()
        downloader.start(spec, allowMetered = false)
        gateway.rows[1] = DownloadRow(1, RowStatus.SUCCESSFUL, 1_000)
        gateway.rows[2] = DownloadRow(2, RowStatus.RUNNING, 500)

        val progress = downloader.snapshot("fake")!!

        assertThat(progress.bytesDone).isEqualTo(1_500)
        assertThat(progress.state).isEqualTo(DownloadState.RUNNING)
        assertThat(downloader.snapshot("other")).isNull()
    }

    @Test
    fun `cancel removes the requests and the partial files`() {
        val downloader = downloader()
        downloader.start(spec, allowMetered = false)
        File(tmp.root, "model-tmp/fake/enc.onnx").writeBytes(ByteArray(10))

        downloader.cancel("fake")

        assertThat(gateway.removed).containsExactly(1L, 2L, 3L)
        assertThat(File(tmp.root, "model-tmp/fake").exists()).isFalse()
        assertThat(downloader.activeModelId()).isNull()
        assertThat(downloader.snapshot("fake")).isNull()
    }

    @Test
    fun `a failing enqueue leaves nothing behind`() {
        val failing = object : DownloadGateway by gateway {
            var calls = 0
            override fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long {
                if (++calls == 2) error("DownloadManager recusou")
                return gateway.enqueue(url, destination, title, allowMetered)
            }
        }
        val downloader = ModelDownloader(
            failing, File(tmp.root, "model-tmp"), ModelStore(File(tmp.root, "models")), { false }, volumes,
            catalog = { spec },
        )

        runCatching { downloader.start(spec, allowMetered = false) }

        assertThat(gateway.removed).containsExactly(1L)
        assertThat(downloader.activeModelId()).isNull()
    }
}

package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DLM-03: instalação/deleção no disco. Só `java.io.File` — roda na JVM com pasta temporária. */
class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = ModelSpec(
        id = "fake-whisper",
        displayName = "Fake",
        summary = "",
        languages = "",
        engineType = EngineType.WHISPER,
        acceptsLanguageHint = true,
        recommendedRamMb = 1,
        files = listOf(
            ModelFile(FileRole.ENCODER, "enc.onnx", "https://x/enc.onnx", "a".repeat(64), 10),
            ModelFile(FileRole.DECODER, "dec.onnx", "https://x/dec.onnx", "b".repeat(64), 20),
            ModelFile(FileRole.TOKENS, "tokens.txt", "https://x/tokens.txt", "c".repeat(64), 5),
        ),
    )

    private fun downloaded(sizes: Map<String, Int> = mapOf("enc.onnx" to 10, "dec.onnx" to 20, "tokens.txt" to 5)): File {
        val dir = tmp.newFolder()
        sizes.forEach { (name, size) -> File(dir, name).writeBytes(ByteArray(size) { it.toByte() }) }
        return dir
    }

    private fun store() = ModelStore(File(tmp.root, "models"))

    @Test
    fun `install copies the files and marks the model installed`() {
        val store = store()

        store.install(spec, downloaded())

        assertThat(store.installedIds.value).containsExactly("fake-whisper")
        assertThat(store.fileFor(spec, FileRole.DECODER)!!.length()).isEqualTo(20)
        assertThat(File(store.dirFor(spec.id), ModelStore.MANIFEST).isFile).isTrue()
    }

    @Test
    fun `a new store instance sees what was installed before`() {
        store().install(spec, downloaded())

        assertThat(store().isInstalled("fake-whisper")).isTrue()
    }

    @Test
    fun `missing file refuses to install and leaves nothing behind`() {
        val store = store()

        assertThrows(IllegalArgumentException::class.java) {
            store.install(spec, downloaded(mapOf("enc.onnx" to 10, "tokens.txt" to 5)))
        }

        assertThat(store.installedIds.value).isEmpty()
        assertThat(File(tmp.root, "models").listFiles().orEmpty().map { it.name }).isEmpty()
    }

    @Test
    fun `size different from the catalog refuses to install`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().install(spec, downloaded(mapOf("enc.onnx" to 10, "dec.onnx" to 19, "tokens.txt" to 5)))
        }
    }

    /** Meio-instalado não conta: sem manifest, o diretório existe mas o modelo não. */
    @Test
    fun `directory without manifest is not installed`() {
        val root = File(tmp.root, "models")
        File(root, "fake-whisper").mkdirs()
        File(root, "fake-whisper/enc.onnx").writeBytes(ByteArray(10))

        assertThat(ModelStore(root).installedIds.value).isEmpty()
    }

    @Test
    fun `a file changed after install makes it not installed`() {
        val store = store()
        store.install(spec, downloaded())

        store.fileFor(spec, FileRole.ENCODER)!!.writeBytes(ByteArray(3))
        store.refresh()

        assertThat(store.isInstalled("fake-whisper")).isFalse()
    }

    @Test
    fun `leftover staging from an interrupted install is ignored and cleared by delete`() {
        val root = File(tmp.root, "models")
        File(root, ".fake-whisper.installing").mkdirs()
        val store = ModelStore(root)

        assertThat(store.installedIds.value).isEmpty()
        store.delete("fake-whisper")
        assertThat(File(root, ".fake-whisper.installing").exists()).isFalse()
    }

    @Test
    fun `delete removes the whole directory`() {
        val store = store()
        store.install(spec, downloaded())

        store.delete("fake-whisper")

        assertThat(store.installedIds.value).isEmpty()
        assertThat(store.dirFor("fake-whisper").exists()).isFalse()
    }

    @Test
    fun `reinstall replaces the previous copy`() {
        val store = store()
        store.install(spec, downloaded())
        File(store.dirFor(spec.id), "stale.bin").writeBytes(ByteArray(1))

        store.install(spec, downloaded())

        assertThat(File(store.dirFor(spec.id), "stale.bin").exists()).isFalse()
        assertThat(store.isInstalled("fake-whisper")).isTrue()
    }

    // ── catálogo que troca o arquivo de um modelo sem trocar o id ───────────────────────────────────────

    /** A mesma entrada com um SHA-256 novo (o catálogo trocou o pin do arquivo). */
    private val newer = spec.copy(files = spec.files.map { if (it.role == FileRole.DECODER) it.copy(sha256 = "d".repeat(64)) else it })

    @Test
    fun `an install whose manifest differs from the catalog is outdated, not installed`() {
        store().install(spec, downloaded())

        val store = ModelStore(File(tmp.root, "models")) { newer.takeIf { s -> s.id == it } }

        assertThat(store.isInstalled(spec.id)).isFalse()
        assertThat(store.installedIds.value).isEmpty()
        assertThat(store.outdatedIds.value).containsExactly(spec.id)
    }

    @Test
    fun `an install that matches the catalog is installed and not outdated`() {
        store().install(spec, downloaded())

        val store = ModelStore(File(tmp.root, "models")) { spec.takeIf { s -> s.id == it } }

        assertThat(store.isInstalled(spec.id)).isTrue()
        assertThat(store.outdatedIds.value).isEmpty()
    }

    @Test
    fun `a size change in the catalog also makes the install outdated`() {
        store().install(spec, downloaded())
        val bigger = spec.copy(files = spec.files.map { if (it.role == FileRole.TOKENS) it.copy(bytes = 6) else it })

        val store = ModelStore(File(tmp.root, "models")) { bigger.takeIf { s -> s.id == it } }

        assertThat(store.outdatedIds.value).containsExactly(spec.id)
    }

    @Test
    fun `refresh picks up a catalog that changed after the store was created`() {
        store().install(spec, downloaded())
        var current = spec
        val store = ModelStore(File(tmp.root, "models")) { current.takeIf { s -> s.id == it } }
        assertThat(store.isInstalled(spec.id)).isTrue()

        current = newer
        store.refresh()

        assertThat(store.isInstalled(spec.id)).isFalse()
        assertThat(store.outdatedIds.value).containsExactly(spec.id)
    }

    @Test
    fun `installing the new version replaces the outdated one`() {
        store().install(spec, downloaded())
        val store = ModelStore(File(tmp.root, "models")) { newer.takeIf { s -> s.id == it } }

        store.install(newer, downloaded())

        assertThat(store.isInstalled(spec.id)).isTrue()
        assertThat(store.outdatedIds.value).isEmpty()
    }

    @Test
    fun `discardOutdated deletes only an outdated install`() {
        store().install(spec, downloaded())
        val current = ModelStore(File(tmp.root, "models")) { spec.takeIf { s -> s.id == it } }
        assertThat(current.discardOutdated(spec.id)).isFalse()
        assertThat(current.dirFor(spec.id).exists()).isTrue()

        val outdated = ModelStore(File(tmp.root, "models")) { newer.takeIf { s -> s.id == it } }
        assertThat(outdated.discardOutdated(spec.id)).isTrue()
        assertThat(outdated.dirFor(spec.id).exists()).isFalse()
        assertThat(outdated.outdatedIds.value).isEmpty()
    }

    /** Quem instalou o Qwen3 0.6B antes de 2026-10-27 tem o manifest com o SHA e o tamanho do pin `a3c5d805…`. */
    @Test
    fun `the Qwen3 installed before the pin change shows up as outdated against the real catalog`() {
        val root = File(tmp.root, "models")
        val id = ModelCatalog.QWEN3_0_6B.id
        File(root, id).mkdirs()
        val oldBytes = 614_236_160L
        java.io.RandomAccessFile(File(root, "$id/Qwen3-0.6B.litertlm"), "rw").use { it.setLength(oldBytes) }
        File(root, "$id/${ModelStore.MANIFEST}").writeText(
            """{"id":"$id","files":[{"name":"Qwen3-0.6B.litertlm","sha256":"555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4","bytes":$oldBytes}]}""",
        )

        val store = ModelStore(root, ModelCatalog::byId)

        assertThat(store.isInstalled(id)).isFalse()
        assertThat(store.outdatedIds.value).containsExactly(id)
    }

    // ── achados da revisão do PR #43 ─────────────────────────────────────────────────────────────────────

    /** Um refresh que está no meio da leitura não pode publicar o estado antigo depois de uma instalação nova: instalar espera o refresh. */
    @Test
    fun `refresh and install are serialized, so a stale refresh cannot discard the new install`() {
        store().install(spec, downloaded())
        val captured = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val installDone = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val store = ModelStore(File(tmp.root, "models")) { id ->
            if (Thread.currentThread().name == "refresh-antigo") {
                captured.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "timeout" }
            }
            newer.takeIf { id == it.id }
        }
        val slow = Thread({ runCatching { store.refresh() }.onFailure { errors += it } }, "refresh-antigo").also { it.start() }
        assertThat(captured.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        val installer = Thread({ runCatching { store.install(newer, downloaded()) }.onFailure { errors += it }; installDone.countDown() }, "instalador")
            .also { it.start() }

        // Com o refresh no meio da leitura a instalação tem de esperar; sem o lock ela terminava aqui.
        assertThat(installDone.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse()
        resume.countDown()
        slow.join(5_000)
        installer.join(5_000)

        assertThat(errors).isEmpty()
        assertThat(store.isInstalled(spec.id)).isTrue()
        assertThat(store.outdatedIds.value).isEmpty()
        assertThat(store.discardOutdated(spec.id)).isFalse()
        assertThat(store.dirFor(spec.id).exists()).isTrue()
    }

    @Test
    fun `discardOutdated revalidates the disk and keeps an install that already matches the catalog`() {
        store().install(spec, downloaded())
        var current = newer
        val store = ModelStore(File(tmp.root, "models")) { current.takeIf { s -> s.id == it } }
        assertThat(store.outdatedIds.value).containsExactly(spec.id)

        // A instalação nova chega por fora do store (outro processo) e o estado publicado ainda diz "desatualizado".
        ModelStore(File(tmp.root, "models")).install(newer, downloaded())
        assertThat(store.outdatedIds.value).containsExactly(spec.id)

        assertThat(store.discardOutdated(spec.id)).isFalse()
        assertThat(store.dirFor(spec.id).exists()).isTrue()
        assertThat(store.isInstalled(spec.id)).isTrue()
    }

    @Test
    fun `an interrupted swap gives the previous install back`() {
        val root = File(tmp.root, "models")
        store().install(spec, downloaded())
        // O processo morreu depois de guardar a instalação antiga e antes de a nova entrar.
        check(File(root, spec.id).renameTo(File(root, ".${spec.id}.replaced")))

        assertThat(ModelStore(root).isInstalled(spec.id)).isTrue()
        assertThat(File(root, ".${spec.id}.replaced").exists()).isFalse()
    }

    @Test
    fun `delete also removes an outdated install`() {
        store().install(spec, downloaded())
        val store = ModelStore(File(tmp.root, "models")) { newer.takeIf { s -> s.id == it } }

        store.delete(spec.id)

        assertThat(store.dirFor(spec.id).exists()).isFalse()
        assertThat(store.outdatedIds.value).isEmpty()
    }

    // ── segunda revisão do PR #43 ───────────────────────────────────────────────────────────────────────

    /** Backup íntegro e destino parcial: a recuperação não pode apagar a única cópia boa. */
    @Test
    fun `an interrupted swap with a partial new install restores the previous one`() {
        val root = File(tmp.root, "models")
        store().install(spec, downloaded())
        val dir = File(root, spec.id)
        check(dir.renameTo(File(root, ".${spec.id}.replaced")))
        // O destino tem manifest de um arquivo maior do que o que está no disco.
        dir.mkdirs()
        File(dir, "enc.onnx").writeBytes(ByteArray(3))
        File(dir, ModelStore.MANIFEST).writeText(
            """{"id":"${spec.id}","files":[{"name":"enc.onnx","sha256":"${"a".repeat(64)}","bytes":10}]}""",
        )

        val recovered = ModelStore(root)

        assertThat(recovered.isInstalled(spec.id)).isTrue()
        assertThat(File(root, spec.id + "/dec.onnx").isFile).isTrue()
        assertThat(File(root, ".${spec.id}.replaced").exists()).isFalse()
    }

    @Test
    fun `an interrupted swap with a complete new install drops the stored copy`() {
        val root = File(tmp.root, "models")
        store().install(spec, downloaded())
        File(root, ".${spec.id}.replaced").mkdirs()
        File(root, ".${spec.id}.replaced/lixo.bin").writeBytes(ByteArray(1))

        assertThat(ModelStore(root).isInstalled(spec.id)).isTrue()
        assertThat(File(root, ".${spec.id}.replaced").exists()).isFalse()
    }

    /**
     * Janela entre aplicar um catálogo novo e o `refresh`: a cópia antiga já não conta como instalada (compara-se com o catálogo vigente a cada
     * chamada), e a identidade que o motor recebe é a do manifest instalado, não a do catálogo.
     */
    @Test
    fun `a new catalog invalidates the install at once and the version comes from the installed manifest`() {
        store().install(spec, downloaded())
        var current = spec
        val store = ModelStore(File(tmp.root, "models")) { current.takeIf { s -> s.id == it } }
        val before = store.installedVersion(spec.id)
        assertThat(store.isInstalled(spec.id)).isTrue()
        assertThat(before).contains("a".repeat(64))

        current = newer // catálogo novo aplicado, sem refresh ainda

        assertThat(store.isInstalled(spec.id)).isFalse()
        assertThat(store.installedVersion(spec.id)).isEqualTo(before)
        assertThat(store.installedVersion(spec.id)).doesNotContain("d".repeat(64))

        store.install(newer, downloaded())

        assertThat(store.isInstalled(spec.id)).isTrue()
        assertThat(store.installedVersion(spec.id)).isNotEqualTo(before)
        assertThat(store.installedVersion(spec.id)).contains("d".repeat(64))
        assertThat(ModelStore(File(tmp.root, "models")).installedVersion("nao-existe")).isNull()
    }
}

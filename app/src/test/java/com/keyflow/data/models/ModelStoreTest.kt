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
}

package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import com.keyflow.skins.SkinPackTestFixtures.read
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SKP-DOC-02 / SKP-T7: o pacote de exemplo do repositório (`skin-packs/exemplo-neon/`) passa pelo validador REAL — o mesmo que o app usa ao importar —
 * **sem nenhum aviso**, e o `.zip` que `scripts/build-skin-pack.sh` produz dele também. É a referência de quem cria uma skin: se o exemplo regride,
 * o formato ou o exemplo estão errados. (O desenho de teste dos SVGs, que precisa do Android, fica em `ExampleSkinPackImportTest`.)
 */
class ExampleSkinPackTest {

    // Os testes de unidade rodam com o diretório do módulo (`app/`) como diretório de trabalho.
    private val repo = File("..")
    private val packDir = File(repo, "skin-packs/exemplo-neon")

    /** Os arquivos do vocabulário da pasta (o `manifest.json` e os `*.svg` da raiz), como o script os empacota. */
    private fun packFiles(): List<File> {
        check(packDir.isDirectory) { "pacote de exemplo não encontrado em ${packDir.absolutePath}" }
        return packDir.listFiles { f -> f.isFile && (f.name == "manifest.json" || f.name.endsWith(".svg")) }!!.sortedBy { it.name }
    }

    private fun zipOfFolder(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (file in packFiles()) {
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertCleanImport(result: SkinPackReadResult) {
        assertThat(result.report.errors).isEmpty()
        assertThat(result.report.warnings).isEmpty()
        assertThat(result.report.installed).containsExactly("orbe: 4 estados, 45 frames", "teclado: tray + 4 faces").inOrder()
    }

    @Test
    fun `the example folder zipped in memory imports with no errors and no warnings`() {
        assertCleanImport(read(zipOfFolder()))
    }

    @Test
    fun `the example has every state animated and every keyboard piece`() {
        val ok = read(zipOfFolder()) as SkinPackReadResult.Ok
        val content = ok.content
        assertThat(content.orbFrames.mapValues { it.value.size }).containsExactly(
            OrbPackState.IDLE, 12,
            OrbPackState.LISTENING, 24,
            OrbPackState.PAUSED, 1,
            OrbPackState.TRANSCRIBING, 8,
        )
        assertThat(content.keyboardFaces.keys).containsExactlyElementsIn(KeyboardPart.entries)
        // Os estados com mais de um frame de fato mudam de um quadro para o outro (nada de frame repetido fingindo animação).
        for ((state, frames) in content.orbFrames) {
            if (frames.size > 1) {
                assertThat(frames.map { String(it.bytes) }.toSet()).hasSize(frames.size)
                println("exemplo ${state.key}: ${frames.size} frames, ${frames.sumOf { it.bytes.size } / frames.size} bytes em média")
            }
        }
        val manifest = content.manifest
        assertThat(manifest.name).isEqualTo("Neon Noturno")
        assertThat(manifest.orb.fpsOf(OrbPackState.LISTENING)).isEqualTo(24)
        assertThat(manifest.keyboard.slice).isNotNull()
    }

    @Test
    fun `the example sticks to what the renderer draws`() {
        for (file in packFiles().filter { it.name.endsWith(".svg") }) {
            val text = file.readText()
            // FORMAT.md §4: nada de filtro, blend, animação nem recurso externo; sempre com viewBox.
            for (banned in listOf("<filter", "filter=", "mix-blend-mode", "<animate", "<set ", "@keyframes", "<script", "<foreignObject", "<image", "href=")) {
                assertThat(text).doesNotContain(banned)
            }
            assertThat(text).contains("viewBox=\"")
            assertThat(file.length()).isLessThan(SkinPackReader.MAX_SVG_BYTES.toLong())
        }
    }

    @Test
    fun `the zip that build-skin-pack builds from the example imports with no warnings either`() {
        val script = File(repo, "scripts/build-skin-pack.sh")
        assumeTrue("script ausente", script.isFile)
        assumeTrue("sem bash", which("bash"))
        assumeTrue("sem zip nem python3", which("zip") || which("python3"))
        val out = File.createTempFile("exemplo-neon", ".zip").also { it.delete() }
        try {
            val process = ProcessBuilder("bash", script.absolutePath, packDir.absolutePath, out.absolutePath).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).isEqualTo(0)
            assertThat(log).contains("OK:")
            // `manifest.json` na RAIZ do zip, nenhuma pasta no meio.
            ZipFile(out).use { zip -> assertThat(zip.entries().asSequence().map { it.name }.toList()).contains("manifest.json") }
            assertCleanImport(read(out.readBytes()))
        } finally {
            out.delete()
        }
    }

    @Test
    fun `build-skin-pack leaves strangers out of the zip and refuses a folder without manifest`() {
        val script = File(repo, "scripts/build-skin-pack.sh")
        assumeTrue("script ausente", script.isFile)
        assumeTrue("sem bash", which("bash"))
        assumeTrue("sem zip nem python3", which("zip") || which("python3"))
        val dir = java.nio.file.Files.createTempDirectory("skin-").toFile()
        val out = File(dir, "saida/skin.zip")
        try {
            File(dir, "manifest.json").writeText("""{"schemaVersion":1,"name":"Mínima"}""")
            File(dir, "orb_idle_01.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>""")
            File(dir, "leia-me.txt").writeText("oi")
            File(dir, "sub").mkdirs()
            File(dir, "sub/outra.svg").writeText("x")
            val ok = ProcessBuilder("bash", script.absolutePath, dir.absolutePath, out.absolutePath).redirectErrorStream(true).start()
            val log = ok.inputStream.bufferedReader().readText()
            assertThat(ok.waitFor(60, TimeUnit.SECONDS)).isTrue()
            assertThat(ok.exitValue()).isEqualTo(0)
            assertThat(log).contains("Ficaram de fora do .zip")
            ZipFile(out).use { zip -> assertThat(zip.entries().asSequence().map { it.name }.toList()).containsExactly("manifest.json", "orb_idle_01.svg") }

            File(dir, "manifest.json").delete()
            val bad = ProcessBuilder("bash", script.absolutePath, dir.absolutePath, File(dir, "x.zip").absolutePath).redirectErrorStream(true).start()
            val badLog = bad.inputStream.bufferedReader().readText()
            assertThat(bad.waitFor(60, TimeUnit.SECONDS)).isTrue()
            assertThat(bad.exitValue()).isNotEqualTo(0)
            assertThat(badLog).contains("manifest.json")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun which(command: String): Boolean = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, command).canExecute() }
}

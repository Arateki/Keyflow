package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import com.keyflow.skins.SkinPackTestFixtures.read
import com.keyflow.skins.SkinPackTestFixtures.svg
import com.keyflow.skins.SkinPackTestFixtures.zip
import java.io.File
import org.junit.Test

/**
 * SKP-DOC-01: o `FORMAT.md` é o contrato com quem cria a skin, então ele não pode mentir. Este teste LÊ o documento e o amarra ao
 * código: o JSON de exemplo vira manifest, a árvore de arquivos vira um pacote que o leitor aceita sem aviso, e os limites e
 * padrões escritos nas tabelas são os das constantes. Se alguém muda o código (ou o texto) e esquece o outro, isto quebra.
 */
class SkinPackFormatDocTest {

    private val format: String by lazy {
        // Os testes de unidade rodam com o diretório do módulo (`app/`) como diretório de trabalho.
        File("../.specs/features/skin-packs/FORMAT.md").also { check(it.isFile) { "FORMAT.md não encontrado em ${it.absolutePath}" } }.readText()
    }

    /** O texto da seção "## <numero>." até a próxima seção de nível 2, com os espaços das tabelas colapsados em um. */
    private fun section(number: Int): String = rawSection(number).replace(Regex(" {2,}"), " ")

    private fun rawSection(number: Int): String {
        val start = format.indexOf("## $number.")
        check(start >= 0) { "seção $number ausente do FORMAT.md" }
        val end = format.indexOf("\n## ", start + 3).let { if (it < 0) format.length else it }
        return format.substring(start, end)
    }

    private fun codeBlocks(text: String, lang: String? = null): List<String> =
        Regex("```${lang ?: ""}[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()

    /** Cada nome do bloco de árvore do §1, com as faixas "a_01.svg … a_12.svg" expandidas. */
    private fun treeFileNames(): List<String> {
        val tree = codeBlocks(section(1)).single { "manifest.json" in it }
        val names = mutableListOf<String>()
        for (line in tree.lines()) {
            val range = Regex("""(orb_[a-z]+_)(\d+)\.svg\s*…\s*\1(\d+)\.svg""").find(line)
            if (range != null) {
                val (prefix, from, to) = range.destructured
                for (n in from.toInt()..to.toInt()) names += "$prefix${n.toString().padStart(from.length, '0')}.svg"
                continue
            }
            Regex("""[a-z_]+(?:_\d+)?\.(?:svg|json)""").find(line)?.let { names += it.value }
        }
        return names
    }

    @Test
    fun `the json example in the format is a valid manifest with no warnings`() {
        val example = codeBlocks(section(3), "json").single()
        val parsed = SkinPackManifest.parse(example)
        assertThat(parsed.errors).isEmpty()
        assertThat(parsed.warnings).isEmpty()
        val manifest = parsed.manifest!!
        assertThat(manifest.name).isEqualTo("Neon Noturno")
        assertThat(manifest.orb.fpsOf(OrbPackState.LISTENING)).isEqualTo(24)
        assertThat(manifest.keyboard.slice).isNotNull()
    }

    @Test
    fun `every file name in the package tree is a name the code understands`() {
        val names = treeFileNames()
        assertThat(names).contains("manifest.json")
        assertThat(names.filter { it.startsWith("orb_idle_") }).hasSize(12)
        assertThat(names.filter { it.startsWith("orb_listening_") }).hasSize(24)
        assertThat(names.filter { it.startsWith("orb_transcribing_") }).hasSize(8)
        for (name in names) assertThat(SkinPackNames.classify(name)).isNotInstanceOf(PackFileKind.Unknown::class.java)
    }

    @Test
    fun `the package tree of the format imports with no warnings at all`() {
        val manifest = codeBlocks(section(3), "json").single()
        val entries = treeFileNames().map { name -> name to (if (name == "manifest.json") manifest else svg()).toByteArray() }
        val result = read(zip(entries))
        assertThat(result.report.errors).isEmpty()
        assertThat(result.report.warnings).isEmpty()
        assertThat(result.report.installed).containsExactly("orbe: 4 estados, 45 frames", "teclado: tray + 4 faces").inOrder()
    }

    @Test
    fun `the state and keyboard file tables list exactly what the code knows`() {
        val files = section(2)
        for (state in OrbPackState.entries) assertThat(files).contains("| `${state.key}`")
        for (part in KeyboardPart.entries) assertThat(files).contains("`${part.fileName}`")
        assertThat(files).contains("`orb_<estado>_<frame>.svg`")
    }

    @Test
    fun `the limits written in the format are the limits in the code`() {
        val limits = section(5)
        assertThat(limits).contains("Zip ≤ ${SkinPackReader.MAX_ZIP_BYTES / (1024 * 1024)} MB")
        assertThat(limits).contains("≤ ${SkinPackReader.MAX_ENTRIES} entradas")
        assertThat(limits).contains("cada SVG ≤ ${SkinPackReader.MAX_SVG_BYTES / 1024} KB")
        assertThat(limits).contains("total descompactado ≤ ${SkinPackReader.MAX_TOTAL_BYTES / (1024 * 1024)} MB")
        val files = section(2)
        assertThat(files).contains("Máximo **${SkinPackReader.MAX_FRAMES_PER_STATE} frames por estado** e **${SkinPackReader.MAX_FRAMES_TOTAL} no total**")
        assertThat(files).contains("Acima de **${SkinPackReader.SMOOTH_FRAMES_PER_STATE} frames** num estado o import **avisa**")
    }

    @Test
    fun `the manifest table agrees with the defaults and ranges in the code`() {
        val table = section(3)
        val d = SkinPackManifest.DEFAULT_FPS
        assertThat(table).contains("| `orb.fps` | ${SkinPackManifest.MIN_FPS}–${SkinPackManifest.MAX_FPS}, `$d`")
        assertThat(table).contains("| `orb.states.<estado>.fps` | ${SkinPackManifest.MIN_FPS}–${SkinPackManifest.MAX_FPS}, herda `orb.fps`")
        assertThat(table).contains("Um `fps` acima de **${SkinPackManifest.MAX_SHOWN_FPS}** é aceito (até ${SkinPackManifest.MAX_FPS}), mas o import **avisa**")
        assertThat(table).contains("texto 1–${SkinPackManifest.MAX_NAME}, **obrigatório**")
        assertThat(table).contains("texto ≤ ${SkinPackManifest.MAX_AUTHOR}, opcional")
        assertThat(table).contains("`schemaVersion` | inteiro, **obrigatório** (`${SkinPackManifest.SCHEMA_VERSION}`)")
        val orb = OrbManifest()
        val kb = KeyboardManifest()
        assertThat(table).contains("`orb.centerInk` | `#RRGGBB` / `#AARRGGBB`, `${hex(orb.centerInk)}`")
        assertThat(table).contains("cor, `${hex(kb.ink)}`")
        assertThat(table).contains("cor **opaca**, `${hex(kb.popup)}`")
        assertThat(table).contains("cor, `${hex(kb.trayColor)}`")
    }

    @Test
    fun `the example manifest keys are exactly the known keys, so the table has no orphan`() {
        val table = section(3)
        for (field in listOf("schemaVersion", "name", "author", "orb.centerInk", "orb.fps", "orb.states.<estado>.fps", "keyboard.ink", "keyboard.popup", "keyboard.trayColor", "keyboard.navigationBarColor", "keyboard.slice")) {
            assertThat(table).contains("`$field`")
        }
    }

    /** `#RRGGBB` quando opaca, `#AARRGGBB` quando não — como o FORMAT.md escreve os padrões. */
    private fun hex(color: Int): String =
        if ((color ushr 24) == 0xFF) "#%06X".format(color and 0xFFFFFF) else "#%08X".format(color)
}

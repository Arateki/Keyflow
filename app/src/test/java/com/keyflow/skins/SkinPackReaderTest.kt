package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import com.keyflow.skins.SkinPackTestFixtures.MANIFEST
import com.keyflow.skins.SkinPackTestFixtures.fullOrb
import com.keyflow.skins.SkinPackTestFixtures.keyboardOnly
import com.keyflow.skins.SkinPackTestFixtures.orbOnly
import com.keyflow.skins.SkinPackTestFixtures.read
import com.keyflow.skins.SkinPackTestFixtures.svg
import com.keyflow.skins.SkinPackTestFixtures.zip
import com.keyflow.skins.SkinPackTestFixtures.zipOf
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/**
 * O leitor/validador do pacote (SKP-T2): estrutura, vocabulário, limites e relatório, com os casos de borda do spec.md. A
 * segurança (SKP-IMP-04) tem a sua própria seção em [SkinPackSecurityTest].
 */
class SkinPackReaderTest {

    private fun ok(bytes: ByteArray): SkinPackReadResult.Ok {
        val result = read(bytes)
        assertThat(result.report.errors).isEmpty()
        return result as SkinPackReadResult.Ok
    }

    private fun failed(bytes: ByteArray): SkinPackReadResult.Failed {
        val result = read(bytes)
        assertThat(result).isInstanceOf(SkinPackReadResult.Failed::class.java)
        assertThat(result.report.errors).isNotEmpty()
        return result as SkinPackReadResult.Failed
    }

    private fun frames(vararg names: String) = names.map { it to svg().toByteArray() }

    // ── pacotes válidos ──────────────────────────────────────────────────────

    @Test
    fun `a pack with only an orb is valid`() {
        val result = ok(zip(orbOnly(idleFrames = 3)))
        assertThat(result.content.hasOrb).isTrue()
        assertThat(result.content.hasKeyboard).isFalse()
        assertThat(result.content.manifest.name).isEqualTo("Teste")
        assertThat(result.content.orbFrames.keys).containsExactly(OrbPackState.IDLE)
        assertThat(result.content.orbFrames.getValue(OrbPackState.IDLE).map { it.fileName })
            .containsExactly("orb_idle_0001.svg", "orb_idle_0002.svg", "orb_idle_0003.svg").inOrder()
        assertThat(result.report.installed).containsExactly("orbe: 1 estado, 3 frames")
    }

    @Test
    fun `a pack with only a keyboard is valid`() {
        val result = ok(zip(keyboardOnly()))
        assertThat(result.content.hasKeyboard).isTrue()
        assertThat(result.content.hasOrb).isFalse()
        assertThat(result.report.installed).containsExactly("teclado: 1 face")
    }

    @Test
    fun `a pack with orb and keyboard lists both`() {
        val entries = orbOnly(2) + keyboardOnly().drop(1) + listOf("keyboard_tray.svg" to svg().toByteArray(), "keyboard_key_pressed.svg" to svg().toByteArray())
        val result = ok(zip(entries))
        assertThat(result.content.hasOrb).isTrue()
        assertThat(result.content.hasKeyboard).isTrue()
        assertThat(result.content.keyboardFaces.keys).containsExactly(KeyboardPart.KEY_NORMAL, KeyboardPart.TRAY, KeyboardPart.KEY_PRESSED)
        assertThat(result.report.installed).containsExactly("orbe: 1 estado, 2 frames", "teclado: tray + 2 faces").inOrder()
    }

    @Test
    fun `frames play in numeric order whatever the zip order or padding`() {
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_idle_10.svg", "orb_idle_2.svg", "orb_idle_001.svg")))
        assertThat(result.content.orbFrames.getValue(OrbPackState.IDLE).map { it.fileName })
            .containsExactly("orb_idle_0001.svg", "orb_idle_0002.svg", "orb_idle_0010.svg").inOrder()
    }

    @Test
    fun `installed content keeps the original svg bytes under canonical names`() {
        val original = svg(extra = "<!-- única -->")
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_7.svg" to original.toByteArray())))
        val frame = result.content.orbFrames.getValue(OrbPackState.IDLE).single()
        assertThat(frame.fileName).isEqualTo("orb_idle_0007.svg")
        assertThat(String(frame.bytes, Charsets.UTF_8)).isEqualTo(original)
    }

    // ── a pasta que envolve tudo ─────────────────────────────────────────────

    @Test
    fun `a single wrapper folder is stripped`() {
        val wrapped = (fullOrb() + keyboardOnly().drop(1)).map { (name, bytes) -> "minha-skin/$name" to bytes }
        val result = ok(zip(listOf("minha-skin/" to ByteArray(0)) + wrapped))
        assertThat(result.content.hasOrb).isTrue()
        assertThat(result.content.hasKeyboard).isTrue()
        assertThat(result.report.warnings).isEmpty()
    }

    @Test
    fun `macOS and Windows leftovers are ignored without a warning, even beside the wrapper folder`() {
        val wrapped = fullOrb().map { (name, bytes) -> "skin/$name" to bytes }
        val junk = listOf(
            "__MACOSX/skin/._manifest.json" to byteArrayOf(1, 2, 3),
            "__MACOSX/._skin" to byteArrayOf(1),
            "skin/.DS_Store" to byteArrayOf(1),
            "skin/Thumbs.db" to byteArrayOf(1),
            "skin/._orb_idle_01.svg" to byteArrayOf(1),
        )
        val result = ok(zip(wrapped + junk))
        assertThat(result.report.warnings).isEmpty()
        assertThat(result.content.hasOrb).isTrue()
    }

    @Test
    fun `files below the root are ignored with a warning`() {
        val result = ok(zip(orbOnly(1) + listOf("extras/orb_listening_01.svg" to svg().toByteArray(), "extras/leia-me.txt" to byteArrayOf(1))))
        assertThat(result.content.orbFrames.keys).containsExactly(OrbPackState.IDLE)
        assertThat(result.report.warnings.joinToString("\n")).contains("2 arquivo(s) dentro de subpastas")
    }

    @Test
    fun `two top level folders are not a wrapper`() {
        // Sem uma pasta única que envolva tudo, o manifest "não está na raiz": o pacote é recusado.
        val result = failed(zip(listOf("a/manifest.json" to MANIFEST.toByteArray(), "b/orb_idle_01.svg" to svg().toByteArray())))
        assertThat(result.report.errors.joinToString("\n")).contains("manifest.json")
    }

    // ── manifest ─────────────────────────────────────────────────────────────

    @Test
    fun `a zip without manifest is refused`() {
        val result = failed(zip(frames("orb_idle_01.svg")))
        assertThat(result.report.errors.joinToString("\n")).contains("não tem o manifest.json")
    }

    @Test
    fun `an invalid manifest installs nothing`() {
        val result = failed(zip(orbOnly(1, manifest = "{ isso não é json")))
        assertThat(result.report.errors.single()).contains("manifest.json não é um JSON válido")
    }

    @Test
    fun `a manifest from a newer format is refused with the update message`() {
        val result = failed(zip(orbOnly(1, manifest = """{"schemaVersion":3,"name":"Futura"}""")))
        assertThat(result.report.errors.single()).contains("atualize o KeyFlow")
    }

    @Test
    fun `manifest errors and file errors are reported together`() {
        val result = failed(zip(listOf("manifest.json" to """{"schemaVersion":1,"name":"","orb":{"fps":999}}""".toByteArray()) + frames("orb_listening_01.svg")))
        assertThat(result.report.errors.size).isAtLeast(3) // name, orb.fps e a falta do idle
    }

    @Test
    fun `a manifest in a non UTF-8 encoding is refused`() {
        val latin1 = """{"schemaVersion":1,"name":"Café"}""".replace("\\u00e9", "é").toByteArray(Charsets.ISO_8859_1)
        val result = failed(zip(listOf("manifest.json" to latin1) + frames("orb_idle_01.svg")))
        assertThat(result.report.errors.joinToString("\n")).contains("UTF-8")
    }

    @Test
    fun `a manifest section without files is only a warning`() {
        val result = ok(zip(orbOnly(1, manifest = """{"schemaVersion":1,"name":"X","keyboard":{"ink":"#000000"}}""")))
        assertThat(result.report.warnings.joinToString("\n")).contains("seção keyboard foi ignorada")
        val noOrb = ok(zip(keyboardOnly(manifest = """{"schemaVersion":1,"name":"X","orb":{"fps":10}}""")))
        assertThat(noOrb.report.warnings.joinToString("\n")).contains("seção orb foi ignorada")
    }

    @Test
    fun `an fps declared for a state without frames is ignored with a warning`() {
        val manifest = """{"schemaVersion":1,"name":"X","orb":{"states":{"listening":{"fps":30},"idle":{"fps":8}}}}"""
        val result = ok(zip(orbOnly(2, manifest = manifest)))
        assertThat(result.report.warnings.single { it.contains("orb.states.listening.fps") }).contains("usa os de idle")
        assertThat(result.report.warnings.none { it.contains("orb.states.idle.fps") }).isTrue()
        assertThat(result.content.manifest.orb.fpsOf(OrbPackState.IDLE)).isEqualTo(8)
    }

    @Test
    fun `a tray without any declared color warns that the navigation bar will be black`() {
        val withTray = keyboardOnly() + listOf("keyboard_tray.svg" to svg().toByteArray())
        assertThat(ok(zip(withTray)).report.warnings.joinToString("\n")).contains("navigationBarColor")
        val declared = keyboardOnly(manifest = """{"schemaVersion":1,"name":"X","keyboard":{"navigationBarColor":"#102030"}}""") + listOf("keyboard_tray.svg" to svg().toByteArray())
        assertThat(ok(zip(declared)).report.warnings).isEmpty()
    }

    // ── orbe ─────────────────────────────────────────────────────────────────

    @Test
    fun `an orb without idle is an error`() {
        val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_listening_01.svg", "orb_paused_01.svg")))
        assertThat(result.report.errors.single()).contains("falta o estado idle")
    }

    @Test
    fun `a keyboard without keyboard_key_normal is an error`() {
        val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("keyboard_tray.svg", "keyboard_modifier_normal.svg")))
        assertThat(result.report.errors.single()).contains("keyboard_key_normal.svg")
    }

    @Test
    fun `a pack with neither orb nor keyboard is an error`() {
        val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "leia-me.txt" to byteArrayOf(65))))
        assertThat(result.report.errors.single()).contains("não tem orbe nem teclado")
    }

    @Test
    fun `a hole in the numbering is a warning and the existing frames are kept`() {
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_idle_01.svg", "orb_listening_03.svg", "orb_listening_04.svg", "orb_listening_06.svg")))
        val warning = result.report.warnings.single { it.contains("listening") && it.contains("buracos") }
        assertThat(warning).contains("1, 2, 5")
        assertThat(result.content.orbFrames.getValue(OrbPackState.LISTENING).map { it.fileName })
            .containsExactly("orb_listening_0003.svg", "orb_listening_0004.svg", "orb_listening_0006.svg").inOrder()
    }

    @Test
    fun `holes lists are shortened`() {
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_idle_01.svg", "orb_paused_20.svg")))
        assertThat(result.report.warnings.single { it.contains("paused") && it.contains("buracos") }).contains("1, 2, 3, 4, 5 …")
    }

    @Test
    fun `an absurd frame number never builds a list of every missing number`() {
        // O nome do arquivo pode trazer 9 dígitos: o leitor listava `1..999999999` para achar os buracos e o import morria de OutOfMemoryError
        // (um zip malicioso derrubava o app). Agora só conta os buracos e lista os cinco primeiros.
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_idle_01.svg", "orb_paused_999999999.svg")))
        val warning = result.report.warnings.single { it.contains("paused") && it.contains("buracos") }
        assertThat(warning).contains("1, 2, 3, 4, 5 …")
        assertThat(result.content.orbFrames.getValue(OrbPackState.PAUSED)).hasSize(1)
    }

    @Test
    fun `a missing state warns that it will use idle`() {
        val result = ok(zip(orbOnly(1)))
        assertThat(result.report.warnings.single()).isEqualTo("orbe: sem frames de listening, paused, transcribing — esses estados usam os de idle.")
        val full = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames("orb_idle_01.svg", "orb_listening_01.svg", "orb_paused_01.svg", "orb_transcribing_01.svg")))
        assertThat(full.report.warnings).isEmpty()
        assertThat(full.report.installed).containsExactly("orbe: 4 estados, 4 frames")
    }

    @Test
    fun `the same frame written two ways is a duplicate error naming both files`() {
        val result = failed(zip(orbOnly(0) + frames("orb_idle_01.svg", "orb_idle_1.svg")))
        val error = result.report.errors.single()
        assertThat(error).contains("orb_idle_01.svg")
        assertThat(error).contains("orb_idle_1.svg")
        assertThat(error).contains("mesmo frame")
    }

    @Test
    fun `frame zero is an error`() {
        val result = failed(zip(orbOnly(1) + frames("orb_listening_00.svg")))
        assertThat(result.report.errors.single()).contains("orb_listening_00.svg")
    }

    private fun packOf(vararg frameNames: String) = zip(listOf("manifest.json" to MANIFEST.toByteArray()) + frames(*frameNames))

    private fun names(state: OrbPackState, count: Int) = (1..count).map { "orb_${state.key}_$it.svg" }

    private fun memoryWarnings(result: SkinPackReadResult.Ok) = result.report.warnings.filter { it.contains("de memória") }

    @Test
    fun `more than the frames per state is an error and exactly the limit is not`() {
        assertThat(SkinPackReader.MAX_FRAMES_PER_STATE).isEqualTo(240)
        val many = names(OrbPackState.IDLE, SkinPackReader.MAX_FRAMES_PER_STATE + 1)
        val result = failed(packOf(*many.toTypedArray()))
        assertThat(result.report.errors.single()).contains("241 frames; o máximo é 240")
        ok(packOf(*names(OrbPackState.IDLE, SkinPackReader.MAX_FRAMES_PER_STATE).toTypedArray()))
    }

    @Test
    fun `the old 64 frame limit is gone`() {
        val result = ok(packOf(*names(OrbPackState.IDLE, 65).toTypedArray()))
        assertThat(result.content.orbFrames.getValue(OrbPackState.IDLE)).hasSize(65)
    }

    @Test
    fun `a state that fits the 16 MB basic cache at 280 px has no memory warning, one that does not names its megabytes`() {
        // 53 frames a 280 px = 16 620 800 bytes (15,85 MB): cabe nos 16 MB. 54 = 16 934 400 (16,15 MB): não cabe.
        assertThat(memoryWarnings(ok(packOf(*names(OrbPackState.IDLE, 53).toTypedArray())))).isEmpty()
        val above = ok(packOf(*names(OrbPackState.IDLE, 54).toTypedArray()))
        val warning = memoryWarnings(above).single()
        assertThat(warning).contains("o estado idle (54 frames) usa ~17 MB de memória a 280 px")
        assertThat(warning).contains("acima dos 16 MB do cache básico")
        assertThat(warning).contains("considere menos frames")
        assertThat(above.content.orbFrames.getValue(OrbPackState.IDLE)).hasSize(54)
    }

    @Test
    fun `the memory warning is the bytes of the state at 280 px, rounded up`() {
        assertThat(memoryWarnings(ok(packOf(*names(OrbPackState.IDLE, 64).toTypedArray()))).single()).contains("usa ~20 MB de memória a 280 px") // 19,14 MB
        assertThat(memoryWarnings(ok(packOf(*names(OrbPackState.IDLE, 120).toTypedArray()))).single()).contains("usa ~36 MB de memória a 280 px") // 35,89 MB
        assertThat(memoryWarnings(ok(packOf(*names(OrbPackState.IDLE, 240).toTypedArray()))).single()).contains("usa ~72 MB de memória a 280 px") // 71,78 MB
    }

    @Test
    fun `the memory warning names every heavy state and only those`() {
        val result = ok(packOf(*(names(OrbPackState.IDLE, 64) + names(OrbPackState.LISTENING, 100) + names(OrbPackState.PAUSED, 48)).toTypedArray()))
        val warnings = memoryWarnings(result)
        assertThat(warnings).hasSize(2)
        assertThat(warnings.single { it.contains("idle") }).contains("(64 frames)")
        assertThat(warnings.single { it.contains("listening") }).contains("(100 frames)")
        assertThat(warnings.none { it.contains("paused") }).isTrue()
    }

    @Test
    fun `the largest legal state fits the absolute memory ceiling at the largest orb size`() {
        // O elo entre o limite de frames do formato e o teto absoluto do cache: se um mudar sem o outro, o import aceitaria um estado que a orbe nunca segura.
        assertThat(OrbMemoryPolicy.stateBytes(OrbMemoryPolicy.MAX_SIDE_PX, SkinPackReader.MAX_FRAMES_PER_STATE)).isAtMost(OrbMemoryPolicy.ABSOLUTE_MAX_BYTES)
        assertThat(OrbMemoryPolicy.stateBytes(OrbMemoryPolicy.MAX_SIDE_PX, SkinPackReader.MAX_FRAMES_PER_STATE + 1)).isGreaterThan(OrbMemoryPolicy.ABSOLUTE_MAX_BYTES)
    }

    @Test
    fun `more than the frames overall is an error and exactly the limit is not`() {
        assertThat(SkinPackReader.MAX_FRAMES_TOTAL).isEqualTo(480)
        val perState = SkinPackReader.MAX_FRAMES_TOTAL / OrbPackState.entries.size
        val atLimit = OrbPackState.entries.flatMap { names(it, perState) }
        ok(packOf(*atLimit.toTypedArray()))
        val over = atLimit + "orb_idle_${perState + 1}.svg"
        assertThat(failed(packOf(*over.toTypedArray())).report.errors.single()).contains("481 frames; o máximo é 480")
        // Dois estados no teto de 240 também fecham em 480.
        ok(packOf(*(names(OrbPackState.IDLE, 240) + names(OrbPackState.LISTENING, 240)).toTypedArray()))
    }

    // ── arquivos fora do vocabulário ─────────────────────────────────────────

    @Test
    fun `unknown files are ignored with one aggregated warning`() {
        val result = ok(zip(orbOnly(1) + listOf("capa.png" to byteArrayOf(1), "leia-me.txt" to byteArrayOf(2), "orb_idle_01.png" to byteArrayOf(3), "extra.svg" to svg().toByteArray())))
        val warning = result.report.warnings.single { it.startsWith("Arquivos fora do formato") }
        assertThat(warning).contains("capa.png")
        assertThat(warning).contains("e mais 1")
    }

    @Test
    fun `wrong case names are flagged as such`() {
        val result = ok(zip(orbOnly(1) + listOf("Orb_Listening_01.svg" to svg().toByteArray())))
        assertThat(result.report.warnings.single { it.contains("minúsculas") }).contains("Orb_Listening_01.svg")
        assertThat(result.content.orbFrames.keys).containsExactly(OrbPackState.IDLE)
    }

    @Test
    fun `reserved names are ignored with a warning`() {
        val result = ok(zip(orbOnly(1) + listOf("orb_error_01.svg" to svg().toByteArray(), "keyboard_fx_press_01.svg" to svg().toByteArray())))
        assertThat(result.report.warnings.single { it.contains("reservados") }).contains("orb_error_01.svg")
        assertThat(result.content.orbFrames.keys).containsExactly(OrbPackState.IDLE)
    }

    // ── conteúdo dos SVGs ────────────────────────────────────────────────────

    @Test
    fun `a filter is only a warning and the pack installs`() {
        val withFilter = svg(extra = """<defs><filter id="b"><feGaussianBlur stdDeviation="4"/></filter></defs>""")
        val result = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to withFilter.toByteArray())))
        val warning = result.report.warnings.single { it.contains("<filter>") }
        assertThat(warning).startsWith("orb_idle_01.svg:")
        assertThat(warning).contains("não tem efeito")
    }

    @Test
    fun `svg warnings are aggregated by kind across files`() {
        val withFilter = svg(extra = """<filter id="b"/>""").toByteArray()
        val withAnimation = svg(extra = """<circle r="3"><animate attributeName="r" from="1" to="9" dur="1s"/></circle>""").toByteArray()
        val entries = listOf("manifest.json" to MANIFEST.toByteArray()) +
            (1..6).map { "orb_idle_0$it.svg" to withFilter } + listOf("orb_listening_01.svg" to withAnimation)
        val warnings = ok(zip(entries)).report.warnings
        val filter = warnings.single { it.contains("<filter>") }
        assertThat(filter).startsWith("orb_idle_01.svg, orb_idle_02.svg, orb_idle_03.svg e mais 3:")
        assertThat(warnings.single { it.contains("animação SMIL") }).startsWith("orb_listening_01.svg:")
    }

    @Test
    fun `every kind of unsupported feature is detected`() {
        fun warnings(extra: String) = ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to svg(extra = extra).toByteArray())))
            .report.warnings.filter { it.startsWith("orb_idle_01.svg:") }
        assertThat(warnings("""<g style="filter:blur(4px)"/>""").single()).contains("<filter>")
        assertThat(warnings("""<g filter="url(#f)"/>""").single()).contains("<filter>")
        assertThat(warnings("""<g style="mix-blend-mode:screen"/>""").single()).contains("mix-blend-mode")
        assertThat(warnings("""<animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="2s"/>""").single()).contains("SMIL")
        assertThat(warnings("""<style>@keyframes spin { to { opacity: 0 } }</style>""").single()).contains("animação CSS")
        assertThat(warnings("""<style>.a { animation: spin 1s }</style>""").single()).contains("animação CSS")
        assertThat(warnings("""<font horiz-adv-x="1000"><font-face font-family="x"/></font>""").single()).contains("fontes SVG")
    }

    @Test
    fun `dangerous or malformed svg content is an error naming the file`() {
        fun error(extra: String, name: String = "orb_idle_01.svg") =
            failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), name to svg(extra = extra).toByteArray()))).report.errors

        assertThat(error("<script>alert(1)</script>").single()).startsWith("orb_idle_01.svg:")
        assertThat(error("<script>alert(1)</script>").single()).contains("<script>")
        assertThat(error("""<svg:script xmlns:svg="http://www.w3.org/2000/svg">x</svg:script>""").single()).contains("<script>")
        assertThat(error("<SCRIPT>x</SCRIPT>").single()).contains("<script>")
        assertThat(error("""<foreignObject width="10" height="10"><div/></foreignObject>""").single()).contains("<foreignObject>")
        assertThat(error("""<image href="http://exemplo.com/a.png" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<use xlink:href="file:///etc/passwd"/>""").single()).contains("referência externa")
        assertThat(error("""<image href="img/local.png" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<image href="//exemplo.com/a.png" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<use href=""/>""").single()).contains("referência externa")
        assertThat(error("""<a href="javascript:alert(1)"><rect width="1" height="1"/></a>""").single()).contains("referência externa")
        assertThat(error("""<image href="data:image/svg+xml;base64,PHN2Zy8+" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<image href="&#104;ttp://x.com/a.png" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<rect fill="url(http://x.com/g.svg#a)" width="1" height="1"/>""").single()).contains("referência externa")
        assertThat(error("""<style>.a{fill:url('file:///x')}</style>""").single()).contains("referência externa")
        assertThat(error("""<style>@import url(http://x.com/a.css);</style>""").size).isAtLeast(1)
        assertThat(error("""<style>@import "a.css";</style>""").single()).contains("folha de estilo")
    }

    @Test
    fun `doctype and entities are refused`() {
        val doctype = """<?xml version="1.0"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">""" + svg()
        val entity = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY a "x">]>""" + svg()
        val onlyEntity = """<!ENTITY a "x">""" + svg()
        for (content in listOf(doctype, entity, onlyEntity, doctype.replace("DOCTYPE", "doctype"))) {
            val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to content.toByteArray())))
            assertThat(result.report.errors.single()).contains("DOCTYPE")
        }
    }

    @Test
    fun `the safe references are accepted`() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII="
        val safe = svg(
            extra = """<defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/></linearGradient><clipPath id="c"><rect width="9" height="9"/></clipPath></defs>
                <use href="#g"/><use xlink:href="#c"/><rect fill="url(#g)" clip-path="url(#c)" width="1" height="1"/>
                <image width="2" height="2" xlink:href="data:image/png;base64,$png"/><image width="2" height="2" href="data:image/jpeg;base64,/9j/4AAQ"/>""",
        )
        ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to safe.toByteArray())))
    }

    @Test
    fun `an xml-stylesheet instruction is refused`() {
        val content = """<?xml-stylesheet href="a.css" type="text/css"?>""" + svg()
        val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to content.toByteArray())))
        assertThat(result.report.errors.joinToString("\n")).contains("folha de estilo")
    }

    @Test
    fun `an empty file, a non svg and a non UTF-8 file are errors`() {
        fun errors(bytes: ByteArray) = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to bytes))).report.errors.single()
        assertThat(errors(ByteArray(0))).contains("vazio")
        assertThat(errors("<html><body/></html>".toByteArray())).contains("não parece um SVG")
        assertThat(errors("isso não é xml".toByteArray())).contains("não parece um SVG")
        assertThat(errors(byteArrayOf(0x3C, 0x73, 0x76, 0x67, 0xFF.toByte(), 0xFE.toByte(), 0x3E))).contains("UTF-8")
    }

    @Test
    fun `a utf-8 byte order mark in an svg is fine`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + svg().toByteArray()
        ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to bytes)))
    }

    @Test
    fun `a svg over 512 KB is an error and names the file`() {
        val big = svg(extra = "<!--" + "x".repeat(SkinPackReader.MAX_SVG_BYTES) + "-->")
        val result = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to big.toByteArray())))
        assertThat(result.report.errors.single()).isEqualTo("orb_idle_01.svg: o arquivo passa de 512 KB.")
        // Exatamente no limite passa.
        val limit = ByteArray(SkinPackReader.MAX_SVG_BYTES)
        val header = svg(extra = "<!--").toByteArray()
        val footer = "-->".toByteArray()
        val padded = header + ByteArray(SkinPackReader.MAX_SVG_BYTES - header.size - footer.size) { 'x'.code.toByte() } + footer
        assertThat(padded.size).isEqualTo(limit.size)
        ok(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to padded)))
    }

    @Test
    fun `a manifest over 64 KB is an error`() {
        val big = """{"schemaVersion":1,"name":"X","author":"${"a".repeat(SkinPackReader.MAX_MANIFEST_BYTES)}"}"""
        val result = failed(zip(listOf("manifest.json" to big.toByteArray()) + frames("orb_idle_01.svg")))
        assertThat(result.report.errors.single()).contains("manifest.json passa de 64 KB")
    }

    // ── o .zip em si ─────────────────────────────────────────────────────────

    @Test
    fun `garbage and empty input are not a zip`() {
        for (bytes in listOf(ByteArray(0), "isto não é um zip".toByteArray(), Random(1).let { r -> ByteArray(4096).also { r.nextBytes(it) } })) {
            val result = read(bytes)
            assertThat(result).isInstanceOf(SkinPackReadResult.Failed::class.java)
            assertThat(result.report.errors.single()).contains("não parece ser um .zip")
        }
    }

    @Test
    fun `a truncated zip is reported as corrupted, not as an exception`() {
        val whole = zip(orbOnly(3))
        val cut = whole.copyOf(whole.size / 3)
        val result = read(cut)
        assertThat(result).isInstanceOf(SkinPackReadResult.Failed::class.java)
        assertThat(result.report.errors).isNotEmpty()
        // Cortes em qualquer ponto — dentro de dados, de cabeçalho ou exatamente numa fronteira de entrada — nunca viram pacote "ok".
        for (length in 1 until whole.size step 7) {
            val partial = read(whole.copyOf(length))
            assertThat(partial).isInstanceOf(SkinPackReadResult.Failed::class.java)
        }
    }

    @Test
    fun `the reader does not close the stream it was given`() {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(zip(orbOnly(1))) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        ok(zip(orbOnly(1)))
        SkinPackReader.read(stream)
        assertThat(closed).isFalse()
    }

    @Test
    fun `a zip with the same entry twice is an error`() {
        val one = SkinPackTestFixtures.localPart(zipOf("manifest.json" to MANIFEST, "orb_idle_01.svg" to svg()))
        val two = SkinPackTestFixtures.localPart(zipOf("orb_idle_01.svg" to svg()))
        val result = failed(one + two + SkinPackTestFixtures.EMPTY_END)
        assertThat(result.report.errors.joinToString("\n")).contains("orb_idle_01.svg mais de uma vez")
    }

    @Test
    fun `more than the entry limit is refused`() {
        assertThat(SkinPackReader.MAX_ENTRIES).isEqualTo(800)
        // `orbOnly(1)` já são 2 entradas (manifest e um frame): MAX-1 de lixo passam do limite; MAX-2 fecham nele.
        val many = zip(orbOnly(1) + (1..SkinPackReader.MAX_ENTRIES - 1).map { "lixo/$it.txt" to byteArrayOf(1) })
        assertThat(read(many).report.errors.single()).contains("mais de ${SkinPackReader.MAX_ENTRIES} arquivos")
        val exactly = zip(orbOnly(1) + (1..SkinPackReader.MAX_ENTRIES - 2).map { "lixo/$it.txt" to byteArrayOf(1) })
        assertThat(read(exactly).report.errors).isEmpty()
    }

    @Test
    fun `a pack at every limit at once still fits the entry limit with room for the junk files of a desktop zip`() {
        // O pior pacote legal: 480 frames, as 5 peças do teclado e o manifest (486 entradas) e ainda um `.DS_Store` e um `._` por frame.
        val frames = OrbPackState.entries.flatMap { state -> (1..SkinPackReader.MAX_FRAMES_TOTAL / OrbPackState.entries.size).map { "orb_${state.key}_$it.svg" } }
        val keyboard = listOf("keyboard_tray.svg", "keyboard_key_normal.svg", "keyboard_key_pressed.svg", "keyboard_modifier_normal.svg", "keyboard_modifier_pressed.svg")
        val entries = listOf("manifest.json" to MANIFEST.toByteArray()) + (frames + keyboard).map { it to svg().toByteArray() } +
            listOf(".DS_Store" to byteArrayOf(1)) + (1..SkinPackReader.MAX_ENTRIES - 487).map { "__MACOSX/._$it.svg" to byteArrayOf(1) }
        assertThat(entries.size).isEqualTo(SkinPackReader.MAX_ENTRIES)
        val result = read(zip(entries))
        assertThat(result.report.errors).isEmpty()
        assertThat(result.report.installed.first()).isEqualTo("orbe: 4 estados, 480 frames")
    }

    @Test
    fun `an incompressible zip over 25 MB is refused while reading`() {
        val random = ByteArray(1024 * 1024)
        Random(7).nextBytes(random)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(MANIFEST.toByteArray())
            zip.closeEntry()
            repeat(26) { i ->
                zip.putNextEntry(ZipEntry("lixo/$i.bin"))
                zip.write(random)
                zip.closeEntry()
            }
        }
        assertThat(out.size()).isGreaterThan(25 * 1024 * 1024)
        assertThat(read(out.toByteArray()).report.errors.single()).contains("passa de 25 MB")
    }

    @Test
    fun `the report is empty of installed lines when it fails`() {
        val result = failed(zip(listOf("manifest.json" to "{".toByteArray())))
        assertThat(result.report.installed).isEmpty()
        assertThat(result.report.ok).isFalse()
    }
}

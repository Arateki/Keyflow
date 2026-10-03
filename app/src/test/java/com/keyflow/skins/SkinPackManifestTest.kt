package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `manifest.json` (FORMAT.md §3): padrões, faixas, erros com o caminho do campo, avisos para o desconhecido. */
class SkinPackManifestTest {

    private fun parse(json: String) = SkinPackManifest.parse(json)

    private fun errorsOf(json: String): List<String> = parse(json).errors

    private fun ok(json: String): SkinPackManifest {
        val result = parse(json)
        assertThat(result.errors).isEmpty()
        return result.manifest!!
    }

    @Test
    fun `only schemaVersion and name are required and everything else has a default`() {
        val manifest = ok("""{"schemaVersion":1,"name":"Mínima"}""")
        assertThat(manifest.schemaVersion).isEqualTo(1)
        assertThat(manifest.name).isEqualTo("Mínima")
        assertThat(manifest.author).isNull()
        assertThat(manifest.orb.centerInk).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(manifest.orb.fps).isEqualTo(12)
        assertThat(manifest.orb.stateFps).isEmpty()
        assertThat(manifest.keyboard.ink).isEqualTo(0xFF1B1B1B.toInt())
        assertThat(manifest.keyboard.popup).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(manifest.keyboard.trayColor).isEqualTo(0x00000000)
        assertThat(manifest.keyboard.navigationBarColor).isEqualTo(0xFF000000.toInt())
        assertThat(manifest.keyboard.slice).isNull()
        assertThat(parse("""{"schemaVersion":1,"name":"Mínima"}""").warnings).isEmpty()
    }

    @Test
    fun `the full example of the format parses with every field`() {
        val manifest = ok(
            """{
              "schemaVersion": 1, "name": "Neon Noturno", "author": "Yan",
              "orb": { "centerInk": "#FFFFFF", "fps": 12, "states": { "listening": { "fps": 24 }, "transcribing": { "fps": 16 } } },
              "keyboard": { "ink": "#F2F2F2", "popup": "#1A1A22", "trayColor": "#0B0B10", "navigationBarColor": "#0B0B10",
                "slice": { "left": 24, "top": 24, "right": 24, "bottom": 24 } }
            }""",
        )
        assertThat(manifest.name).isEqualTo("Neon Noturno")
        assertThat(manifest.author).isEqualTo("Yan")
        assertThat(manifest.orb.fpsOf(OrbPackState.IDLE)).isEqualTo(12)
        assertThat(manifest.orb.fpsOf(OrbPackState.LISTENING)).isEqualTo(24)
        assertThat(manifest.orb.fpsOf(OrbPackState.TRANSCRIBING)).isEqualTo(16)
        assertThat(manifest.orb.fpsOf(OrbPackState.PAUSED)).isEqualTo(12)
        assertThat(manifest.keyboard.ink).isEqualTo(0xFFF2F2F2.toInt())
        assertThat(manifest.keyboard.popup).isEqualTo(0xFF1A1A22.toInt())
        assertThat(manifest.keyboard.trayColor).isEqualTo(0xFF0B0B10.toInt())
        assertThat(manifest.keyboard.navigationBarColor).isEqualTo(0xFF0B0B10.toInt())
        assertThat(manifest.keyboard.slice).isEqualTo(SliceInsets(24f, 24f, 24f, 24f))
        assertThat(manifest.keyboard.declaresTrayColor).isTrue()
        assertThat(manifest.keyboard.declaresNavigationBarColor).isTrue()
    }

    @Test
    fun `the navigation bar color defaults to the opaque tray color`() {
        val manifest = ok("""{"schemaVersion":1,"name":"X","keyboard":{"trayColor":"#80123456"}}""")
        assertThat(manifest.keyboard.trayColor).isEqualTo(0x80123456.toInt())
        assertThat(manifest.keyboard.navigationBarColor).isEqualTo(0xFF123456.toInt())
        assertThat(manifest.keyboard.declaresNavigationBarColor).isFalse()
    }

    @Test
    fun `colors accept RRGGBB and AARRGGBB in any case`() {
        val manifest = ok("""{"schemaVersion":1,"name":"X","orb":{"centerInk":"#aabbcc"},"keyboard":{"ink":"#80ffffff"}}""")
        assertThat(manifest.orb.centerInk).isEqualTo(0xFFAABBCC.toInt())
        assertThat(manifest.keyboard.ink).isEqualTo(0x80FFFFFF.toInt())
    }

    // ── schemaVersion ────────────────────────────────────────────────────────

    @Test
    fun `a newer schemaVersion is refused with the update message`() {
        val errors = errorsOf("""{"schemaVersion":2,"name":"X"}""")
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("atualize o KeyFlow")
        assertThat(parse("""{"schemaVersion":2,"name":"X"}""").manifest).isNull()
    }

    @Test
    fun `schemaVersion must be a positive integer`() {
        for (bad in listOf("""{"name":"X"}""", """{"schemaVersion":"1","name":"X"}""", """{"schemaVersion":1.5,"name":"X"}""", """{"schemaVersion":null,"name":"X"}""", """{"schemaVersion":true,"name":"X"}""")) {
            assertThat(errorsOf(bad).single()).contains("schemaVersion")
        }
        assertThat(errorsOf("""{"schemaVersion":0,"name":"X"}""").single()).contains("schemaVersion")
        assertThat(errorsOf("""{"schemaVersion":-3,"name":"X"}""").single()).contains("schemaVersion")
        assertThat(errorsOf("""{"schemaVersion":1.0,"name":"X"}""")).isEmpty()
    }

    // ── name e author ────────────────────────────────────────────────────────

    @Test
    fun `name is required and limited to forty characters`() {
        assertThat(errorsOf("""{"schemaVersion":1}""").single()).contains("name")
        assertThat(errorsOf("""{"schemaVersion":1,"name":""}""").single()).contains("name")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"   "}""").single()).contains("name")
        assertThat(errorsOf("""{"schemaVersion":1,"name":42}""").single()).contains("name")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"${"a".repeat(41)}"}""").single()).contains("41")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"${"a".repeat(40)}"}""")).isEmpty()
    }

    @Test
    fun `name length counts characters, not UTF-16 units`() {
        // 40 emojis = 80 unidades UTF-16, mas 40 caracteres para quem lê.
        assertThat(errorsOf("""{"schemaVersion":1,"name":"${"🎨".repeat(40)}"}""")).isEmpty()
        assertThat(errorsOf("""{"schemaVersion":1,"name":"${"🎨".repeat(41)}"}""")).isNotEmpty()
    }

    @Test
    fun `name is trimmed and cannot hold line breaks`() {
        assertThat(ok("""{"schemaVersion":1,"name":"  Neon  "}""").name).isEqualTo("Neon")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"a\nb"}""").single()).contains("name")
    }

    @Test
    fun `author is optional, trimmed and limited to sixty characters`() {
        assertThat(ok("""{"schemaVersion":1,"name":"X","author":"  Yan "}""").author).isEqualTo("Yan")
        assertThat(ok("""{"schemaVersion":1,"name":"X","author":""}""").author).isNull()
        assertThat(ok("""{"schemaVersion":1,"name":"X","author":null}""").author).isNull()
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","author":"${"a".repeat(61)}"}""").single()).contains("author")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","author":7}""").single()).contains("author")
    }

    // ── cores, fps, slice ────────────────────────────────────────────────────

    @Test
    fun `a bad color names the field`() {
        for (bad in listOf("red", "#FFF", "#GGGGGG", "FFFFFF", "#FFFFFFF", "#FFFFFFFFF", "", "rgb(0,0,0)")) {
            assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":{"centerInk":"$bad"}}""").single()).contains("orb.centerInk")
        }
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"ink":12}}""").single()).contains("keyboard.ink")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"trayColor":true}}""").single()).contains("keyboard.trayColor")
    }

    @Test
    fun `popup and navigationBarColor must be opaque`() {
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"popup":"#80FFFFFF"}}""").single()).contains("keyboard.popup")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"navigationBarColor":"#00112233"}}""").single()).contains("keyboard.navigationBarColor")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"popup":"#FFFFFFFF","navigationBarColor":"#FF112233"}}""")).isEmpty()
        // ink e centerInk podem ser translúcidas.
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"ink":"#80FFFFFF"},"orb":{"centerInk":"#00FFFFFF"}}""")).isEmpty()
    }

    @Test
    fun `fps is an integer from 1 to 120, globally and per state`() {
        assertThat(ok("""{"schemaVersion":1,"name":"X","orb":{"fps":1}}""").orb.fps).isEqualTo(1)
        assertThat(ok("""{"schemaVersion":1,"name":"X","orb":{"fps":60}}""").orb.fps).isEqualTo(60)
        assertThat(ok("""{"schemaVersion":1,"name":"X","orb":{"fps":120}}""").orb.fps).isEqualTo(120)
        assertThat(ok("""{"schemaVersion":1,"name":"X","orb":{"states":{"listening":{"fps":120}}}}""").orb.fpsOf(OrbPackState.LISTENING)).isEqualTo(120)
        for (bad in listOf("0", "121", "1000", "-5", "12.5", "\"12\"", "null", "true")) {
            val global = errorsOf("""{"schemaVersion":1,"name":"X","orb":{"fps":$bad}}""").single()
            assertThat(global).contains("orb.fps")
            assertThat(global).contains("de 1 a 120")
            assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":{"states":{"idle":{"fps":$bad}}}}""").single()).contains("orb.states.idle.fps")
        }
    }

    @Test
    fun `the limit written in the error is the limit of the code`() {
        assertThat(SkinPackManifest.MAX_FPS).isEqualTo(120)
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":{"fps":${SkinPackManifest.MAX_FPS + 1}}}""").single())
            .isEqualTo("manifest.json: orb.fps precisa ser um número inteiro de 1 a ${SkinPackManifest.MAX_FPS}.")
    }

    @Test
    fun `an fps above 90 is accepted with one warning that names every field, and up to 90 has none`() {
        for (fps in listOf(1, 12, 60, 90)) {
            assertThat(parse("""{"schemaVersion":1,"name":"X","orb":{"fps":$fps}}""").warnings).isEmpty()
        }
        val global = parse("""{"schemaVersion":1,"name":"X","orb":{"fps":91}}""")
        assertThat(global.errors).isEmpty()
        assertThat(global.warnings).hasSize(1)
        assertThat(global.warnings.single()).contains("orb.fps (91)")
        assertThat(global.warnings.single()).contains("acima de 90 quadros por segundo")
        assertThat(global.warnings.single()).contains("telas de 60/90 Hz não mostram tudo, só ajuda em telas de 120 Hz")

        val several = parse("""{"schemaVersion":1,"name":"X","orb":{"fps":24,"states":{"listening":{"fps":120},"transcribing":{"fps":100},"paused":{"fps":90}}}}""")
        assertThat(several.warnings).hasSize(1)
        assertThat(several.warnings.single()).contains("orb.states.listening.fps (120), orb.states.transcribing.fps (100)")
        assertThat(several.warnings.single()).doesNotContain("paused")
        assertThat(several.warnings.single()).doesNotContain("orb.fps (")
    }

    @Test
    fun `slice needs four non negative numbers`() {
        assertThat(ok("""{"schemaVersion":1,"name":"X","keyboard":{"slice":{"left":1.5,"top":0,"right":2,"bottom":3}}}""").keyboard.slice)
            .isEqualTo(SliceInsets(1.5f, 0f, 2f, 3f))
        for (bad in listOf("""{"left":1,"top":1,"right":1}""", """{"left":-1,"top":1,"right":1,"bottom":1}""", """{"left":"a","top":1,"right":1,"bottom":1}""", "5", "[1,2,3,4]", "null")) {
            assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":{"slice":$bad}}""").single()).contains("keyboard.slice")
        }
    }

    // ── desconhecidos ────────────────────────────────────────────────────────

    @Test
    fun `unknown fields are only warnings and the manifest still loads`() {
        val result = parse(
            """{"schemaVersion":1,"name":"X","versao":"2","orb":{"fps":10,"cor":"#FFF"},"keyboard":{"ink":"#000000","glow":true,"slice":{"left":1,"top":1,"right":1,"bottom":1,"extra":9}}}""",
        )
        assertThat(result.errors).isEmpty()
        assertThat(result.manifest).isNotNull()
        assertThat(result.warnings.joinToString("\n")).contains("versao")
        assertThat(result.warnings.joinToString("\n")).contains("orb.cor")
        assertThat(result.warnings.joinToString("\n")).contains("keyboard.glow")
        assertThat(result.warnings.joinToString("\n")).contains("keyboard.slice.extra")
        assertThat(result.warnings).hasSize(4)
    }

    @Test
    fun `reserved and unknown orb states warn differently and are ignored`() {
        val result = parse("""{"schemaVersion":1,"name":"X","orb":{"states":{"error":{"fps":5},"cancelling":{"fps":5},"sleeping":{"fps":5},"idle":{"fps":20}}}}""")
        assertThat(result.errors).isEmpty()
        assertThat(result.manifest!!.orb.stateFps).containsExactly(OrbPackState.IDLE, 20)
        assertThat(result.warnings).hasSize(3)
        assertThat(result.warnings.count { it.contains("reservado") }).isEqualTo(2)
        assertThat(result.warnings.count { it.contains("desconhecido") }).isEqualTo(1)
    }

    @Test
    fun `declared sections are reported so the reader can warn about empty ones`() {
        assertThat(parse("""{"schemaVersion":1,"name":"X"}""").declaredSections).isEmpty()
        assertThat(parse("""{"schemaVersion":1,"name":"X","orb":{}}""").declaredSections).containsExactly("orb")
        assertThat(parse("""{"schemaVersion":1,"name":"X","orb":{},"keyboard":{}}""").declaredSections).containsExactly("orb", "keyboard")
    }

    // ── JSON ruim ────────────────────────────────────────────────────────────

    @Test
    fun `invalid json and non object roots are errors, never exceptions`() {
        for (bad in listOf("", "isso não é json", "{", """{"schemaVersion":1,""", "[1,2]", "\"texto\"", "42", "null")) {
            val result = parse(bad)
            assertThat(result.manifest).isNull()
            assertThat(result.errors).isNotEmpty()
        }
    }

    @Test
    fun `a wrongly typed section is an error`() {
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":"x"}""").single()).contains("orb")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","keyboard":[1]}""").single()).contains("keyboard")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":{"states":3}}""").single()).contains("orb.states")
        assertThat(errorsOf("""{"schemaVersion":1,"name":"X","orb":{"states":{"idle":7}}}""").single()).contains("orb.states.idle")
    }

    @Test
    fun `a byte order mark in front is tolerated`() {
        assertThat(parse("﻿" + """{"schemaVersion":1,"name":"X"}""").errors).isEmpty()
    }

    @Test
    fun `deeply nested json is refused before the recursive parser can overflow the stack`() {
        val bomb = "[".repeat(50_000) + "]".repeat(50_000)
        val result = parse(bomb)
        assertThat(result.manifest).isNull()
        assertThat(result.errors.single()).contains("aninhados")
        val nestedInObject = """{"schemaVersion":1,"name":"X","a":""" + "[".repeat(40) + "]".repeat(40) + "}"
        assertThat(errorsOf(nestedInObject).single()).contains("aninhados")
    }

    @Test
    fun `brackets inside strings do not count as nesting`() {
        val name = "[".repeat(30)
        assertThat(ok("""{"schemaVersion":1,"name":"$name"}""").name).isEqualTo(name)
        assertThat(ok("""{"schemaVersion":1,"name":"a\"[[[[[[[[[[[[[[[[[[[[b"}""").name).contains("[")
    }

    @Test
    fun `several errors are reported together`() {
        val errors = errorsOf("""{"schemaVersion":1,"name":"","orb":{"fps":999,"centerInk":"azul"},"keyboard":{"popup":"#00000000"}}""")
        assertThat(errors).hasSize(4)
    }
}

package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import java.text.Normalizer
import org.junit.Test

/** Ids de skin de pacote (SKP-STO-02 e SKP-IMP-05): estáveis, ASCII, seguros como nome de pasta, sem tocar nos ids antigos. */
class SkinIdsTest {

    private val valid = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*-[0-9a-f]{8}""")

    @Test
    fun `the id comes from the name and is the same every time`() {
        val id = SkinIds.idFor("Neon Noturno")
        assertThat(id).matches(valid.pattern)
        assertThat(id).startsWith("neon-noturno-")
        assertThat(SkinIds.idFor("Neon Noturno")).isEqualTo(id)
    }

    @Test
    fun `case, outer spaces and composed accents do not change the id`() {
        val id = SkinIds.idFor("Café Açaí")
        assertThat(SkinIds.idFor("café açaí")).isEqualTo(id)
        assertThat(SkinIds.idFor("  CAFÉ AÇAÍ  ")).isEqualTo(id)
        val decomposed = Normalizer.normalize("Café Açaí", Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo("Café Açaí")
        assertThat(SkinIds.idFor(decomposed)).isEqualTo(id)
    }

    @Test
    fun `accents and symbols become a plain ascii slug`() {
        assertThat(SkinIds.idFor("Café Açaí")).startsWith("cafe-acai-")
        assertThat(SkinIds.idFor("¡Olá, Mundo!")).startsWith("ola-mundo-")
        assertThat(SkinIds.idFor("a___b---c   d")).startsWith("a-b-c-d-")
        assertThat(SkinIds.idFor("日本語のスキン").all { it.code < 128 }).isTrue()
    }

    @Test
    fun `a name with nothing usable still gets an id, and different names get different ones`() {
        val a = SkinIds.idFor("🎨🎨")
        val b = SkinIds.idFor("🌙")
        assertThat(a).startsWith("skin-")
        assertThat(b).startsWith("skin-")
        assertThat(a).isNotEqualTo(b)
        assertThat(SkinIds.idFor("日本語")).startsWith("skin-")
    }

    @Test
    fun `names that differ only by symbols share the slug but not the id`() {
        val a = SkinIds.idFor("Neon!")
        val b = SkinIds.idFor("Neon?")
        assertThat(a).startsWith("neon-")
        assertThat(b).startsWith("neon-")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `the slug is short and never ends with a dash`() {
        val id = SkinIds.idFor("A very long skin name that keeps going and going - and going")
        assertThat(id.length).isAtMost(24 + 1 + 8)
        assertThat(id).matches(valid.pattern)
        // O corte cai num `-`: ele não pode sobrar antes do hash (viraria `--`).
        val cutOnDash = SkinIds.idFor("abcdefghijklmnopqrstuvw-xyz")
        assertThat(cutOnDash).doesNotContain("--")
    }

    @Test
    fun `every generated id is a valid folder name`() {
        val names = listOf("", " ", "a", "..", "../../etc/passwd", "con", "NUL", "x".repeat(500), "\u0000", "\n", "pack:evil", "C:\\x", "%00", "a/b", "💥", "Ünïcödé", "İstanbul", "ǅ")
        for (name in names) {
            val id = SkinIds.idFor(name)
            assertThat(id).matches(valid.pattern)
            assertThat(SkinIds.packId(SkinIds.forPack(id))).isEqualTo(id)
        }
    }

    @Test
    fun `packId only accepts a pack prefix and a folder safe id`() {
        assertThat(SkinIds.packId("pack:neon-noturno-3fa91c2b")).isEqualTo("neon-noturno-3fa91c2b")
        assertThat(SkinIds.isPack("pack:x")).isTrue()
        for (bad in listOf(null, "", "pack:", "pack", "PACK:x", "pack:../../x", "pack:a/b", "pack:a\\b", "pack:A", "pack:a b", "pack:.", "pack:..", "pack:-x", "pack:a.b", "pack:" + "a".repeat(64), "pack:\u0000", "pack:é")) {
            assertThat(SkinIds.packId(bad)).isNull()
            assertThat(SkinIds.isPack(bad)).isFalse()
        }
        assertThat(SkinIds.packId("pack:" + "a".repeat(63))).isNotNull()
    }

    @Test
    fun `the ids of the built in skins are not packs and keep their meaning`() {
        for (legacy in listOf("system", "atelie", "aero", "glitch", "rosegold")) {
            assertThat(SkinIds.isPack(legacy)).isFalse()
            assertThat(SkinIds.packId(legacy)).isNull()
        }
        assertThat(SkinIds.forPack("abc-12345678")).isEqualTo("pack:abc-12345678")
    }
}

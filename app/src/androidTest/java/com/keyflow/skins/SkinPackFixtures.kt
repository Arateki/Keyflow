package com.keyflow.skins

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `.zip` e SVGs de teste para os instrumentados do pacote de skin (o equivalente do que o teste de unidade tem na JVM). */
internal object SkinPackFixtures {

    fun manifest(name: String = "Teste", extra: String = ""): String =
        """{"schemaVersion":1,"name":"${name.replace("\"", "\\\"")}"$extra}"""

    /** Um SVG quadrado de cor sólida [color] (sem `#`), com um quadradinho de [mark] no canto de baixo à direita. */
    fun svg(color: String = "FF0000", mark: String? = null, viewBox: String = "0 0 100 100"): String =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox"><rect width="100" height="100" fill="#$color"/>""" +
            (mark?.let { """<rect x="80" y="80" width="20" height="20" fill="#$it"/>""" } ?: "") + "</svg>"

    fun zip(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Um pacote com orbe (idle com [idleFrames] frames, mais os outros estados com 1) e, se [keyboard], as peças do teclado. */
    fun pack(
        name: String = "Teste",
        idleFrames: Int = 2,
        keyboard: Boolean = false,
        extraManifest: String = "",
        color: String = "FF0000",
    ): List<Pair<String, String>> = buildList {
        add("manifest.json" to manifest(name, extraManifest))
        for (i in 1..idleFrames) add("orb_idle_${i.toString().padStart(2, '0')}.svg" to svg(color))
        add("orb_listening_01.svg" to svg("00FF00"))
        if (keyboard) {
            add("keyboard_key_normal.svg" to svg("FFFFFF"))
            add("keyboard_tray.svg" to svg("101010"))
        }
    }

    /** Uma pasta de trabalho nova dentro do cache do app; o chamador apaga no fim. */
    fun tempRoot(parent: File): File = File(parent, "skins-test-${System.nanoTime()}").also { it.mkdirs() }
}

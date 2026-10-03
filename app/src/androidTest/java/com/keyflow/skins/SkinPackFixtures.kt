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

    /** Uma face de tecla realista: degradê, contorno, brilho e sombra de baixo, em 96×96 (para fatia de 24). */
    val realisticFace = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs>
        <linearGradient id="k" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#D5DCE6"/></linearGradient>
        <linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.9"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient></defs>
        <rect x="6" y="9" width="84" height="84" rx="20" fill="#0A1A33" fill-opacity="0.25"/>
        <rect x="5" y="5" width="86" height="84" rx="20" fill="url(#k)" stroke="#9FB0C8" stroke-width="2"/>
        <rect x="12" y="9" width="72" height="30" rx="14" fill="url(#s)"/></svg>"""

    /** Um pacote de teclado "de verdade" (as quatro faces e a bandeja em cor lisa), com fatia de 24. */
    fun realisticKeyboardPack(name: String): List<Pair<String, String>> = listOf(
        "manifest.json" to manifest(name, ""","keyboard":{"slice":{"left":24,"top":24,"right":24,"bottom":24},"trayColor":"#101010"}"""),
        "keyboard_key_normal.svg" to realisticFace,
        "keyboard_key_pressed.svg" to realisticFace.replace("#D5DCE6", "#B9C4D4"),
        "keyboard_modifier_normal.svg" to realisticFace.replace("#D5DCE6", "#C2CCDA"),
        "keyboard_modifier_pressed.svg" to realisticFace.replace("#D5DCE6", "#9FB0C8"),
    )

    /** Um quadro de orbe "de verdade" (viewBox 256): halo e corpo em degradê radial, [points] pontos em anel, um arco e um brilho. */
    fun realisticOrbFrame(phase: Int, points: Int): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><defs>""")
        append("""<radialGradient id="halo" cx="128" cy="128" r="128" gradientUnits="userSpaceOnUse"><stop offset="0.5" stop-color="#7EB8A8" stop-opacity="0.45"/><stop offset="1" stop-color="#7EB8A8" stop-opacity="0"/></radialGradient>""")
        append("""<radialGradient id="body" cx="110" cy="105" r="80" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#9AD0BF"/><stop offset="1" stop-color="#4E9683"/></radialGradient>""")
        append("""<linearGradient id="shine" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.8"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>""")
        append("""</defs><circle cx="128" cy="128" r="128" fill="url(#halo)"/>""")
        append("""<circle cx="128" cy="128" r="${70 + phase % 6}" fill="url(#body)"/>""")
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points + phase * 0.05
            append("""<circle cx="%.2f" cy="%.2f" r="%d" fill="#FFFFFF" fill-opacity="0.6"/>""".format(java.util.Locale.US, 128 + 100 * Math.cos(a), 128 + 100 * Math.sin(a), 2 + i % 3))
        }
        append("""<path d="M60 128 A68 68 0 0 1 196 128" fill="none" stroke="#FFFFFF" stroke-opacity="0.5" stroke-width="3" stroke-linecap="round"/>""")
        append("""<ellipse cx="105" cy="92" rx="34" ry="18" fill="url(#shine)"/></svg>""")
    }

    /** Um pacote só de orbe com 12 + 24 + 1 + 8 frames realistas ([points] pontos cada), 12 fps (24 em listening). */
    fun realisticOrbPack(name: String, points: Int = 24): List<Pair<String, String>> = buildList {
        add("manifest.json" to manifest(name, ""","orb":{"fps":12,"states":{"listening":{"fps":24}}}"""))
        for ((state, count) in listOf("idle" to 12, "listening" to 24, "paused" to 1, "transcribing" to 8)) {
            for (i in 1..count) add("orb_${state}_${i.toString().padStart(2, '0')}.svg" to realisticOrbFrame(i, points))
        }
    }
}

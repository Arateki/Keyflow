package com.keyflow.ui.gallery

import android.content.Context
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.config.SkinImportOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pacotes de skin de mentira para as rotas da galeria (SKP-T6): `.zip` montados em memória e instalados de verdade, por [SkinPackStore.import], numa
 * pasta própria da galeria (`cacheDir/gallery-skins`, nunca a `filesDir/skins` do app) — então as miniaturas dos cartões e o texto dos relatórios
 * são os que o import real produz. Independentes do pacote de exemplo do repositório de propósito: os goldens da galeria não mudam quando ele muda.
 */
internal object GallerySkinPacks {

    fun store(context: Context): SkinPackStore = SkinPackStore(File(context.cacheDir, "gallery-skins"))

    /** Instala os três pacotes (um com orbe e teclado, um só de teclado, um só de orbe) e devolve o `skinId` do primeiro. */
    fun install(context: Context): String {
        val store = store(context)
        val both = store.import(zip(aurora()).inputStream())
        check(both.installed) { both.report.errors.toString() }
        for (entries in listOf(onlyKeyboard(), onlyOrb())) {
            val result = store.import(zip(entries).inputStream())
            check(result.installed) { result.report.errors.toString() }
        }
        return both.skinId!!
    }

    /** O relatório de um import com tudo e com avisos: um frame com `<filter>`, um buraco na numeração e um arquivo de fora do formato. */
    fun warningOutcome(context: Context): SkinImportOutcome {
        val entries = aurora("Aurora").toMutableList()
        entries.removeAll { it.first == "orb_listening_01.svg" }
        entries += "orb_listening_01.svg" to orbFrame("#7C5CFF", "#18D2C0", filter = true)
        entries += "orb_listening_03.svg" to orbFrame("#9B83FF", "#18D2C0")
        entries += "leia-me.txt" to "Minha skin"
        val result = reportStore(context).import(zip(entries).inputStream())
        return SkinImportOutcome("aurora.zip", result, packName = "Aurora", hasOrb = true, hasKeyboard = true)
    }

    /** O relatório de um import recusado: um `<script>` num frame e um frame `00`. */
    fun errorOutcome(context: Context): SkinImportOutcome {
        val entries = listOf(
            "manifest.json" to """{"schemaVersion":1,"name":"Quebrada"}""",
            "orb_idle_01.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><script>alert(1)</script><circle cx="128" cy="128" r="90" fill="#7C5CFF"/></svg>""",
            "orb_idle_00.svg" to orbFrame("#7C5CFF", "#18D2C0"),
        )
        val result = reportStore(context).import(zip(entries).inputStream())
        return SkinImportOutcome("quebrada.zip", result)
    }

    /** Uma pasta sempre vazia: o relatório de uma reimportação diria "atualizada" na segunda captura, e o golden seria outro. */
    private fun reportStore(context: Context): SkinPackStore =
        SkinPackStore(File(context.cacheDir, "gallery-skins-report").also { it.deleteRecursively() })

    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(name: String, body: String = "") = """{"schemaVersion":1,"name":"$name","author":"Galeria"$body}"""

    private const val KEYBOARD_MANIFEST =
        ""","keyboard":{"ink":"#F4F1FF","popup":"#241B45","trayColor":"#120D26","navigationBarColor":"#120D26","slice":{"left":24,"top":24,"right":24,"bottom":24}}"""

    private fun aurora(name: String = "Aurora"): List<Pair<String, String>> = buildList {
        add("manifest.json" to manifest(name, KEYBOARD_MANIFEST + ""","orb":{"fps":12}"""))
        add("orb_idle_01.svg" to orbFrame("#7C5CFF", "#18D2C0"))
        add("orb_idle_02.svg" to orbFrame("#8B6DFF", "#22DCCA"))
        add("orb_listening_01.svg" to orbFrame("#FF5C93", "#FFC24D"))
        add("keyboard_tray.svg" to tray())
        add("keyboard_key_normal.svg" to face("#3A2D78", "#271D55", "#6F5DD6"))
        add("keyboard_key_pressed.svg" to face("#2A2058", "#1B1440", "#5646B0"))
    }

    private fun onlyKeyboard(): List<Pair<String, String>> = listOf(
        "manifest.json" to manifest("Papel", KEYBOARD_MANIFEST),
        "keyboard_key_normal.svg" to face("#F7F2E8", "#E4DCC8", "#B9AD8E"),
    )

    private fun onlyOrb(): List<Pair<String, String>> = listOf(
        "manifest.json" to manifest("Brasa"),
        "orb_idle_01.svg" to orbFrame("#FF7A3D", "#FFD166"),
    )

    /** Uma orbe de 256×256: halo, corpo em degradê, anel e brilho. [filter] mete um `<filter>` (que o app ignora) para o relatório ter um aviso. */
    private fun orbFrame(top: String, bottom: String, filter: Boolean = false): String = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">
<defs>
<radialGradient id="halo" cx="128" cy="128" r="128" gradientUnits="userSpaceOnUse"><stop offset="0.55" stop-color="$top" stop-opacity="0.45"/><stop offset="1" stop-color="$top" stop-opacity="0"/></radialGradient>
<linearGradient id="body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="$top"/><stop offset="1" stop-color="$bottom"/></linearGradient>
<linearGradient id="shine" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.75"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
${if (filter) """<filter id="blur"><feGaussianBlur stdDeviation="4"/></filter>""" else ""}
</defs>
<circle cx="128" cy="128" r="128" fill="url(#halo)"/>
<circle cx="128" cy="128" r="84" fill="url(#body)"${if (filter) """ filter="url(#blur)"""" else ""}/>
<circle cx="128" cy="128" r="84" fill="none" stroke="#FFFFFF" stroke-opacity="0.35" stroke-width="3"/>
<ellipse cx="108" cy="92" rx="40" ry="22" fill="url(#shine)"/>
</svg>"""

    /** A face de uma tecla em 96×96 (para a fatia de 24): sombra, corpo em degradê, contorno e brilho. */
    private fun face(top: String, bottom: String, edge: String): String = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">
<defs>
<linearGradient id="k" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="$top"/><stop offset="1" stop-color="$bottom"/></linearGradient>
<linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.35"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
</defs>
<rect x="6" y="9" width="84" height="84" rx="20" fill="#000000" fill-opacity="0.35"/>
<rect x="5" y="5" width="86" height="84" rx="20" fill="url(#k)" stroke="$edge" stroke-width="2"/>
<rect x="12" y="9" width="72" height="30" rx="14" fill="url(#s)"/>
</svg>"""

    private fun tray(): String = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" preserveAspectRatio="none">
<defs><linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1C1340"/><stop offset="1" stop-color="#0D0A1F"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#t)"/>
</svg>"""
}

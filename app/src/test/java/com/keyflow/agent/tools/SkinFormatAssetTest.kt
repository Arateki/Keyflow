package com.keyflow.agent.tools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * SKL-01: o `FORMAT.md` que a ferramenta `skin_get_format` devolve é, byte a byte, o do repositório — UMA fonte da verdade. O build o COPIA
 * (`copySkinFormatAsset` no `build.gradle.kts`) para `assets/agent/skin-format.md`; não existe uma segunda cópia versionada que um dia diverge.
 * Os testes de unidade rodam depois dessa tarefa (`dependsOn`), então a cópia está sempre lá.
 */
class SkinFormatAssetTest {

    private val original = File("../.specs/features/skin-packs/FORMAT.md")
    private val copied = File("build/generated/skinFormatAssets/agent/skin-format.md")

    @Test
    fun `the embedded asset is the repository format document, byte for byte`() {
        assertWithMessage("FORMAT.md original não encontrado em ${original.absolutePath}").that(original.isFile).isTrue()
        assertWithMessage("o build não copiou o FORMAT.md (copySkinFormatAsset): ${copied.absolutePath}").that(copied.isFile).isTrue()
        assertThat(copied.readBytes()).isEqualTo(original.readBytes())
    }

    @Test
    fun `there is no second versioned copy of the format document inside the app sources`() {
        val copies = File("src").walkTopDown().filter { it.isFile && (it.name == "skin-format.md" || it.name == "FORMAT.md") }.toList()
        assertWithMessage("cópias versionadas do formato (devia haver só o original em .specs): $copies").that(copies).isEmpty()
    }

    @Test
    fun `the document is the one that describes the file names and the limits the tools rely on`() {
        val text = original.readText()
        for (needle in listOf("manifest.json", "orb_idle_01.svg", "keyboard_key_normal.svg", "keyboard_tray.svg", "schemaVersion")) {
            assertThat(text).contains(needle)
        }
        // Cabe no teto de resultado da ferramenta (ela o devolve inteiro, sem corte).
        assertThat(text.length).isLessThan(60_000)
    }
}

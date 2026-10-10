package com.keyflow.fuzz

import com.keyflow.skins.SkinPackReadResult
import com.keyflow.skins.SkinPackReader
import com.keyflow.skins.SkinPackTestFixtures
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fuzzing da importação de pacote de skin — o .zip vem do usuário, então é entrada hostil por definição (SKP-IMP-04). O
 * contrato: com qualquer arquivo o leitor devolve Ok ou Failed, nunca uma exceção que escape (ela derrubaria a tela de
 * importação), e nenhum nome que não seja canônico chega ao que o store grava no disco. O prazo de cada teste também pega
 * expressão regular com tempo explosivo no conferidor de SVG.
 */
class SkinPackReaderFuzzTest {

    private fun read(zip: ByteArray, what: String): SkinPackReadResult = try {
        SkinPackReader.read(zip.inputStream())
    } catch (t: Throwable) {
        fail("$what: o leitor lançou ${t.javaClass.simpleName} (${t.message?.take(120)}) — ${zip.size} bytes, início ${zip.take(24)}")
        throw t
    }

    private val validPack: ByteArray = SkinPackTestFixtures.zip(SkinPackTestFixtures.fullOrb() + SkinPackTestFixtures.keyboardOnly().drop(1))

    @Test(timeout = 180_000)
    fun randomBytesAreRefusedNotThrown() = runBlocking<Unit> {
        val bytes = arbitrary { rs -> ByteArray(rs.random.nextInt(0, 4_096)).also(rs.random::nextBytes) }
        checkAll(ITERATIONS, bytes) { zip -> read(zip, "bytes aleatórios") }
    }

    /** Um pacote válido com bytes trocados, inseridos ou cortado: é o que acha o descompactador lançando algo inesperado. */
    @Test(timeout = 180_000)
    fun aCorruptedValidPackNeverThrows() = runBlocking<Unit> {
        val corrupted = arbitrary { rs ->
            val r = rs.random
            val zip = validPack.copyOf()
            when (r.nextInt(3)) {
                0 -> { repeat(1 + r.nextInt(8)) { zip[r.nextInt(zip.size)] = r.nextInt(256).toByte() }; zip }
                1 -> zip.copyOf(r.nextInt(zip.size))
                else -> { val at = r.nextInt(zip.size); zip.copyOfRange(0, at) + ByteArray(1 + r.nextInt(64)).also(r::nextBytes) + zip.copyOfRange(at, zip.size) }
            }
        }
        checkAll(ITERATIONS, corrupted) { zip -> read(zip, "pacote corrompido") }
    }

    /** Nomes hostis dentro do .zip: nada fora do vocabulário canônico chega ao que o store grava. */
    @Test(timeout = 180_000)
    fun hostileEntryNamesNeverReachTheDisk() = runBlocking<Unit> {
        val pieces = listOf("..", ".", "/", "\\", "C:", "~", "\u0000", "‮", "%2e%2e", "__MACOSX", "._x", "manifest.json",
            "orb_idle_1.svg", "keyboard_tray.svg", "pasta", "a b", "é", "😀", "orb_idle_0001.svg")
        val names = arbitrary { rs ->
            val r = rs.random
            List(1 + r.nextInt(6)) { List(1 + r.nextInt(4)) { pieces.random(r) }.joinToString(listOf("/", "\\", "").random(r)) }
        }
        checkAll(ITERATIONS, names) { list ->
            val entries = list.map { it to SkinPackTestFixtures.svg().toByteArray() } + ("manifest.json" to SkinPackTestFixtures.MANIFEST.toByteArray())
            val zip = try { SkinPackTestFixtures.zip(entries) } catch (e: Exception) { return@checkAll } // nome que nem o ZipOutputStream escreve
            val result = read(zip, "nomes $list")
            if (result is SkinPackReadResult.Ok) {
                val written = result.content.orbFrames.values.flatten().map { it.fileName } + result.content.keyboardFaces.values.map { it.fileName }
                written.forEach { name ->
                    assertTrue("nome não canônico iria para o disco: $name (de $list)", Regex("""(orb_[a-z]+_\d{4}|keyboard_[a-z_]+)\.svg""").matches(name))
                }
            }
        }
    }

    /** SVG com conteúdo qualquer (inclusive o que o conferidor procura: DOCTYPE, script, entidade) num pacote de nomes válidos. */
    @Test(timeout = 180_000)
    fun anySvgContentIsHandled() = runBlocking<Unit> {
        val tokens = listOf("<svg", "</svg>", "<!DOCTYPE x [", "<!ENTITY e \"", "&e;", "<script>", "<foreignObject", "xlink:href=\"http://x\"",
            "<path d=\"M0 0", "\"", ">", "<", " ", "a".repeat(200), "<g>".repeat(50), "\u0000", "é", "<!--", "-->", "<![CDATA[", "]]>")
        val svgs = arbitrary { rs -> List(rs.random.nextInt(1, 40)) { tokens.random(rs.random) }.joinToString("") }
        checkAll(ITERATIONS / 2, svgs) { svg ->
            val zip = SkinPackTestFixtures.zip(listOf("manifest.json" to SkinPackTestFixtures.MANIFEST.toByteArray(), "orb_idle_1.svg" to svg.toByteArray()))
            read(zip, "svg ${svg.take(80)}")
        }
    }

    companion object {
        const val ITERATIONS = 3_000
    }
}

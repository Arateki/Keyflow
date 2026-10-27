package com.keyflow.ui.icons

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O conjunto de ícones é dado puro: nomes únicos, paths bem-formados e dentro do grid 24×24. */
class KfIconsTest {

    @Test
    fun `every icon has a unique name`() {
        val names = KfIcons.all.map { it.name }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `every icon draws something and every path starts with a move`() {
        KfIcons.all.forEach { icon ->
            val paths = icon.strokes + icon.fills
            assertThat(paths).isNotEmpty()
            paths.forEach { d -> assertThat(d.trimStart().first()).isEqualTo('M') }
        }
    }

    @Test
    fun `absolute coordinates stay inside the 24 grid`() {
        // Só os números logo após comandos absolutos (maiúsculos) são coordenadas absolutas; os de
        // arcos e relativos são deslocamentos — basta conferir que nada absoluto escapa do grid. A folga de 1 é dos pontos de
        // controle das curvas dos logos de marca (o DeepSeek tem um em -0,231), que podem passar da borda sem o desenho passar.
        val absolute = Regex("""[MLHVC]\s*([-\d.,\s]+)""")
        // Número SVG compacto: "8.776-.231" são dois, ".854.152" também (o ponto depois de um decimal abre outro).
        val number = Regex("""-?(?:\d+\.?\d*|\.\d+)""")
        KfIcons.all.forEach { icon ->
            (icon.strokes + icon.fills).forEach { d ->
                absolute.findAll(d).flatMap { m ->
                    number.findAll(m.groupValues[1]).map { it.value.toFloat() }
                }.forEach { v -> assertThat(v).isIn(com.google.common.collect.Range.closed(-1f, 25f)) }
            }
        }
    }

    @Test
    fun `circle is two arcs around the center`() {
        assertThat(circle(12f, 12f, 3f)).isEqualTo("M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z")
    }

    @Test
    fun `round square closes back on its start`() {
        val d = roundSquare(4f, 4f, 6.5f, 1.75f)
        assertThat(d).startsWith("M5.75 4.0h3.0")
        assertThat(d).endsWith("z")
    }
}

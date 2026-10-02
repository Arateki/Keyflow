package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SKP-T1: a camada sobre o AndroidSVG desenha o que o FORMAT.md promete (cor sólida, degradês, esticar, fatiar 3×3) e
 * NÃO derruba o app com o que ele não suporta (`<filter>`, imagem externa). Instrumentado porque `Bitmap`/`Canvas` são
 * reais; também mede o custo de render (linhas `SKP-T1` do logcat) para a nota do STATE.md.
 */
class SvgRenderTest {

    private fun render(svg: String, w: Int, h: Int, fit: SvgFit = SvgFit.STRETCH, slice: SliceInsets? = null): Bitmap =
        SvgRender.toBitmap(SvgRender.parse(svg), w, h, fit, slice)

    private fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Int, tolerance: Int = 2, what: String = "") {
        val actual = bitmap.getPixel(x, y)
        val ok = abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
            abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
        assertTrue("$what pixel ($x,$y): esperava #%08X, veio #%08X".format(expected, actual), ok)
    }

    @Test
    fun solidColorRendersTheExpectedPixel() {
        val bitmap = render("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="#FF8000"/></svg>""", 40, 40)
        assertPixel(bitmap, 20, 20, 0xFFFF8000.toInt())
        assertPixel(bitmap, 1, 1, 0xFFFF8000.toInt())
        assertPixel(bitmap, 38, 38, 0xFFFF8000.toInt())
    }

    @Test
    fun areaOutsideTheDrawingStaysTransparent() {
        // Só a metade esquerda é pintada: a direita tem de continuar com alfa 0 (fundo transparente do FORMAT.md §2).
        val bitmap = render("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="50" height="100" fill="#0000FF"/></svg>""", 40, 40)
        assertPixel(bitmap, 5, 20, 0xFF0000FF.toInt())
        assertEquals(0, Color.alpha(bitmap.getPixel(34, 20)))
    }

    @Test
    fun semiTransparentFillKeepsItsAlpha() {
        val bitmap = render("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#000000" fill-opacity="0.5"/></svg>""", 20, 20)
        val a = Color.alpha(bitmap.getPixel(10, 10))
        assertTrue("alfa esperado ~128, veio $a", a in 124..132)
    }

    @Test
    fun linearGradientGoesFromOneEndToTheOther() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#000000"/><stop offset="1" stop-color="#FFFFFF"/></linearGradient></defs>
            <rect width="100" height="100" fill="url(#g)"/></svg>"""
        val bitmap = render(svg, 100, 20)
        val left = Color.red(bitmap.getPixel(2, 10))
        val mid = Color.red(bitmap.getPixel(50, 10))
        val right = Color.red(bitmap.getPixel(97, 10))
        assertTrue("esquerda deveria ser escura: $left", left < 15)
        assertTrue("meio deveria ser cinza: $mid", mid in 115..140)
        assertTrue("direita deveria ser clara: $right", right > 240)
        assertTrue("o degradê é horizontal: a cor não muda na vertical", abs(Color.red(bitmap.getPixel(50, 2)) - Color.red(bitmap.getPixel(50, 17))) <= 2)
    }

    @Test
    fun radialGradientIsOpaqueInTheCenterAndFadesOut() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <defs><radialGradient id="r" cx="50" cy="50" r="50" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FF0000" stop-opacity="1"/><stop offset="1" stop-color="#FF0000" stop-opacity="0"/></radialGradient></defs>
            <circle cx="50" cy="50" r="50" fill="url(#r)"/></svg>"""
        val bitmap = render(svg, 100, 100)
        assertTrue("o centro tem de ser opaco", Color.alpha(bitmap.getPixel(50, 50)) > 245)
        val ring = Color.alpha(bitmap.getPixel(50 + 25, 50))
        assertTrue("a meio raio o alfa cai para ~metade: $ring", ring in 105..150)
        assertTrue("a borda some", Color.alpha(bitmap.getPixel(50 + 48, 50)) < 25)
        assertEquals("fora do círculo é transparente", 0, Color.alpha(bitmap.getPixel(2, 2)))
        assertEquals("o degradê é vermelho de ponta a ponta", 255, Color.red(bitmap.getPixel(50 + 25, 50)))
    }

    @Test
    fun clipPathCutsTheDrawing() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <defs><clipPath id="c"><rect width="50" height="100"/></clipPath></defs>
            <rect width="100" height="100" fill="#00FF00" clip-path="url(#c)"/></svg>"""
        val bitmap = render(svg, 40, 40)
        assertPixel(bitmap, 8, 20, 0xFF00FF00.toInt())
        assertEquals(0, Color.alpha(bitmap.getPixel(30, 20)))
    }

    @Test
    fun stretchFillsTheTargetEvenWhenTheProportionsDiffer() {
        // viewBox 2:1 num alvo quadrado: esticar preenche tudo; manter a proporção deixa faixas transparentes em cima e embaixo.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><rect width="200" height="100" fill="#FF0000"/></svg>"""
        val stretched = render(svg, 40, 40, SvgFit.STRETCH)
        assertPixel(stretched, 20, 1, 0xFFFF0000.toInt(), what = "stretch topo")
        assertPixel(stretched, 20, 38, 0xFFFF0000.toInt(), what = "stretch base")
        val met = render(svg, 40, 40, SvgFit.MEET)
        assertEquals("meet: faixa de cima fica vazia", 0, Color.alpha(met.getPixel(20, 2)))
        assertPixel(met, 20, 20, 0xFFFF0000.toInt(), what = "meet centro")
        assertEquals("meet: faixa de baixo fica vazia", 0, Color.alpha(met.getPixel(20, 37)))
    }

    @Test
    fun theDocumentSizeAttributesDoNotOverrideTheTarget() {
        // width/height absolutos no <svg> NÃO podem decidir o tamanho do desenho: quem manda é o retângulo de destino. O
        // quadrado azul no canto de baixo à direita prova a escala (se o width=500 valesse, ele cairia fora do bitmap).
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="500" height="500" viewBox="0 0 100 100">
            <rect width="100" height="100" fill="#FFFFFF"/><rect x="80" y="80" width="20" height="20" fill="#0000FF"/></svg>"""
        val bitmap = render(svg, 100, 100)
        assertPixel(bitmap, 50, 50, 0xFFFFFFFF.toInt(), what = "meio")
        assertPixel(bitmap, 90, 90, 0xFF0000FF.toInt(), what = "quadrado azul no canto")
    }

    @Test
    fun withoutViewBoxTheDeclaredSizeBecomesTheScale() {
        val image = SvgRender.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="50" height="50"><rect width="50" height="50" fill="#FF0000"/></svg>""")
        assertTrue(image.scalable)
        assertFalse("o viewBox foi deduzido, não declarado", image.declaredViewBox)
        assertEquals(50f, image.width, 0.01f)
        val bitmap = SvgRender.toBitmap(image, 100, 100)
        assertPixel(bitmap, 98, 98, 0xFFFF0000.toInt(), what = "o desenho de 50 px preenche os 100 px do destino")
    }

    @Test
    fun withoutAnySizeTheSvgIsNotScalableAndDrawsOneToOne() {
        val image = SvgRender.parse("""<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10" fill="#FF0000"/></svg>""")
        assertFalse(image.scalable)
        val bitmap = SvgRender.toBitmap(image, 40, 40)
        assertPixel(bitmap, 5, 5, 0xFFFF0000.toInt(), what = "1:1 dentro do retângulo de 10")
        assertEquals("1:1: fora dos 10 px não há nada", 0, Color.alpha(bitmap.getPixel(30, 30)))
    }

    // As quatro quinas em cores diferentes, o miolo branco e o resto transparente: dá para saber o que esticou e o que não.
    private val cornersSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">
        <rect width="96" height="96" fill="#FFFFFF"/>
        <rect x="0" y="0" width="24" height="24" fill="#FF0000"/>
        <rect x="72" y="0" width="24" height="24" fill="#00FF00"/>
        <rect x="0" y="72" width="24" height="24" fill="#0000FF"/>
        <rect x="72" y="72" width="24" height="24" fill="#FFFF00"/></svg>"""

    @Test
    fun nineSliceKeepsTheCornersWhileTheMiddleStretches() {
        // 240×48: a escala dos cantos é min(240/96, 48/96) = 0,5 → cada canto vira 12×12 px, e o miolo (96−48=48 unidades) estica.
        val sliced = render(cornersSvg, 240, 48, slice = SliceInsets(24f, 24f, 24f, 24f))
        assertPixel(sliced, 3, 3, 0xFFFF0000.toInt(), what = "canto sup. esq.")
        assertPixel(sliced, 236, 3, 0xFF00FF00.toInt(), what = "canto sup. dir.")
        assertPixel(sliced, 3, 44, 0xFF0000FF.toInt(), what = "canto inf. esq.")
        assertPixel(sliced, 236, 44, 0xFFFFFF00.toInt(), what = "canto inf. dir.")
        // O canto continua com 12 px: logo ao lado dele já é o miolo branco; esticado de verdade ele iria até ~60 px.
        assertPixel(sliced, 11, 5, 0xFFFF0000.toInt(), what = "último px do canto")
        assertPixel(sliced, 14, 5, 0xFFFFFFFF.toInt(), what = "primeiro px do miolo")
        assertPixel(sliced, 120, 24, 0xFFFFFFFF.toInt(), what = "miolo")
        // E o canto também é quadrado na vertical: 12 px de altura, não os 48 do alvo.
        assertPixel(sliced, 5, 11, 0xFFFF0000.toInt(), what = "último px do canto (vertical)")
        assertPixel(sliced, 5, 14, 0xFFFFFFFF.toInt(), what = "miolo abaixo do canto")

        val plain = render(cornersSvg, 240, 48)
        assertPixel(plain, 20, 5, 0xFFFF0000.toInt(), what = "sem fatiar o canto estica até 60 px")
    }

    @Test
    fun nineSliceLeavesNoSeamsBetweenCells() {
        // Tudo branco e opaco: depois de fatiar em nove células não pode sobrar nenhum px transparente nem semitransparente,
        // nem em tamanhos que dão fronteiras fracionárias.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" fill="#FFFFFF"/></svg>"""
        for ((w, h) in listOf(240 to 48, 133 to 61, 97 to 97, 301 to 53)) {
            val bitmap = render(svg, w, h, slice = SliceInsets(24f, 24f, 24f, 24f))
            for (y in 0 until h) for (x in 0 until w) {
                val a = Color.alpha(bitmap.getPixel(x, y))
                if (a != 255) fail("fresta em ($x,$y) no tamanho ${w}x$h: alfa=$a")
            }
        }
    }

    @Test
    fun nineSliceWithAsymmetricMargins() {
        val sliced = render(cornersSvg, 192, 192, slice = SliceInsets(24f, 24f, 24f, 24f))
        // Escala 2: cada canto vira 48 px.
        assertPixel(sliced, 40, 40, 0xFFFF0000.toInt(), what = "canto 48 px")
        assertPixel(sliced, 55, 55, 0xFFFFFFFF.toInt(), what = "miolo depois dos 48 px")
    }

    @Test
    fun filterIsIgnoredAndDoesNotCrash() {
        // FORMAT.md §4: <filter> não funciona e o app ignora — o desenho continua saindo, sem desfoque.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <defs><filter id="blur"><feGaussianBlur stdDeviation="8"/></filter></defs>
            <rect x="25" y="25" width="50" height="50" fill="#FF0000" filter="url(#blur)"/></svg>"""
        val bitmap = render(svg, 100, 100)
        Log.i(TAG, "filter: centro=#%08X canto=#%08X borda-do-retangulo=#%08X".format(bitmap.getPixel(50, 50), bitmap.getPixel(5, 5), bitmap.getPixel(22, 50)))
        assertNotNull(bitmap)
        // Sem o desfoque, a borda do retângulo continua nítida: logo fora dele (x=20) é transparente.
        assertPixel(bitmap, 50, 50, 0xFFFF0000.toInt(), what = "centro do retângulo")
        assertEquals("sem desfoque: fora do retângulo é vazio", 0, Color.alpha(bitmap.getPixel(15, 50)))
    }

    @Test
    fun externalImageReferenceIsNotFetched() {
        // Nenhum SVGExternalFileResolver é registrado: a imagem externa some em silêncio, sem rede e sem exceção.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100">
            <rect width="100" height="100" fill="#00FF00"/><image x="0" y="0" width="100" height="100" xlink:href="http://example.invalid/x.png"/>
            <image x="0" y="0" width="100" height="100" href="file:///etc/passwd"/></svg>"""
        val started = System.nanoTime()
        val bitmap = render(svg, 40, 40)
        val ms = (System.nanoTime() - started) / 1e6
        assertPixel(bitmap, 20, 20, 0xFF00FF00.toInt())
        assertTrue("render com href externo não pode esperar rede: ${ms}ms", ms < 2000)
    }

    @Test
    fun embeddedPngDataUriIsDrawn() {
        // Um PNG 2×2 vermelho em base64 (o único tipo de imagem embutida que o FORMAT.md aceita).
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII="
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10">
            <image x="0" y="0" width="10" height="10" xlink:href="data:image/png;base64,$png"/></svg>"""
        val bitmap = render(svg, 20, 20)
        Log.i(TAG, "data-uri png: pixel=#%08X".format(bitmap.getPixel(10, 10)))
        assertTrue("a imagem embutida precisa aparecer (alfa > 0)", Color.alpha(bitmap.getPixel(10, 10)) > 0)
    }

    @Test
    fun scriptAndForeignObjectDoNotCrashTheRenderer() {
        // O leitor do pacote recusa os dois ANTES do parse; se algum dia passarem, o renderizador não pode explodir nem executar nada.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><script>throw 1</script>
            <foreignObject width="100" height="100"><div xmlns="http://www.w3.org/1999/xhtml">x</div></foreignObject>
            <rect width="100" height="100" fill="#FF0000"/></svg>"""
        assertPixel(render(svg, 20, 20), 10, 10, 0xFFFF0000.toInt())
    }

    @Test
    fun internalEntitiesAreDisabled() {
        assertFalse("o init do SvgRender tem de desligar as entidades internas (o padrão da 1.4 é ligado)", SvgRender.internalEntitiesEnabled)
        // Com elas desligadas, o DOCTYPE com entidade é rejeitado ou a entidade não expande — o que NÃO pode acontecer é
        // a expansão silenciosa. Registro do comportamento real no logcat.
        val svg = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY color "#FF0000">]>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="&color;"/></svg>"""
        val outcome = try {
            val bitmap = render(svg, 10, 10)
            "renderizou, pixel=#%08X".format(bitmap.getPixel(5, 5)).also { assertFalse("entidade expandida!", bitmap.getPixel(5, 5) == 0xFFFF0000.toInt()) }
        } catch (e: SvgException) {
            "rejeitou: ${e.message}"
        }
        Log.i(TAG, "entidade interna: $outcome")
    }

    @Test
    fun malformedSvgThrowsSvgException() {
        for (bad in listOf("", "isso não é xml", "<svg", "<html><body/></html>", """<svg viewBox="0 0 0 0" xmlns="http://www.w3.org/2000/svg"/>""")) {
            try {
                render(bad, 10, 10)
                fail("deveria ter lançado SvgException para: $bad")
            } catch (_: SvgException) {
                // ok
            }
        }
    }

    // ── medição (o número vai para o STATE.md) ───────────────────────────────

    /** Um quadro de orbe "de verdade": halo e miolo em degradê radial, 24 pontos em anel, arcos de traço e um brilho. */
    private fun orbFrame(phase: Int, points: Int): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><defs>""")
        append("""<radialGradient id="halo" cx="128" cy="128" r="128" gradientUnits="userSpaceOnUse"><stop offset="0.5" stop-color="#7EB8A8" stop-opacity="0.45"/><stop offset="1" stop-color="#7EB8A8" stop-opacity="0"/></radialGradient>""")
        append("""<radialGradient id="body" cx="110" cy="105" r="80" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#9AD0BF"/><stop offset="1" stop-color="#4E9683"/></radialGradient>""")
        append("""<linearGradient id="shine" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.8"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>""")
        append("""</defs><circle cx="128" cy="128" r="128" fill="url(#halo)"/>""")
        append("""<circle cx="128" cy="128" r="${70 + phase % 6}" fill="url(#body)"/>""")
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points + phase * 0.05
            val x = 128 + 100 * Math.cos(a)
            val y = 128 + 100 * Math.sin(a)
            append("""<circle cx="%.2f" cy="%.2f" r="%d" fill="#FFFFFF" fill-opacity="0.6"/>""".format(java.util.Locale.US, x, y, 2 + i % 3))
        }
        append("""<path d="M60 128 A68 68 0 0 1 196 128" fill="none" stroke="#FFFFFF" stroke-opacity="0.5" stroke-width="3" stroke-linecap="round"/>""")
        append("""<ellipse cx="105" cy="92" rx="34" ry="18" fill="url(#shine)"/></svg>""")
    }

    @Test
    fun measureRenderCostPerFrame() {
        val frames = (0 until 12).map { orbFrame(it, 24) }
        val heavy = (0 until 12).map { orbFrame(it, 160) }
        // Aquece o JIT e o parser com os dois conjuntos antes de medir qualquer lado (a primeira rodada saía 2–4× mais lenta).
        repeat(150) { SvgRender.toBitmap(SvgRender.parse((frames + heavy)[it % 24]), 200, 200, SvgFit.MEET).recycle() }
        for (side in listOf(156, 300, 480)) {
            for ((label, set) in listOf("orbe-24-pontos" to frames, "orbe-160-pontos" to heavy)) {
                // Aquecimento: o JIT e a primeira alocação não entram na conta.
                repeat(40) { SvgRender.toBitmap(SvgRender.parse(set[it % set.size]), side, side, SvgFit.MEET).recycle() }
                val parse = ArrayList<Double>()
                val draw = ArrayList<Double>()
                repeat(60) { i ->
                    val t0 = System.nanoTime()
                    val image = SvgRender.parse(set[i % set.size])
                    val t1 = System.nanoTime()
                    SvgRender.toBitmap(image, side, side, SvgFit.MEET).recycle()
                    val t2 = System.nanoTime()
                    parse += (t1 - t0) / 1e6
                    draw += (t2 - t1) / 1e6
                }
                parse.sort(); draw.sort()
                Log.i(TAG, "SKP-T1 $label lado=${side}px bytes=${set[0].length}: parse mediana=%.2fms p95=%.2fms | render(bitmap) mediana=%.2fms p95=%.2fms".format(
                    java.util.Locale.US, parse[30], parse[56], draw[30], draw[56],
                ))
            }
        }
        // E o fatiamento 3×3 de uma face de tecla (nove desenhos), frio e em regime.
        val face = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs>
            <linearGradient id="k" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#D8DEE6"/></linearGradient></defs>
            <rect x="4" y="4" width="88" height="88" rx="20" fill="url(#k)" stroke="#B8C0CC" stroke-width="2"/></svg>"""
        val image = SvgRender.parse(face)
        repeat(30) { SvgRender.toBitmap(image, 120, 150, slice = SliceInsets(24f, 24f, 24f, 24f)).recycle() }
        val keyMs = ArrayList<Double>()
        repeat(40) {
            val t0 = System.nanoTime()
            SvgRender.toBitmap(image, 120, 150, slice = SliceInsets(24f, 24f, 24f, 24f)).recycle()
            keyMs += (System.nanoTime() - t0) / 1e6
        }
        keyMs.sort()
        Log.i(TAG, "SKP-T1 face-de-tecla 9-slice 120x150px: mediana=%.2fms p95=%.2fms".format(java.util.Locale.US, keyMs[20], keyMs[38]))
    }

    private companion object {
        const val TAG = "SKP"
    }
}

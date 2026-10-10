package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * O SVG não pôde ser lido: XML inválido, documento sem elemento raiz ou `viewBox` sem área. A mensagem é técnica (em
 * português); quem mostra ao usuário (o relatório de import) a embrulha com o nome do arquivo.
 */
class SvgException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Como o SVG ocupa o retângulo de destino quando as proporções diferem. */
enum class SvgFit {
    /** Estica até preencher: faces de tecla e fundo do teclado (`preserveAspectRatio="none"`, FORMAT.md §2). */
    STRETCH,

    /** Mantém a proporção e centraliza (sobra transparente): a orbe, que é um quadrado inteiro. */
    MEET,
}

/**
 * Um SVG já lido, pronto para desenhar quantas vezes for preciso. Opaco para quem usa: o tipo da biblioteca não sai
 * deste arquivo.
 *
 * [width]/[height] são o tamanho do `viewBox` — o "tamanho natural" que define a proporção; sem `viewBox`, o `width`/`height`
 * declarados no `<svg>` fazem esse papel. Se nenhum dos dois existe, [scalable] é `false` e o desenho sai em 1:1 (o autor
 * esqueceu de dimensionar), sem escala nenhuma.
 */
class SvgImage internal constructor(
    internal val svg: SVG,
    internal val viewBox: RectF,
    /** O `<svg>` declarava um `viewBox` próprio (em vez de ele ter sido deduzido do `width`/`height`). */
    val declaredViewBox: Boolean,
    val scalable: Boolean,
) {
    val width: Float get() = viewBox.width()
    val height: Float get() = viewBox.height()

    /** O desenho do documento inteiro, gravado uma vez ([SvgRender.drawNineSlice] o reproduz nove vezes). `null` até o primeiro uso. */
    internal var recorded: Picture? = null
}

/**
 * Desenha SVG de pacotes de skin sobre um [Canvas] do Android. **O único arquivo do app que conhece a biblioteca**
 * (AndroidSVG 1.4, ver `app/build.gradle.kts`): trocar o renderizador depois não vaza para o resto.
 *
 * O que a biblioteca faz e não faz (conferido no `classes.jar` e por [SvgRenderTest]):
 * - desenha `path`, formas, `g`/`defs`/`use`, degradês, `clipPath`, `mask`, `pattern`, opacidade, `transform`, `style` simples
 *   e `text` com fontes do sistema; **ignora** `<filter>`/`filter=` (sem desfoque nem sombra projetada) e animações;
 * - **não** busca arquivo nem rede por conta própria: sem um `SVGExternalFileResolver` registrado, `<image href="http:…">`
 *   some em silêncio — e este objeto nunca registra um. Só `data:image/png|jpeg;base64` (e `#id` interno) é resolvido;
 * - as **entidades internas** do XML vêm LIGADAS por padrão na 1.4 (`SVG.isInternalEntitiesEnabled()` = true). Aqui elas
 *   são desligadas no `init`, uma barreira a mais além da recusa textual do [SkinPackReader] ao `DOCTYPE`.
 *
 * Chamar [draw]/[drawNineSlice]/[toBitmap] fora da thread principal é seguro: cada [SvgImage] serializa os próprios
 * desenhos (a biblioteca não promete que dois desenhos simultâneos do mesmo documento são seguros).
 */
object SvgRender {

    init {
        SVG.setInternalEntitiesEnabled(false)
    }

    /** Só o teste confere: as entidades internas do XML da biblioteca precisam estar desligadas. */
    internal val internalEntitiesEnabled: Boolean get() = SVG.isInternalEntitiesEnabled()

    /** Lê o SVG de [text] (UTF-8 já decodificado). Lança [SvgException] se não for um SVG desenhável. */
    fun parse(text: String): SvgImage {
        val svg = try {
            SVG.getFromString(text)
        } catch (e: Exception) {
            // SVGParseException (XML ruim) e, em documentos estranhos, exceções de execução da biblioteca: tudo é "não leu".
            throw SvgException("o SVG não é válido: ${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            val declared = svg.getDocumentViewBox()
            if (declared != null) {
                if (declared.width() <= 0f || declared.height() <= 0f) throw SvgException("o viewBox do SVG não tem área")
                fillViewport(svg)
                return SvgImage(svg, RectF(declared), declaredViewBox = true, scalable = true)
            }
            // Sem viewBox, a biblioteca não escala nada. Se o <svg> declara tamanho absoluto, ele vira o viewBox
            // (o autor desenhou em px dessa área); senão não há como saber o tamanho e o desenho sai em 1:1.
            val w = svg.getDocumentWidth()
            val h = svg.getDocumentHeight()
            if (w > 0f && h > 0f) {
                svg.setDocumentViewBox(0f, 0f, w, h)
                fillViewport(svg)
                return SvgImage(svg, RectF(0f, 0f, w, h), declaredViewBox = false, scalable = true)
            }
            return SvgImage(svg, RectF(0f, 0f, DEFAULT_SIDE, DEFAULT_SIDE), declaredViewBox = false, scalable = false)
        } catch (e: SvgException) {
            throw e
        } catch (e: Exception) {
            throw SvgException("o SVG não é válido: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * O `width`/`height` absolutos do `<svg>` mandam no tamanho do desenho **por cima** do retângulo que o chamador pede
     * (medido: `width="50"` desenhava a 50 px mesmo num destino de 100). Viram 100% do destino, que é quem decide a escala —
     * o tamanho natural já foi guardado como `viewBox`.
     */
    private fun fillViewport(svg: SVG) {
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
    }

    /** Desenha [image] em [left],[top] com [width]×[height] px de tamanho, conforme [fit]. */
    fun draw(
        canvas: Canvas,
        image: SvgImage,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        fit: SvgFit = SvgFit.STRETCH,
    ) {
        if (width <= 0f || height <= 0f) return
        if (!image.scalable) {
            // Sem tamanho natural: 1:1 a partir do canto, e a biblioteca recorta o que passar do viewport.
            render(canvas, image, left, top, width, height, PreserveAspectRatio.UNSCALED)
            return
        }
        render(canvas, image, left, top, width, height, if (fit == SvgFit.STRETCH) PreserveAspectRatio.STRETCH else PreserveAspectRatio.LETTERBOX)
    }

    /**
     * Desenha [image] em [dst] com **fatiamento 3×3**: os cantos mantêm a proporção (todos na mesma escala, a do menor lado
     * relativo) e só o miolo estica para preencher [dst]. Cada uma das nove células é o desenho inteiro recortado e transladado — a
     * biblioteca não fatia —, mas o documento é **percorrido uma vez só**: ele é gravado num [Picture] e as nove células o
     * reproduzem. (Re-renderizar o SVG nove vezes custava 10–20× o das skins nativas por sprite, medido no emulador: o custo é
     * andar na árvore e montar shaders, não rasterizar.) O resultado é o mesmo, vetor puro, sem reamostrar bitmap.
     *
     * [dst] deve ter bordas em px inteiros (o sprite é desenhado em `0,0,w,h`): as fronteiras das células são arredondadas
     * para px, para não sobrar fresta nem sobrepor entre elas. Pensado para um [Canvas] de software (um bitmap de sprite): a
     * reprodução de um [Picture] num canvas acelerado não é garantida nas versões antigas do Android.
     */
    fun drawNineSlice(canvas: Canvas, image: SvgImage, dst: RectF, slice: SliceInsets) {
        if (dst.width() <= 0f || dst.height() <= 0f) return
        if (!image.scalable) {
            draw(canvas, image, dst.left, dst.top, dst.width(), dst.height())
            return
        }
        val vb = image.viewBox
        val vbW = vb.width()
        val vbH = vb.height()
        // O miolo de origem precisa ter alguma área, ou não haveria o que esticar. O import já recusa fatias que ocupam o
        // viewBox inteiro; isto é só a rede de segurança do desenho.
        val minMiddleW = vbW * MIN_MIDDLE
        val minMiddleH = vbH * MIN_MIDDLE
        val l = slice.left.coerceIn(0f, vbW - minMiddleW)
        val r = slice.right.coerceIn(0f, vbW - l - minMiddleW)
        val t = slice.top.coerceIn(0f, vbH - minMiddleH)
        val b = slice.bottom.coerceIn(0f, vbH - t - minMiddleH)

        // A escala dos cantos é a mesma nos dois eixos: o menor dos dois fatores. Com l+r <= vbW e t+b <= vbH os cantos
        // sempre cabem em [dst].
        val s = min(dst.width() / vbW, dst.height() / vbH)
        val dx1 = dst.left + (l * s).roundToInt()
        val dx2 = maxOf(dx1, dst.right - (r * s).roundToInt())
        val dy1 = dst.top + (t * s).roundToInt()
        val dy2 = maxOf(dy1, dst.bottom - (b * s).roundToInt())
        val destX = floatArrayOf(dst.left, dx1, dx2, dst.right)
        val destY = floatArrayOf(dst.top, dy1, dy2, dst.bottom)

        // O Picture é gravado em unidades inteiras (px de gravação = unidades do viewBox × [fx]/[fy]; 1 para o viewBox inteiro).
        val pictureW = maxOf(1, vbW.roundToInt())
        val pictureH = maxOf(1, vbH.roundToInt())
        val fx = pictureW / vbW
        val fy = pictureH / vbH
        val picture = recordedPicture(image, pictureW, pictureH)
        val srcX = floatArrayOf(0f, l * fx, (vbW - r) * fx, pictureW.toFloat())
        val srcY = floatArrayOf(0f, t * fy, (vbH - b) * fy, pictureH.toFloat())

        for (row in 0 until 3) {
            val cellH = destY[row + 1] - destY[row]
            val srcH = srcY[row + 1] - srcY[row]
            if (cellH <= 0f || srcH <= 0f) continue
            for (col in 0 until 3) {
                val cellW = destX[col + 1] - destX[col]
                val srcW = srcX[col + 1] - srcX[col]
                if (cellW <= 0f || srcW <= 0f) continue
                val sx = cellW / srcW
                val sy = cellH / srcH
                canvas.save()
                canvas.clipRect(destX[col], destY[row], destX[col + 1], destY[row + 1])
                // O ponto (srcX, srcY) da célula de origem cai em (destX, destY): translada e escala, nessa ordem.
                canvas.translate(destX[col] - srcX[col] * sx, destY[row] - srcY[row] * sy)
                canvas.scale(sx, sy)
                canvas.drawPicture(picture)
                canvas.restore()
            }
        }
    }

    /** O documento gravado em [width]×[height] (esticado do viewBox: `preserveAspectRatio` "none"), uma vez por imagem. */
    private fun recordedPicture(image: SvgImage, width: Int, height: Int): Picture = synchronized(image) {
        image.recorded ?: image.svg.renderToPicture(width, height, RenderOptions.create().preserveAspectRatio(PreserveAspectRatio.STRETCH))
            .also { image.recorded = it }
    }

    /**
     * O SVG num bitmap novo de [width]×[height] px (ARGB_8888, fundo transparente), conforme [fit] — ou, com [slice], com
     * fatiamento 3×3 (então [fit] não vale). É o que vira sprite de tecla, fundo do teclado e frame da orbe.
     */
    fun toBitmap(image: SvgImage, width: Int, height: Int, fit: SvgFit = SvgFit.STRETCH, slice: SliceInsets? = null): Bitmap {
        require(width > 0 && height > 0) { "bitmap sem área: ${width}x$height" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (slice != null) {
            drawNineSlice(canvas, image, RectF(0f, 0f, width.toFloat(), height.toFloat()), slice)
        } else {
            draw(canvas, image, 0f, 0f, width.toFloat(), height.toFloat(), fit)
        }
        return bitmap
    }

    private fun render(canvas: Canvas, image: SvgImage, x: Float, y: Float, w: Float, h: Float, aspect: PreserveAspectRatio) {
        val options = RenderOptions.create().viewPort(x, y, w, h).preserveAspectRatio(aspect)
        synchronized(image) { image.svg.renderToCanvas(canvas, options) }
    }

    /** Lado do `viewBox` presumido quando o SVG não diz tamanho nenhum (só serve de proporção quadrada). */
    private const val DEFAULT_SIDE = 100f

    /** Fração mínima do viewBox que o miolo de uma fatia mantém, para sempre haver o que esticar. */
    private const val MIN_MIDDLE = 0.01f
}

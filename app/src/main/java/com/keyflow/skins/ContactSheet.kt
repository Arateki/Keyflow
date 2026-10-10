package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.keyflow.agent.ToolImage
import com.keyflow.i18n.tr
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A "folha de contato" de uma skin (A4, SKL-03, D7): uma imagem PNG só, desenhada pelo próprio app a partir dos SVGs JÁ VALIDADOS (nada vai a
 * disco), para o usuário VER o que o modelo criou antes de instalar — e para o modelo ver, se aceitar imagem. Mostra:
 *  1. **um quadro de cada estado da orbe** (o 1º frame de `idle`, `listening`, `paused`, `transcribing`; um estado sem frames usa os de
 *     `idle` e a legenda diz isso), cada um sobre um fundo claro e escuro lado a lado — a transparência aparece nos dois;
 *  2. **alguns frames da animação**: do estado com mais frames, até [STRIP_FRAMES] espaçados por igual (o laço inteiro, não só o começo);
 *  3. **as faces do teclado**: uma faixa com a bandeja (o SVG, ou a cor lisa do manifest) e as teclas — normal, pressionada, função e função
 *     pressionada — com o fatiamento 3×3 do manifest, como no teclado de verdade.
 *
 * O lado maior fica em [WIDTH] px, abaixo do limite de 1568 px que a API da Anthropic aceita sem reduzir a imagem. Usa o [SvgRender] (o único
 * que conhece a biblioteca de SVG). Chamar fora da thread principal: desenha uns vinte SVGs. Nunca lança: um quadro que não desenha vira
 * um "x" na célula, e se nem a folha monta o resultado é `null` (a pré-visualização segue só com o relatório).
 */
object ContactSheet {
    const val WIDTH = 1400
    const val STRIP_FRAMES = 8

    private const val MARGIN = 28
    private const val GAP = 20
    private const val ORB_CELL = 296
    private const val STRIP_CELL = 150
    private const val KEY_W = 168
    private const val KEY_H = 112
    private const val LABEL_H = 34

    private val LIGHT = Color.rgb(0xEC, 0xE6, 0xD3)
    private val DARK = Color.rgb(0x22, 0x25, 0x2D)
    private val PAGE = Color.rgb(0x31, 0x35, 0x40)
    private val INK = Color.rgb(0xF2, 0xF0, 0xE8)
    private val MUTED = Color.rgb(0xA9, 0xAE, 0xBB)

    fun render(content: SkinPackContent): ToolImage? = try {
        val bitmap = draw(content)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        ToolImage("image/png", out.toByteArray())
    } catch (e: Exception) {
        null
    }

    private fun draw(content: SkinPackContent): Bitmap {
        val keyboard = content.hasKeyboard
        val orb = content.hasOrb
        val stripState = if (orb) content.orbFrames.entries.maxByOrNull { it.value.size }?.takeIf { it.value.size > 1 } else null

        // As alturas das faixas, de cima para baixo.
        val titleH = 78
        val orbH = if (orb) ORB_CELL + LABEL_H + GAP else 0
        val stripH = if (stripState != null) STRIP_CELL + LABEL_H * 2 + GAP else 0
        val keyboardH = if (keyboard) KEY_H + 2 * 28 + LABEL_H + GAP else 0
        val height = MARGIN + titleH + orbH + stripH + keyboardH + MARGIN - GAP
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAGE)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 34f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 22f }
        canvas.drawText(clip(content.manifest.name, 48), MARGIN.toFloat(), (MARGIN + 34).toFloat(), title)
        val author = content.manifest.author?.let { clip(it, 60) }
        canvas.drawText(
            listOfNotNull(author, "fps ${content.manifest.orb.fps}".takeIf { orb }, "${content.frameCount} frames".takeIf { orb }).joinToString("  ·  "),
            MARGIN.toFloat(), (MARGIN + 66).toFloat(), subtitle,
        )

        var y = MARGIN + titleH
        if (orb) {
            drawOrbStates(canvas, content, y)
            y += orbH
        }
        if (stripState != null) {
            drawStrip(canvas, content, stripState.key, stripState.value, y)
            y += stripH
        }
        if (keyboard) drawKeyboard(canvas, content, y)
        return bitmap
    }

    // ── a orbe ───────────────────────────────────────────────────────────────

    private fun drawOrbStates(canvas: Canvas, content: SkinPackContent, top: Int) {
        val label = labelPaint()
        for ((index, state) in OrbPackState.entries.withIndex()) {
            val own = content.orbFrames[state]
            val frames = own ?: content.orbFrames[OrbPackState.IDLE] ?: continue
            val left = MARGIN + index * (ORB_CELL + GAP)
            splitBackground(canvas, left, top, ORB_CELL, ORB_CELL)
            drawFrame(canvas, frames.first(), left, top, ORB_CELL)
            val fps = content.manifest.orb.fpsOf(if (own != null) state else OrbPackState.IDLE)
            val text = if (own != null) "${state.key} · ${own.size} · $fps fps" else tr("%s: usa o idle", state.key)
            canvas.drawText(clip(text, 30), left.toFloat(), (top + ORB_CELL + 24).toFloat(), label)
        }
    }

    private fun drawStrip(canvas: Canvas, content: SkinPackContent, state: OrbPackState, frames: List<PackSvg>, top: Int) {
        val label = labelPaint()
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 24f }
        canvas.drawText(tr("Animação de %s", state.key), MARGIN.toFloat(), (top + 22).toFloat(), subtitle)
        val shown = sample(frames.size, STRIP_FRAMES)
        val cellsTop = top + LABEL_H
        for ((slot, frameIndex) in shown.withIndex()) {
            val left = MARGIN + slot * (STRIP_CELL + 12)
            splitBackground(canvas, left, cellsTop, STRIP_CELL, STRIP_CELL)
            drawFrame(canvas, frames[frameIndex], left, cellsTop, STRIP_CELL)
            canvas.drawText("${frameIndex + 1}/${frames.size}", left.toFloat(), (cellsTop + STRIP_CELL + 24).toFloat(), label)
        }
    }

    /** Até [wanted] índices de 0 até [count] − 1, espaçados por igual, sempre com o primeiro e o último. */
    internal fun sample(count: Int, wanted: Int): List<Int> {
        if (count <= wanted) return (0 until count).toList()
        return (0 until wanted).map { (it * (count - 1).toDouble() / (wanted - 1)).roundToInt() }.distinct()
    }

    // ── o teclado ────────────────────────────────────────────────────────────

    private fun drawKeyboard(canvas: Canvas, content: SkinPackContent, top: Int) {
        val manifest = content.manifest.keyboard
        val label = labelPaint()
        val stripLeft = MARGIN.toFloat()
        val stripTop = top.toFloat()
        val stripWidth = (WIDTH - 2 * MARGIN).toFloat()
        val stripHeight = (KEY_H + 2 * 28).toFloat()
        val tray = RectF(stripLeft, stripTop, stripLeft + stripWidth, stripTop + stripHeight)

        // A bandeja: o SVG esticado, ou a cor lisa do manifest (sobre o fundo escuro, para uma cor transparente aparecer).
        canvas.drawRect(tray, Paint().apply { color = DARK })
        val trayImage = content.keyboardFaces[KeyboardPart.TRAY]?.let { parse(it) }
        if (trayImage != null) {
            runCatching { SvgRender.draw(canvas, trayImage, tray.left, tray.top, tray.width(), tray.height(), SvgFit.STRETCH) }
        } else {
            canvas.drawRect(tray, Paint().apply { color = manifest.trayColor })
        }

        // As teclas: três de letra, uma pressionada, uma de função e uma de função pressionada — com o que o pacote tem, na cadeia de padrões do formato.
        val slots = listOf(
            Triple("Q", KeyboardPart.KEY_NORMAL, false),
            Triple("W", KeyboardPart.KEY_NORMAL, false),
            Triple("E", KeyboardPart.KEY_NORMAL, false),
            Triple("R", KeyboardPart.KEY_PRESSED, true),
            Triple("fn", KeyboardPart.MODIFIER_NORMAL, false),
            Triple("fn", KeyboardPart.MODIFIER_PRESSED, true),
        )
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = manifest.ink; textSize = 40f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val gap = (stripWidth - slots.size * KEY_W) / (slots.size + 1)
        for ((i, slot) in slots.withIndex()) {
            val (letter, wanted, _) = slot
            val (part, darken) = resolve(content, wanted)
            val left = stripLeft + gap + i * (KEY_W + gap)
            val key = RectF(left, stripTop + 28f, left + KEY_W, stripTop + 28f + KEY_H)
            val image = content.keyboardFaces[part]?.let { parse(it) }
            if (image != null) {
                runCatching {
                    val slice = manifest.slice
                    // O fatiamento 3×3 pede bordas inteiras (o sprite é desenhado em px inteiros).
                    val whole = RectF(key.left.roundToInt().toFloat(), key.top.roundToInt().toFloat(), key.right.roundToInt().toFloat(), key.bottom.roundToInt().toFloat())
                    if (slice != null) SvgRender.drawNineSlice(canvas, image, whole, slice) else SvgRender.draw(canvas, image, whole.left, whole.top, whole.width(), whole.height())
                }
            }
            // Pressionada sem a peça própria: o teclado escurece a normal; aqui, um véu.
            if (darken) canvas.drawRect(key, Paint().apply { color = Color.argb(70, 0, 0, 0) })
            canvas.drawText(letter, key.centerX(), key.centerY() + 14f, ink)
        }
        val caption = tr("teclado: normal · pressionada · função · função pressionada")
        canvas.drawText(caption, stripLeft, stripTop + stripHeight + 26f, label)
    }

    /** A peça que o teclado desenharia para [wanted] e se a escurece: a cadeia de padrões do FORMAT.md §2, a mesma do `PackKeyboard.faceFor`. */
    private fun resolve(content: SkinPackContent, wanted: KeyboardPart): Pair<KeyboardPart, Boolean> {
        val has = content.keyboardFaces.keys
        return when (wanted) {
            KeyboardPart.KEY_PRESSED -> if (KeyboardPart.KEY_PRESSED in has) wanted to false else KeyboardPart.KEY_NORMAL to true
            KeyboardPart.MODIFIER_NORMAL -> if (KeyboardPart.MODIFIER_NORMAL in has) wanted to false else KeyboardPart.KEY_NORMAL to false
            KeyboardPart.MODIFIER_PRESSED -> when {
                KeyboardPart.MODIFIER_PRESSED in has -> wanted to false
                KeyboardPart.MODIFIER_NORMAL in has -> KeyboardPart.MODIFIER_NORMAL to true
                KeyboardPart.KEY_PRESSED in has -> KeyboardPart.KEY_PRESSED to false
                else -> KeyboardPart.KEY_NORMAL to true
            }
            else -> wanted to false
        }
    }

    // ── peças ────────────────────────────────────────────────────────────────

    private fun parse(svg: PackSvg): SvgImage? = try {
        SvgRender.parse(String(svg.bytes, Charsets.UTF_8).removePrefix("﻿"))
    } catch (e: Exception) {
        null
    }

    /** O quadro [svg] num quadrado de [size] px em ([left], [top]); falhou, um "x". */
    private fun drawFrame(canvas: Canvas, svg: PackSvg, left: Int, top: Int, size: Int) {
        val image = parse(svg)
        val ok = image != null && runCatching { SvgRender.draw(canvas, image, left.toFloat(), top.toFloat(), size.toFloat(), size.toFloat(), SvgFit.MEET) }.isSuccess
        if (!ok) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 48f; textAlign = Paint.Align.CENTER }
            canvas.drawText("x", left + size / 2f, top + size / 2f + 16f, paint)
        }
    }

    /** Metade clara, metade escura: a orbe é transparente nas bordas, e só assim se vê como fica sobre um app claro e sobre um escuro. */
    private fun splitBackground(canvas: Canvas, left: Int, top: Int, w: Int, h: Int) {
        canvas.drawRect(left.toFloat(), top.toFloat(), left + w / 2f, (top + h).toFloat(), Paint().apply { color = LIGHT })
        canvas.drawRect(left + w / 2f, top.toFloat(), (left + w).toFloat(), (top + h).toFloat(), Paint().apply { color = DARK })
    }

    private fun labelPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 22f }

    private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"
}

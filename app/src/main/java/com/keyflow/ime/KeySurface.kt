package com.keyflow.ime

import android.graphics.Canvas
import android.graphics.RectF

/**
 * Como uma skin com superfície própria ([KeyboardColors.surface]) pinta a face das teclas e dos botões da barra:
 * o vidro do Aero ([GlassPainter]) ou o Glitch ([GlitchPainter]). As views (grade e barra) só chamam isto; não sabem
 * qual é a skin. Nada aqui aloca por tecla: [draw] roda no `onDraw`.
 */
interface KeySurface {
    /**
     * Antes das teclas de uma view: a lâmina atrás dela e onde a origem do canvas está sobre a lâmina
     * ([dx]/[dy], já descontada a rolagem). Só o vidro usa (posiciona o reflexo).
     */
    fun useSheet(sheet: GlassSheet?, dx: Float, dy: Float) = Unit

    /** A face de uma tecla em [bounds], na tinta [tint], [pressed] enquanto o dedo está nela. */
    fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean)

    /**
     * Como [draw], sabendo se a tecla é de letra ou de função ([role]). As skins de hoje pintam as duas pela tinta e ignoram o papel
     * (é o que este padrão faz); a skin de pacote ([com.keyflow.skins.PackKeySurface]) desenha uma face de SVG diferente para cada.
     * As views chamam ESTE método.
     */
    fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean, role: KeyRole) =
        draw(canvas, bounds, radius, tint, pressed)

    /** Quantas vezes o rótulo (texto ou ícone) é desenhado: uma, ou as camadas de cor do Glitch. */
    val labelPasses: Int get() = 1

    /** Deslocamento horizontal da passada [pass] do rótulo, em px. */
    fun labelOffsetX(pass: Int): Float = 0f

    /** Cor da passada [pass] do rótulo; [ink] é a tinta normal da tecla. */
    fun labelColor(pass: Int, ink: Int, pressed: Boolean): Int = ink

    /**
     * Intensidade (0..1) de um efeito em andamento na skin — a falha do Glitch. Quem anima ([KeyboardMotion]) muda isto
     * antes de cada quadro do evento e invalida a view; as skins sem efeito ignoram.
     */
    fun setEffect(amount: Float) = Unit
}

/**
 * Que tipo de tecla uma face representa. [KEY] é a tecla de letra; [MODIFIER] são as de função (shift, apagar, `?123`, enter, globo,
 * espaço) e os botões das barras — a divisão do FORMAT.md §2 (`keyboard_key_*` × `keyboard_modifier_*`).
 */
enum class KeyRole { KEY, MODIFIER }

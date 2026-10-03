package com.keyflow.skins

import android.graphics.drawable.Drawable
import android.util.Log
import com.keyflow.ime.KeyRole
import com.keyflow.ime.KeySurface

/**
 * O teclado de um pacote de skin, pronto para as views do IME: os SVGs das peças já lidos, as cores do manifest e as fábricas da
 * superfície das teclas ([surface]) e do fundo ([backgroundDrawable]). Uma instância por `onCreateInputView`, dividida por todas as views
 * (grade, barras, faixa de sugestões) — cada uma só tem o próprio cache de sprites.
 *
 * Abrir um pacote ([load]) é o único trabalho de IO no caminho crítico do IME: lê e faz o *parse* de no máximo cinco SVGs pequenos
 * (os frames da orbe nem são tocados aqui). Se a tecla normal não carrega, o pacote inteiro não serve e [load] devolve `null` — o
 * serviço cai no Sistema (SKP-STO-03); uma das outras peças que não carrega apenas some, e o padrão do FORMAT.md §2 cobre o buraco.
 */
class PackKeyboard private constructor(
    val pack: SkinPack,
    private val images: Map<KeyboardPart, SvgImage>,
) {
    val manifest: KeyboardManifest get() = pack.manifest.keyboard

    /** O fatiamento 3×3 das faces de tecla, se o manifest declarou. */
    val slice: SliceInsets? get() = manifest.slice

    /** Tem `keyboard_tray.svg` (e ele carregou): o fundo é o SVG esticado, não uma cor lisa. */
    val hasTray: Boolean get() = KeyboardPart.TRAY in images

    /** Faixa do sistema sob o teclado (SKP-KBD-02). */
    val navigationBarColor: Int get() = manifest.navigationBarColor

    /**
     * A tinta que as views passam para uma tecla comum (de letra ou de função): a cor dos balões. Qualquer OUTRA tinta que chegue à
     * superfície é um **acento** (shift ativo, mic gravando…) e a face é tingida por cima dela — a arte manda, mas o estado
     * da tecla ainda precisa aparecer.
     */
    val plainTint: Int get() = manifest.popup

    fun image(part: KeyboardPart): SvgImage? = images[part]

    /** Qual peça desenhar para [role] e se escurece-la: a cadeia de padrões do FORMAT.md §2. */
    fun faceFor(role: KeyRole, pressed: Boolean): Face = when (role) {
        KeyRole.KEY -> when {
            !pressed -> Face(KeyboardPart.KEY_NORMAL, darken = false)
            KeyboardPart.KEY_PRESSED in images -> Face(KeyboardPart.KEY_PRESSED, darken = false)
            else -> Face(KeyboardPart.KEY_NORMAL, darken = true)
        }
        KeyRole.MODIFIER -> {
            val normal = if (KeyboardPart.MODIFIER_NORMAL in images) KeyboardPart.MODIFIER_NORMAL else KeyboardPart.KEY_NORMAL
            when {
                !pressed -> Face(normal, darken = false)
                KeyboardPart.MODIFIER_PRESSED in images -> Face(KeyboardPart.MODIFIER_PRESSED, darken = false)
                KeyboardPart.MODIFIER_NORMAL in images -> Face(KeyboardPart.MODIFIER_NORMAL, darken = true)
                KeyboardPart.KEY_PRESSED in images -> Face(KeyboardPart.KEY_PRESSED, darken = false)
                else -> Face(KeyboardPart.KEY_NORMAL, darken = true)
            }
        }
    }

    /** A peça a desenhar e se ela vai escurecida (quando o pacote não traz a versão pressionada). */
    class Face(val part: KeyboardPart, val darken: Boolean)

    /** Uma superfície nova das teclas (uma por view: cada uma tem o próprio cache de sprites, de tamanhos diferentes). */
    fun surface(): KeySurface = PackKeySurface(this)

    /** O fundo do teclado inteiro, ou `null` quando é uma cor lisa (`keyboard.trayColor`, que as views já pintam). */
    fun backgroundDrawable(): Drawable? = images[KeyboardPart.TRAY]?.let { PackBackdrop(it, manifest.trayColor) }

    companion object {
        private const val TAG = "SKP"

        /** Abre o teclado de [pack], ou `null` se o pacote não tem teclado ou a tecla normal não carrega. Nunca lança. */
        fun load(pack: SkinPack): PackKeyboard? {
            if (!pack.hasKeyboard) return null
            val images = linkedMapOf<KeyboardPart, SvgImage>()
            for (part in KeyboardPart.entries) {
                val file = pack.keyboardFile(part) ?: continue
                val text = pack.readSvg(file)
                if (text == null) {
                    Log.w(TAG, "${pack.id}: não consegui ler ${file.name}")
                    continue
                }
                try {
                    images[part] = SvgRender.parse(text)
                } catch (e: SvgException) {
                    Log.w(TAG, "${pack.id}: ${file.name} não desenha (${e.message})")
                }
            }
            if (KeyboardPart.KEY_NORMAL !in images) return null
            return PackKeyboard(pack, images)
        }
    }
}

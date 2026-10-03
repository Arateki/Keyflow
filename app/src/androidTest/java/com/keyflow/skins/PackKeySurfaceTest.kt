package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.keyflow.ime.KeyRole
import com.keyflow.ime.KeyboardColors
import com.keyflow.ime.KeyboardSkin
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SKP-T4: a superfície de teclas do pacote desenha as faces do SVG por papel e estado (letra × função, normal × pressionada, com os
 * padrões do FORMAT.md §2), fatia em 3×3, tinge o acento sem apagar a face e — em regime — só copia um bitmap: nada de SVG por quadro.
 */
class PackKeySurfaceTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun keyboardOf(
        faces: Map<String, String>,
        extraManifest: String = "",
        name: String = "Teclado",
    ): PackKeyboard {
        val entries = listOf("manifest.json" to manifest(name, extraManifest)) + faces.map { (file, content) -> file to content }
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        return PackKeyboard.load(store.load(result.packId!!)!!)!!
    }

    private fun all(
        key: String? = "FF0000",
        keyPressed: String? = "00FF00",
        modifier: String? = "0000FF",
        modifierPressed: String? = "FFFF00",
        tray: String? = null,
    ): Map<String, String> = buildMap {
        key?.let { put("keyboard_key_normal.svg", svg(it)) }
        keyPressed?.let { put("keyboard_key_pressed.svg", svg(it)) }
        modifier?.let { put("keyboard_modifier_normal.svg", svg(it)) }
        modifierPressed?.let { put("keyboard_modifier_pressed.svg", svg(it)) }
        tray?.let { put("keyboard_tray.svg", svg(it)) }
    }

    /** Desenha uma tecla de 60×50 px no meio de um bitmap de 100×80 e devolve o bitmap. */
    private fun drawKey(surface: com.keyflow.ime.KeySurface, tint: Int, pressed: Boolean, role: KeyRole?, bounds: RectF = RectF(20f, 15f, 80f, 65f)): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (role == null) surface.draw(canvas, bounds, 7f, tint, pressed) else surface.draw(canvas, bounds, 7f, tint, pressed, role)
        return bitmap
    }

    private fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Int, tolerance: Int = 3, what: String = "") {
        val actual = bitmap.getPixel(x, y)
        val ok = abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
            abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
        assertTrue("$what pixel ($x,$y): esperava #${"%08X".format(expected)}, veio #${"%08X".format(actual)}", ok)
    }

    // ── a face certa para cada papel e estado ────────────────────────────────

    @Test
    fun eachRoleAndStateDrawsItsOwnFace() {
        val keyboard = keyboardOf(all())
        val surface = keyboard.surface()
        val plain = keyboard.plainTint
        assertPixel(drawKey(surface, plain, pressed = false, role = KeyRole.KEY), 50, 40, 0xFFFF0000.toInt(), what = "letra solta")
        assertPixel(drawKey(surface, plain, pressed = true, role = KeyRole.KEY), 50, 40, 0xFF00FF00.toInt(), what = "letra pressionada")
        assertPixel(drawKey(surface, plain, pressed = false, role = KeyRole.MODIFIER), 50, 40, 0xFF0000FF.toInt(), what = "função solta")
        assertPixel(drawKey(surface, plain, pressed = true, role = KeyRole.MODIFIER), 50, 40, 0xFFFFFF00.toInt(), what = "função pressionada")
    }

    @Test
    fun theFaceFillsExactlyTheKeyBoundsAndNothingElse() {
        val keyboard = keyboardOf(all())
        val bitmap = drawKey(keyboard.surface(), keyboard.plainTint, pressed = false, role = KeyRole.KEY)
        assertPixel(bitmap, 20, 15, 0xFFFF0000.toInt(), what = "canto sup. esq. da tecla")
        assertPixel(bitmap, 79, 64, 0xFFFF0000.toInt(), what = "canto inf. dir. da tecla")
        assertEquals("fora da tecla fica transparente", 0, Color.alpha(bitmap.getPixel(10, 40)))
        assertEquals(0, Color.alpha(bitmap.getPixel(85, 40)))
        assertEquals(0, Color.alpha(bitmap.getPixel(50, 5)))
    }

    @Test
    fun withoutARoleItIsAFunctionKey() {
        val keyboard = keyboardOf(all())
        assertPixel(drawKey(keyboard.surface(), keyboard.plainTint, pressed = false, role = null), 50, 40, 0xFF0000FF.toInt())
    }

    @Test
    fun missingPressedFacesAreTheNormalOnesDarkened() {
        // Só a tecla normal: a função usa a mesma face (FORMAT.md §2) e a pressionada é a normal 20% mais escura.
        val keyboard = keyboardOf(all(keyPressed = null, modifier = null, modifierPressed = null))
        val surface = keyboard.surface()
        val plain = keyboard.plainTint
        val darkRed = 0xFFCC0000.toInt() // 255 × 0,8
        assertPixel(drawKey(surface, plain, false, KeyRole.MODIFIER), 50, 40, 0xFFFF0000.toInt(), what = "função sem arte própria usa a tecla")
        assertPixel(drawKey(surface, plain, true, KeyRole.KEY), 50, 40, darkRed, what = "tecla pressionada escurecida")
        assertPixel(drawKey(surface, plain, true, KeyRole.MODIFIER), 50, 40, darkRed, what = "função pressionada escurecida")
    }

    @Test
    fun aModifierWithoutItsPressedFaceUsesTheDarkenedModifierNotTheKeyPressedOne() {
        val keyboard = keyboardOf(all(modifierPressed = null))
        val bitmap = drawKey(keyboard.surface(), keyboard.plainTint, pressed = true, role = KeyRole.MODIFIER)
        assertPixel(bitmap, 50, 40, 0xFF0000CC.toInt(), what = "azul 20% mais escuro, não o verde da tecla pressionada")
    }

    @Test
    fun withoutAnyModifierArtThePressedKeyFaceServesTheModifierToo() {
        val keyboard = keyboardOf(all(modifier = null, modifierPressed = null))
        assertPixel(drawKey(keyboard.surface(), keyboard.plainTint, true, KeyRole.MODIFIER), 50, 40, 0xFF00FF00.toInt())
    }

    // ── acento ───────────────────────────────────────────────────────────────

    @Test
    fun anAccentTintKeepsTheSilhouetteAndTheFaceUnderneath() {
        // Uma face com margem transparente: o acento (shift ativo, mic gravando) só pinta onde a face existe.
        val framed = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="20" y="20" width="60" height="60" fill="#FF0000"/></svg>"""
        val keyboard = keyboardOf(mapOf("keyboard_key_normal.svg" to framed))
        val teal = 0xFF4E9683.toInt()
        val bitmap = drawKey(keyboard.surface(), teal, pressed = false, role = KeyRole.KEY)
        // Em cima da face vermelha, 80% de verde-azulado: r = 0x4E·0,8 + 255·0,2 = 113, g = 0x96·0,8 = 120, b = 0x83·0,8 = 105.
        assertPixel(bitmap, 50, 40, Color.argb(255, 113, 120, 105), tolerance = 4, what = "face tingida")
        assertEquals("a margem transparente da face continua transparente", 0, Color.alpha(bitmap.getPixel(24, 18)))
    }

    @Test
    fun theCommonTintLeavesTheFaceUntouched() {
        val keyboard = keyboardOf(all())
        val plain = drawKey(keyboard.surface(), keyboard.plainTint, false, KeyRole.KEY)
        val accented = drawKey(keyboard.surface(), 0xFFC6873F.toInt(), false, KeyRole.KEY)
        assertPixel(plain, 50, 40, 0xFFFF0000.toInt())
        assertTrue("o acento muda a cor", plain.getPixel(50, 40) != accented.getPixel(50, 40))
    }

    // ── fatiamento 3×3 ───────────────────────────────────────────────────────

    private val cornersSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" fill="#FFFFFF"/>
        <rect width="24" height="24" fill="#FF0000"/><rect x="72" width="24" height="24" fill="#00FF00"/>
        <rect y="72" width="24" height="24" fill="#0000FF"/><rect x="72" y="72" width="24" height="24" fill="#FFFF00"/></svg>"""

    @Test
    fun theSliceKeepsTheCornersWhileAWideKeyStretches() {
        val sliced = keyboardOf(
            mapOf("keyboard_key_normal.svg" to cornersSvg),
            extraManifest = ""","keyboard":{"slice":{"left":24,"top":24,"right":24,"bottom":24}}""",
        )
        // Espaço: 240×48 → escala dos cantos 0,5 → cada canto 12×12 px, e o miolo branco estica.
        val bounds = RectF(0f, 0f, 240f, 48f)
        val bitmap = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)
        sliced.surface().draw(Canvas(bitmap), bounds, 7f, sliced.plainTint, false, KeyRole.KEY)
        assertPixel(bitmap, 3, 3, 0xFFFF0000.toInt(), what = "canto sup. esq.")
        assertPixel(bitmap, 236, 3, 0xFF00FF00.toInt(), what = "canto sup. dir.")
        assertPixel(bitmap, 11, 5, 0xFFFF0000.toInt(), what = "último px do canto")
        assertPixel(bitmap, 14, 5, 0xFFFFFFFF.toInt(), what = "primeiro px do miolo")

        val plain = keyboardOf(mapOf("keyboard_key_normal.svg" to cornersSvg), name = "Sem fatia")
        val stretched = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)
        plain.surface().draw(Canvas(stretched), bounds, 7f, plain.plainTint, false, KeyRole.KEY)
        assertPixel(stretched, 20, 5, 0xFFFF0000.toInt(), what = "sem fatia o canto estica até ~60 px")
    }

    // ── cache: em regime, só drawBitmap ──────────────────────────────────────

    @Test
    fun spritesAreRenderedOncePerSizeStateRoleAndAccent() {
        val keyboard = keyboardOf(all())
        val surface = keyboard.surface() as PackKeySurface
        val plain = keyboard.plainTint
        repeat(3) { drawKey(surface, plain, false, KeyRole.KEY) }
        assertEquals(1, surface.renderCount)
        assertEquals(1, surface.spriteCount)
        drawKey(surface, plain, true, KeyRole.KEY)
        drawKey(surface, plain, false, KeyRole.MODIFIER)
        drawKey(surface, plain, false, KeyRole.KEY, bounds = RectF(0f, 0f, 90f, 50f))
        drawKey(surface, 0xFF4E9683.toInt(), false, KeyRole.KEY)
        assertEquals(5, surface.renderCount)
        assertEquals(5, surface.spriteCount)
        // Tudo de novo: nenhuma rasterização a mais.
        drawKey(surface, plain, false, KeyRole.KEY)
        drawKey(surface, plain, true, KeyRole.KEY)
        drawKey(surface, plain, false, KeyRole.MODIFIER)
        drawKey(surface, 0xFF4E9683.toInt(), false, KeyRole.KEY)
        assertEquals(5, surface.renderCount)
    }

    /** Um canvas que conta o que o `onDraw` faz com ele, para provar que em regime só há um `drawBitmap` por tecla. */
    private class SpyCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        var bitmaps = 0
        var other = 0
        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            bitmaps++
            super.drawBitmap(bitmap, left, top, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) { bitmaps++; super.drawBitmap(bitmap, src, dst, paint) }
        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) { bitmaps++; super.drawBitmap(bitmap, src, dst, paint) }
        override fun drawColor(color: Int) { other++; super.drawColor(color) }
        override fun drawRect(rect: RectF, paint: Paint) { other++; super.drawRect(rect, paint) }
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) { other++; super.drawRect(left, top, right, bottom, paint) }
        override fun drawPath(path: android.graphics.Path, paint: Paint) { other++; super.drawPath(path, paint) }
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) { other++; super.drawRoundRect(rect, rx, ry, paint) }
        override fun drawPicture(picture: android.graphics.Picture) { other++; super.drawPicture(picture) }
        override fun save(): Int { other++; return super.save() }
        override fun clipRect(rect: RectF): Boolean { other++; return super.clipRect(rect) }
        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean { other++; return super.clipRect(left, top, right, bottom) }
        override fun scale(sx: Float, sy: Float) { other++; super.scale(sx, sy) }
        override fun translate(dx: Float, dy: Float) { other++; super.translate(dx, dy) }
    }

    @Test
    fun inSteadyStateDrawIsOneBitmapCopyAndNothingElse() {
        val keyboard = keyboardOf(all(), extraManifest = ""","keyboard":{"slice":{"left":24,"top":24,"right":24,"bottom":24}}""")
        val surface = keyboard.surface() as PackKeySurface
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        val bounds = RectF(20f, 15f, 80f, 65f)
        surface.draw(Canvas(bitmap), bounds, 7f, keyboard.plainTint, false, KeyRole.KEY) // aquece: gera o sprite
        val renders = surface.renderCount

        val spy = SpyCanvas(bitmap)
        repeat(50) { surface.draw(spy, bounds, 7f, keyboard.plainTint, false, KeyRole.KEY) }
        assertEquals("um drawBitmap por tecla", 50, spy.bitmaps)
        assertEquals("e mais nada no canvas da view (nem SVG, nem recorte, nem matriz)", 0, spy.other)
        assertEquals("sem rasterizar de novo", renders, surface.renderCount)
    }

    @Test
    fun theSurfaceDoesNotLeakAcrossViewsEachOneHasItsOwnCache() {
        val keyboard = keyboardOf(all())
        val a = keyboard.surface() as PackKeySurface
        val b = keyboard.surface() as PackKeySurface
        drawKey(a, keyboard.plainTint, false, KeyRole.KEY)
        assertEquals(1, a.spriteCount)
        assertEquals(0, b.spriteCount)
    }

    // ── abrir o teclado de um pacote ─────────────────────────────────────────

    @Test
    fun aPackWithoutAKeyboardOrWithABrokenKeyNormalDoesNotOpen() {
        val orbOnly = store.import(SkinPackFixtures.zip(SkinPackFixtures.pack("So Orbe")).inputStream())
        assertNull(PackKeyboard.load(store.load(orbOnly.packId!!)!!))

        val ok = store.import(SkinPackFixtures.zip(listOf("manifest.json" to manifest("Quebra"), "keyboard_key_normal.svg" to svg())).inputStream())
        val dir = File(base, "skins/${ok.packId}")
        File(dir, "keyboard_key_normal.svg").writeText("<svg viewBox=\"0 0 10 10\"><rect></svg>")
        assertNull("a tecla normal que não desenha derruba o teclado do pacote", PackKeyboard.load(store.load(ok.packId!!)!!))
    }

    @Test
    fun aBrokenOptionalPartIsSimplyMissing() {
        val keyboard = keyboardOf(all(tray = "101010"), name = "Parcial")
        val id = keyboard.pack.id
        val dir = File(base, "skins/$id")
        File(dir, "keyboard_tray.svg").writeText("isso não é svg")
        File(dir, "keyboard_modifier_normal.svg").delete()
        val reopened = PackKeyboard.load(store.load(id)!!)!!
        assertFalse("a bandeja que não desenha some", reopened.hasTray)
        assertNull(reopened.backgroundDrawable())
        assertPixel(drawKey(reopened.surface(), reopened.plainTint, false, KeyRole.MODIFIER), 50, 40, 0xFFFF0000.toInt(), what = "função sem arte cai na tecla")
    }

    // ── fundo do teclado ─────────────────────────────────────────────────────

    @Test
    fun theTrayIsStretchedOverTheWholeArea() {
        val halves = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 40" preserveAspectRatio="xMidYMid meet">
            <rect width="50" height="40" fill="#FF0000"/><rect x="50" width="50" height="40" fill="#0000FF"/></svg>"""
        val keyboard = keyboardOf(mapOf("keyboard_key_normal.svg" to svg(), "keyboard_tray.svg" to halves))
        assertTrue(keyboard.hasTray)
        val drawable = keyboard.backgroundDrawable()!!
        // 300×300 num SVG 100×40: esticar (preserveAspectRatio="none" do FORMAT.md) preenche tudo, sem faixas.
        drawable.setBounds(0, 0, 300, 300)
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        assertPixel(bitmap, 5, 5, 0xFFFF0000.toInt(), what = "esquerda, em cima")
        assertPixel(bitmap, 5, 295, 0xFFFF0000.toInt(), what = "esquerda, embaixo (sem faixa vazia)")
        assertPixel(bitmap, 295, 150, 0xFF0000FF.toInt(), what = "direita")
        // E acompanha uma nova medida.
        drawable.setBounds(0, 0, 100, 40)
        val small = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(small))
        assertPixel(small, 10, 20, 0xFFFF0000.toInt())
        assertPixel(small, 90, 20, 0xFF0000FF.toInt())
    }

    @Test
    fun anEmptyTrayDrawsNothingAndDoesNotCrash() {
        val keyboard = keyboardOf(mapOf("keyboard_key_normal.svg" to svg(), "keyboard_tray.svg" to svg("00FF00")))
        val drawable = keyboard.backgroundDrawable()!!
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap)) // sem bounds
        assertEquals(0, Color.alpha(bitmap.getPixel(5, 5)))
        drawable.setBounds(0, 0, 0, 0)
        drawable.draw(Canvas(bitmap))
        assertEquals(0, Color.alpha(bitmap.getPixel(5, 5)))
    }

    // ── KeyboardColors.forPack ───────────────────────────────────────────────

    @Test
    fun colorsOfAPackComeFromTheManifestAndTheArtOwnsTheFaces() {
        val manifestExtra = ""","keyboard":{"ink":"#FFFFFF","popup":"#112233","trayColor":"#445566","navigationBarColor":"#778899"}"""
        val withoutTray = keyboardOf(all(), manifestExtra, name = "Sem bandeja")
        val colors = KeyboardColors.forPack(withoutTray)
        assertEquals(0xFFFFFFFF.toInt(), colors.ink)
        assertEquals("o balão e a etiqueta de sugestão usam a cor dos balões", 0xFF112233.toInt(), colors.popup)
        assertEquals(colors.popup, colors.key)
        assertEquals(colors.popup, colors.modifier)
        assertEquals("sem bandeja SVG, a bandeja é a cor lisa", 0xFF445566.toInt(), colors.tray)
        assertEquals(0xFF778899.toInt(), colors.navigationBarColor(blurBehind = false))
        assertTrue(colors.hasSurface)
        assertNotNull(colors.surface(2f, false))
        assertNull("sem bandeja SVG não há fundo desenhado", colors.backgroundDrawable(2f, false))
        assertNull("os pacotes não usam o enum fechado", colors.backdrop)
        assertEquals(withoutTray.plainTint, colors.key)

        val withTray = KeyboardColors.forPack(keyboardOf(all(tray = "101010"), manifestExtra, name = "Com bandeja"))
        assertEquals("com bandeja SVG a cor lisa fica transparente para não cobri-la", 0, withTray.tray)
        assertNotNull(withTray.backgroundDrawable(2f, false))
    }

    @Test
    fun theBuiltInSkinsAreUntouchedByThePackAdditions() {
        for (skin in KeyboardSkin.entries) {
            for (dark in listOf(false, true)) {
                val legacy = KeyboardColors.of(skin, dark)
                assertEquals(legacy, KeyboardColors.of(skin, dark, null))
                assertNull(legacy.pack)
                assertEquals("hasSurface só nas skins com enum", legacy.backdrop != null, legacy.hasSurface)
            }
        }
        // O enum continua dono da própria lâmina e da faixa de navegação.
        val aero = KeyboardColors.of(KeyboardSkin.AERO, false)
        assertNotNull(aero.backgroundDrawable(2f, true))
        assertEquals(aero.backdrop!!.navigationBarColor(true), aero.navigationBarColor(true))
        assertNull(KeyboardColors.of(KeyboardSkin.SYSTEM, false).navigationBarColor(false))
        assertNull(KeyboardColors.of(KeyboardSkin.SYSTEM, false).backgroundDrawable(2f, false))
    }

    @Test
    fun aPackOverridesTheEnumSkinWhenBothAreGiven() {
        val keyboard = keyboardOf(all(), name = "Manda")
        assertSame(keyboard, KeyboardColors.of(KeyboardSkin.AERO, true, keyboard).pack)
    }
}

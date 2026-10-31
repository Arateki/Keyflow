package com.keyflow.notificacao.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.EstadoVisual
import com.keyflow.notificacao.GlifoDaOrbe
import com.keyflow.skins.SkinIds
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.centerInkOf
import com.keyflow.ui.orb.orbStyleFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Do estado ao bitmap (NTF-24, NTF-26..28, NTF-30, design §7): resolve a skin efetiva (a mesma [orbStyleFor] da orbe flutuante, então a paga sem
 * direito vira o Ateliê **sem apagar a escolha**; **Sistema**; pacote), desenha fora da thread principal, guarda num `LruCache` por bytes
 * (teto 2 MB) e cai no Ateliê quando algo falha — e [AparenciaIndisponivel] quando nem o Ateliê desenha (o host usa a mínima).
 *
 * Tudo que muda a aparência está na chave do cache (skin efetiva, versão dos pacotes, aparência, glifo, lado, modo), então nada fica velho; as
 * assinaturas de [observar] ainda soltam o cache inteiro quando o direito ou um pacote mudam.
 */
class ProvedorDeAparencia(
    private val context: Context,
    private val skinsUnlocked: () -> Boolean,
    /** Abre o pacote [skinId] (`pack:<id>`) como fonte de orbe; `null` se sumiu ou não tem o que desenhar (T8). */
    private val abrirPacote: (skinId: String) -> FonteDeOrbe? = { null },
    /** O contador de mudanças dos pacotes (`SkinPackStore.changes`), parte da chave do cache. */
    private val versaoDosPacotes: () -> Int = { 0 },
    private val renderizar: (OrbStyle, AparenciaDaOrbe, Int, GlifoDaOrbe?) -> Bitmap = { s, a, l, g -> RenderizadorDeOrbe.renderizar(s, a, l, g) },
) {
    private data class Chave(val skin: String, val versao: Int, val aparencia: AparenciaDaOrbe, val glifo: GlifoDaOrbe?, val lado: Int, val escuro: Boolean)

    private val cache = object : LruCache<Chave, AparenciaDaNotificacao>(TETO_DO_CACHE_BYTES) {
        override fun sizeOf(key: Chave, value: AparenciaDaNotificacao): Int = value.orbe.allocationByteCount
    }

    /** Quantas vezes o desenho foi de fato feito (e não vindo do cache): para os testes. */
    @Volatile
    var desenhos: Int = 0
        private set

    /** A skin que vale agora para [skinId]: o id do estilo (`atelie`…), `sistema` ou `pack:<id>`; o pago sem direito é `atelie`. */
    fun skinEfetiva(skinId: String): String = when {
        skinId == SKIN_SISTEMA -> SKIN_SISTEMA
        SkinIds.isPack(skinId) -> skinId
        else -> orbStyleFor(skinId, skinsUnlocked()).id
    }

    suspend fun obter(skinId: String, estado: EstadoVisual, ladoPx: Int, escuro: Boolean): AparenciaDaNotificacao =
        withContext(Dispatchers.Default) { obterAgora(skinId, estado.aparencia, estado.glifo, ladoPx, escuro) }

    /** A versão síncrona de [obter] (já fora da thread principal). */
    fun obterAgora(skinId: String, aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, ladoPx: Int, escuro: Boolean): AparenciaDaNotificacao {
        val skin = skinEfetiva(skinId)
        val chave = Chave(skin, versaoDosPacotes(), aparencia, glifo, ladoPx, escuro)
        cache.get(chave)?.let { return it }
        val feita = try {
            desenhar(skin, aparencia, glifo, ladoPx, escuro)
        } catch (_: Exception) {
            null
        } ?: atelie(aparencia, glifo, ladoPx, escuro)
        desenhos++
        cache.put(chave, feita)
        return feita
    }

    /** Solta o cache (o direito de skins ou um pacote mudou). */
    fun invalidar() = cache.evictAll()

    fun observar(escopo: CoroutineScope, direito: Flow<Boolean>, pacotes: Flow<Int>) {
        escopo.launch { direito.collect { invalidar() } }
        escopo.launch { pacotes.collect { invalidar() } }
    }

    private fun desenhar(skin: String, aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, lado: Int, escuro: Boolean): AparenciaDaNotificacao? = when {
        skin == SKIN_SISTEMA -> sistema(aparencia, glifo, lado)
        SkinIds.isPack(skin) -> abrirPacote(skin)?.desenhar(aparencia, glifo, lado, escuro)
        else -> {
            val estilo = OrbStyle.entries.first { it.id == skin }
            AparenciaDaNotificacao(
                orbe = renderizar(estilo, aparencia, lado, glifo),
                tinta = centerInkOf(estilo).toArgb(),
                fundo = if (estilo == OrbStyle.ATELIE) (if (escuro) FUNDO_ESCURO else FUNDO_CLARO) else null,
            )
        }
    }

    private fun atelie(aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, lado: Int, escuro: Boolean): AparenciaDaNotificacao = try {
        desenhar(OrbStyle.ATELIE.id, aparencia, glifo, lado, escuro)!!
    } catch (e: Exception) {
        throw AparenciaIndisponivel(e)
    }

    /** O círculo liso na cor de destaque do sistema (Android 12+; uma cor fixa antes), com o glifo; sem fundo próprio. */
    private fun sistema(aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, lado: Int): AparenciaDaNotificacao {
        val cor = corDeDestaque()
        val orbe = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        Canvas(orbe).drawCircle(lado / 2f, lado / 2f, lado / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cor })
        val tinta = if (luminancia(cor) > 0.5) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt()
        if (glifo != null) RenderizadorDeOrbe.glifoSobre(orbe, glifo, tinta)
        return AparenciaDaNotificacao(orbe, tinta, null)
    }

    private fun corDeDestaque(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(android.R.color.system_accent1_500) else COR_DE_DESTAQUE_ANTES_DO_12

    private fun luminancia(c: Int): Double =
        (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0

    companion object {
        const val SKIN_SISTEMA = "sistema"
        const val TETO_DO_CACHE_BYTES = 2 * 1024 * 1024
        const val FUNDO_ESCURO = 0xFF2B2720.toInt()
        const val FUNDO_CLARO = 0xFFF1E7C9.toInt()
        const val COR_DE_DESTAQUE_ANTES_DO_12 = 0xFF14B8A6.toInt()
    }
}

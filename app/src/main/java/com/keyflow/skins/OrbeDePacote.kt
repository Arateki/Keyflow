package com.keyflow.skins

import android.graphics.Bitmap
import android.util.Log
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.GlifoDaOrbe
import com.keyflow.notificacao.app.AparenciaDaNotificacao
import com.keyflow.notificacao.app.FonteDeOrbe
import com.keyflow.notificacao.app.RenderizadorDeOrbe

/**
 * A fonte de orbe da notificação que vem de um pacote de skin (NTF-31..34, design §7). Com a seção `notification` desenha o
 * `notification_<estado>.svg` (estado ausente usa `idle`), na `notification.ink` e com o `notification.background` (opaco, igual nos modos
 * claro e escuro; sem ele, o fundo do sistema). Um pacote **só de orbe**, escolhido pelo "copiar", desenha o **primeiro quadro** de
 * `orb_<estado>_<menor>.svg` na `orb.centerInk`, sem fundo (NTF-34). Sem nenhum dos dois, não desenha (`null`): o provedor cai no Ateliê.
 * O glifo do estado vai por cima, na tinta da skin; o tempo é do `Chronometer`, nunca do SVG.
 */
class OrbeDePacote(private val pack: SkinPack) : FonteDeOrbe {

    override fun desenhar(aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, ladoPx: Int, escuro: Boolean): AparenciaDaNotificacao? {
        if (ladoPx <= 0) return null
        val estado = OrbPackState.ofKey(aparencia.name.lowercase()) ?: OrbPackState.IDLE
        val manifest = pack.manifest
        val (arquivo, tinta, fundo) = when {
            pack.hasNotification -> Triple(pack.notificationFile(estado), manifest.notification.ink, manifest.notification.background)
            pack.hasOrb -> Triple(pack.framesOf(estado).firstOrNull(), manifest.orb.centerInk, null)
            else -> return null
        }
        val texto = arquivo?.let { pack.readSvg(it) } ?: return null
        val orbe: Bitmap = try {
            SvgRender.toBitmap(SvgRender.parse(texto), ladoPx, ladoPx, SvgFit.MEET)
        } catch (e: Exception) {
            Log.w("NTF", "${pack.id}: a orbe da notificação não desenhou (${e.message})")
            return null
        }
        val copia = if (orbe.isMutable) orbe else orbe.copy(Bitmap.Config.ARGB_8888, true)
        if (glifo != null) RenderizadorDeOrbe.glifoSobre(copia, glifo, tinta)
        return AparenciaDaNotificacao(copia, tinta, fundo)
    }
}

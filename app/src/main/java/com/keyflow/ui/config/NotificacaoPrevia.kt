package com.keyflow.ui.config

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.core.ButtonState
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLocale
import com.keyflow.i18n.tr
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.EstadoVisualDaNotificacao
import com.keyflow.notificacao.OrcamentoDeBitmaps
import com.keyflow.notificacao.efetiva
import com.keyflow.notificacao.app.NotificacaoDaOrbe
import com.keyflow.notificacao.app.ProvedorDeAparencia
import com.keyflow.skins.OrbeDePacote
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class EstadoDePrevia(val estado: ButtonState, val visao: Boolean = false) {
    OCIOSA(ButtonState.IDLE_NO_FIELD), GRAVANDO(ButtonState.RECORDING), PAUSADA(ButtonState.PAUSED),
    TRANSCREVENDO(ButtonState.SENDING), CANCELANDO(ButtonState.CANCELLING), ERRO(ButtonState.ERROR),
    VISAO(ButtonState.IDLE_NO_FIELD, true);

    fun rotulo(): String = when (this) {
        OCIOSA -> tr("Ociosa")
        GRAVANDO -> tr("Gravando")
        PAUSADA -> tr("Pausada")
        TRANSCREVENDO -> tr("Transcrevendo")
        CANCELANDO -> tr("Cancelando")
        ERRO -> tr("Erro")
        VISAO -> tr("Visão")
    }
}

/** NTF-T13: mesma linha do notify, no tema do sistema, com tempo fixo e sem executar PendingIntents. */
@Composable
internal fun NotificacaoPrevia(config: ConfigNotificacao, settings: Settings, skinsUnlocked: Boolean, store: SkinPackStore) {
    val contexto = LocalContext.current.applicationContext
    val escuro = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val densidade = LocalDensity.current.density
    val versao by SkinPackStore.changes.collectAsStateOnMain()
    val idioma = AppLocale.current
    val e = efetiva(config, settings)
    var estado by remember { mutableStateOf(EstadoDePrevia.OCIOSA) }
    val provedor = remember(contexto, store, skinsUnlocked) { ProvedorDeAparencia(contexto, { skinsUnlocked },
        abrirPacote = { id -> store.loadBySkinId(id)?.let(::OrbeDePacote) }, versaoDosPacotes = { SkinPackStore.changes.value }) }
    val escolhido = estado
    val linha by produceState<ResultadoDePrevia?>(null, e, escolhido, escuro, densidade, versao, provedor, idioma) {
        withContext(Dispatchers.Main.immediate) { value = null }
        val feita = try {
            withContext(Dispatchers.Default) {
                val visual = EstadoVisualDaNotificacao.para(escolhido.estado, escolhido.visao, e)
                val aparencia = provedor.obter(e.skin, visual, OrcamentoDeBitmaps.ladoPx(densidade), escuro)
                ResultadoDePrevia(NotificacaoDaOrbe(contexto, NotificacaoDaOrbe.CANAL_ORBE)
                    .galeria(escolhido.estado, escolhido.visao, aparencia, e, escuro, segundos = 12))
            }
        } catch (erro: CancellationException) { throw erro }
        catch (_: Throwable) { ResultadoDePrevia(null) }
        withContext(Dispatchers.Main.immediate) { value = feita }
    }
    Column(Modifier.testTag("notificacao-previa")) {
        Text(tr("Prévia"), color = LocalKeyFlowPalette.current.fg)
        Dropdown(tr("Estado da prévia"), EstadoDePrevia.entries.map { it.name }, estado.name,
            { estado = EstadoDePrevia.valueOf(it) }, "notificacao-previa-estado",
            display = { EstadoDePrevia.valueOf(it).rotulo() },
            fieldModifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = tr("Estado da prévia") })
        val pronta = linha
        if (pronta == null) Text(tr("Preparando a prévia da notificação…"), color = LocalKeyFlowPalette.current.muted)
        else if (pronta.linha == null) Text(tr("Não consegui preparar a prévia da notificação."), color = LocalKeyFlowPalette.current.muted)
        else AndroidView(
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .background(if (escuro) Color(0xFF202124) else Color.White)
                .semantics { contentDescription = tr("Prévia da notificação: %s", estado.rotulo()) }
                .testTag("notificacao-previa-pronta"),
            factory = { ctx -> FrameLayout(ctx).apply {
                tag = "notificacao-previa-android"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } },
            update = { pai ->
                pai.removeAllViews()
                val conteudo = pronta.linha.apply(pai.context, pai)
                tornarPreviaPassiva(conteudo)
                pai.addView(conteudo)
            },
        )
    }
}

private data class ResultadoDePrevia(val linha: RemoteViews?)

/** A linha real traz PendingIntents; a amostra nunca grava, pausa ou abre o app. */
private fun tornarPreviaPassiva(view: View) {
    view.setOnClickListener(null)
    view.isClickable = false
    view.isFocusable = false
    if (view is ViewGroup) for (i in 0 until view.childCount) tornarPreviaPassiva(view.getChildAt(i))
}

package com.keyflow.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.data.Settings
import com.keyflow.fluxos.AbaDaEtapa
import com.keyflow.fluxos.BotoesDeFluxo
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.Gatilho
import com.keyflow.fluxos.ModeloOferecido
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.Superficie
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.ui.config.AtalhosDoModal
import com.keyflow.ui.config.FonteDaTela
import com.keyflow.ui.config.FluxosLista
import com.keyflow.ui.config.FluxosModalConteudo
import com.keyflow.ui.config.OverlayPanel
import com.keyflow.ui.config.Tela
import com.keyflow.ui.theme.LocalKeyFlowPalette

/**
 * Rotas da aba Fluxos (FLX-53). Fixtures fixos: ids e nomes escritos aqui, a semente dos 3 padrão (`s-<builtinId>`), nenhum relógio
 * nem execução em andamento (o estado "rodando" não é golden). As telas são as puras (`FluxosLista`, `FluxosModalConteudo`), as
 * mesmas que o painel liga ao DataStore.
 */
internal object FluxosRoutes {
    private val settings: Settings = FakeSettings.settings.copy(shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME)
    private val padroes = PadroesFluxo.semear()
    private val ouvir = padroes[0]
    private val agente = padroes[2]
    private val rascunho = Fluxo("rascunho", "Rascunho", etapas = listOf(Etapa.Perguntar()))
    private val toque2 = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2)
    private val toque3 = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3)

    private val semAtalho = ConfigFluxos(padroes, emptyList())
    private val comAtalhos = ConfigFluxos(
        padroes + rascunho,
        listOf(Gatilho(toque2, ouvir.id), Gatilho(Superficie.Barra("a1b2c3"), agente.id), Gatilho(toque3, rascunho.id)),
    )

    private val fonte = FonteDaTela(
        settings,
        instalados = mapOf(AbaDaEtapa.TRANSCRICAO to listOf(ModeloOferecido("whisper-base", "Whisper Base"))),
        conectados = mapOf(AbaDaEtapa.TRANSCRICAO to setOf("OPENAI"), AbaDaEtapa.RACIOCINIO to setOf("OPENAI")),
    )

    @Composable
    private fun Cena(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(horizontal = 16.dp)) { content() }
    }

    private fun lista(key: String, config: ConfigFluxos, restaurar: Boolean = false) = Route(key, minDelayMs = if (restaurar) 1000L else 0L) {
        Cena { FluxosLista(config, settings, EstadoExecucao.Ocioso, onOpen = {}, onNovo = {}, onRestaurar = {}, restaurarAberto = restaurar) }
    }

    /** O modal com o [fluxo] e as [config] de gatilhos (as linhas de atalho leem delas); [inicial] é a tela com que abre. */
    private fun modal(key: String, fluxo: Fluxo, config: ConfigFluxos, inicial: Tela = Tela.Principal, escondeAtalhos: Boolean = false) =
        Route(key, minDelayMs = 1000L) {
            Cena {
                FluxosModalConteudo(
                    fluxo = fluxo, estado = EstadoExecucao.Ocioso, settings = settings, onDismiss = {}, onAlterar = {}, fonte = fonte,
                    atalhos = if (escondeAtalhos) null else AtalhosDoModal(config, {}, {}, {}, {}),
                    onExcluir = {}, telaInicial = inicial,
                )
            }
        }

    val rotas: List<Route> = listOf(
        lista("config-fluxos", semAtalho),
        lista("config-fluxos-atalhos", comAtalhos),
        lista("config-fluxos-vazio", ConfigFluxos(emptyList(), emptyList())),
        lista("config-fluxos-restaurar", ConfigFluxos(listOf(ouvir.copy(etapas = listOf(Etapa.Ditar(), Etapa.Inserir))), emptyList()), restaurar = true),
        modal("config-fluxos-modal", ouvir, semAtalho),
        modal("config-fluxos-modal-etapa-nova", ouvir.copy(etapas = listOf(Etapa.Ditar())), semAtalho, Tela.NovaEtapa),
        modal("config-fluxos-modal-modelo", ouvir, semAtalho, Tela.Modelo(0)),
        modal("config-fluxos-modal-incompleto", rascunho, ConfigFluxos(listOf(rascunho), emptyList())),
        modal("config-fluxos-atalho-substitui", ouvir, semAtalho, Tela.Ligar(toque2, "Isto substitui “Retrair / expandir teclado” neste gesto da orbe flutuante.")),
        modal("config-fluxos-atalho-ocupado", agente, comAtalhos, Tela.Ligar(toque2, "Isto substitui “Fluxo: Ouvir e responder” neste gesto da orbe flutuante.")),
        modal("config-fluxos-excluir", ouvir, comAtalhos, Tela.Excluir),
        Route("keyboard-barra-fluxo") {
            val dark = LocalKeyFlowPalette.current.isDark
            val barras = BotoesDeFluxo.comBotao(BotoesDeFluxo.comBotao(BarsConfig.DEFAULT.copy(mirrored = false), "a1b2c3"), "d4e5f6")
            Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg), contentAlignment = Alignment.BottomCenter) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                    factory = { ctx ->
                        // a1b2c3 tem gatilho válido (botão ligado); d4e5f6 não tem (desligado).
                        KeyFlowKeyboardView(context = ctx, onKey = {}, onMic = {}, onHide = {}, dark = dark, frozenSeconds = 12,
                            barsConfig = barras, flowButtons = mapOf("a1b2c3" to agente.id)).apply { setExpanded(false) }
                    },
                )
            }
        },
        Route("config-sobrepor-atalho-fluxo") {
            Cena {
                OverlayPanel(settings = settings.copy(overlayModeEnabled = true), onSettingsChange = {}, onUnlockSkins = {},
                    configFluxos = comAtalhos, rolarAoFim = true)
            }
        },
    )
}

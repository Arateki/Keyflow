package com.keyflow.ui.config

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.DicaDeAtalho
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.LIMITES
import com.keyflow.fluxos.ModeloRef
import com.keyflow.fluxos.OpcaoDeModelo
import com.keyflow.fluxos.agenteSemFerramentas
import com.keyflow.fluxos.opcoesSoDaAba
import com.keyflow.fluxos.BotoesDeFluxo
import com.keyflow.fluxos.Ids
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.PoliticaGatilhos
import com.keyflow.fluxos.Superficie
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.ValidadorFluxo
import com.keyflow.fluxos.app.nomeExibido
import com.keyflow.fluxos.comModelo
import com.keyflow.fluxos.comTexto
import com.keyflow.fluxos.etapaNova
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.reordenar
import com.keyflow.i18n.tr
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

const val FLUXOS_MODAL_FECHAR_TAG = "fluxos-modal-fechar"
const val FLUXOS_RODAR_TAG = "fluxos-rodar-agora"

/** De onde vêm as opções do seletor de modelo por etapa: o mesmo dado que as abas das capacidades usam (design §11.3). */
fun interface FonteDeModelos {
    fun opcoes(etapa: Etapa): List<OpcaoDeModelo>

    /** O Agente desta etapa só responde em texto? Liga a nota "só usa ferramentas com chave de API". */
    fun semFerramentas(etapa: Etapa): Boolean = true

    companion object {
        /** Sem fiação (testes, galeria): só "Da aba" das [settings] recebidas, sem afirmar o que está instalado ou conectado. */
        fun daAba(settings: Settings): FonteDeModelos = object : FonteDeModelos {
            override fun opcoes(etapa: Etapa) = opcoesSoDaAba(etapa, settings)
            override fun semFerramentas(etapa: Etapa) = agenteSemFerramentas(etapa, settings)
        }
    }
}

/**
 * Modal de um fluxo ligado ao DataStore: lê `configFluxosFlow()` e grava cada mudança na hora por `atualizarFluxos` (a edição
 * usa o valor da transação, não uma cópia da tela). Se o fluxo some (excluído em outro lugar), fecha.
 */
@Composable
fun FluxosModal(
    fluxoId: String,
    repository: SettingsRepository,
    settings: Settings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fonte: FonteDeModelos? = null,
    onAbrirAba: (ConfigTab) -> Unit = {},
    onCancelar: () -> Unit = OverlayService::cancelarFluxo,
    onRodar: (Context, String) -> Unit = { contexto, id -> OverlayService.start(contexto, OverlayService.runFlowNowIntent(contexto, id)) },
) {
    val configuracoes = remember(repository) { repository.configFluxosFlow() }
    val config by configuracoes.collectAsStateOnMain(initial = null)
    val estado by KeyFlowController.fluxo.collectAsStateOnMain()
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var erro by remember { mutableStateOf(false) }
    val atual = config
    val fluxo = atual?.fluxo(fluxoId)
    // O fluxo sumiu do armazenamento (excluído aqui ou em outro lugar): só então o modal fecha, nunca antes de persistir.
    if (atual != null && fluxo == null) { LaunchedEffect(fluxoId) { onDismiss() }; return }
    if (fluxo == null || atual == null) return
    fun gravar(acao: suspend () -> Unit) {
        scope.launch {
            try { acao(); erro = false } catch (e: CancellationException) { throw e } catch (_: Exception) { erro = true }
        }
    }
    val atalhos = AtalhosDoModal(
        config = atual,
        onLigar = { s -> gravar { repository.atualizarFluxos { PoliticaGatilhos.ligar(it, fluxoId, s) } } },
        onDesligar = { s -> gravar { repository.atualizarFluxos { PoliticaGatilhos.desligar(it, s) } } },
        onAdicionarBotao = {
            gravar {
                repository.atualizarFluxosEBarras { c, b ->
                    val id = Ids.botao()
                    val ligada = PoliticaGatilhos.ligar(c, fluxoId, Superficie.Barra(id))
                    // Recusada (fluxo incompleto, limite): nem o botão nasce, os dois andam juntos.
                    if (ligada == c) c to b else ligada to BotoesDeFluxo.comBotao(b, id)
                }
            }
        },
        onRemoverBotao = { id ->
            gravar { repository.atualizarFluxosEBarras { c, b -> PoliticaGatilhos.desligar(c, Superficie.Barra(id)) to BotoesDeFluxo.semBotoes(b, setOf(id)) } }
        },
    )
    Column(modifier) {
        FluxosModalConteudo(
            fluxo = fluxo, estado = estado, settings = settings, fonte = fonte, onDismiss = onDismiss, atalhos = atalhos,
            onExcluir = {
                // Cancela o que estava rodando deste fluxo; os botões do teclado saem na mesma transação. A gravação não depende do
                // escopo do modal (que sai da tela junto): termina mesmo assim, e o modal só fecha depois de persistir.
                if ((estado as? EstadoExecucao.Rodando)?.fluxoId == fluxoId) onCancelar()
                gravar {
                    withContext(NonCancellable) {
                        repository.atualizarFluxosEBarras { c, b ->
                            val exclusao = PoliticaGatilhos.excluir(c, fluxoId)
                            exclusao.config to BotoesDeFluxo.semBotoes(b, exclusao.botoes.toSet())
                        }
                    }
                }
            },
            onAlterar = { alterar ->
                scope.launch {
                    try {
                        repository.atualizarFluxos { c -> c.copy(fluxos = c.fluxos.map { if (it.id == fluxoId) alterar(it) else it }) }
                        erro = false
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { erro = true }
                }
            },
            onAbrirAba = onAbrirAba, onCancelar = onCancelar, onRodar = { onRodar(contexto, fluxoId) }, erroAoSalvar = erro,
        )
    }
}

private sealed interface Tela {
    data object Principal : Tela
    data object NovaEtapa : Tela
    data class Modelo(val indice: Int) : Tela
    data class Ligar(val superficie: Superficie.Orbe, val aviso: String) : Tela
    data object Excluir : Tela
}

/**
 * O modal sem efeitos: [onAlterar] recebe uma transformação do fluxo (a mesma que o wrapper aplica dentro da transação). Três
 * telas no mesmo [ConfigModal] (principal, "+ Etapa" e modelo da etapa), a seta volta de uma para a principal.
 */
@Composable
fun FluxosModalConteudo(
    fluxo: Fluxo,
    estado: EstadoExecucao,
    settings: Settings,
    onDismiss: () -> Unit,
    onAlterar: ((Fluxo) -> Fluxo) -> Unit,
    modifier: Modifier = Modifier,
    fonte: FonteDeModelos? = null,
    onAbrirAba: (ConfigTab) -> Unit = {},
    onCancelar: () -> Unit = {},
    onRodar: () -> Unit = {},
    erroAoSalvar: Boolean = false,
    /** Seção Atalhos; `null` (galeria, testes de etapas) não a mostra. */
    atalhos: AtalhosDoModal? = null,
    /** Excluir o fluxo (depois da confirmação); `null` não mostra o botão. */
    onExcluir: (() -> Unit)? = null,
) {
    var tela by remember { mutableStateOf<Tela>(Tela.Principal) }
    val fonteDeModelos = fonte ?: remember(settings) { FonteDeModelos.daAba(settings) }
    val nomeNaTela = PadroesFluxo.nomeExibido(fluxo)
    val etapaDoModelo = (tela as? Tela.Modelo)?.let { fluxo.etapas.getOrNull(it.indice) }
    if (tela is Tela.Modelo && etapaDoModelo == null) tela = Tela.Principal
    val titulo = when (tela) {
        Tela.Principal -> nomeNaTela
        Tela.NovaEtapa -> tr("Nova etapa")
        is Tela.Modelo -> tr("Modelo · %s", etapaDoModelo?.tipo?.nomeExibido().orEmpty())
        is Tela.Ligar -> tr("Ligar “%s”", nomeNaTela)
        Tela.Excluir -> tr("Excluir fluxo")
    }
    ConfigModal(
        titulo, onDismiss, FLUXOS_MODAL_FECHAR_TAG, modifier, cardTag = "fluxos-modal",
        onBack = if (tela == Tela.Principal) null else ({ tela = Tela.Principal }), backTag = "fluxos-modal-voltar",
        accessibleHeader = true,
    ) {
        when (val atual = tela) {
            Tela.Principal -> Principal(fluxo, estado, settings, fonteDeModelos, onAlterar, onCancelar, onRodar, erroAoSalvar,
                atalhos, onExcluir?.let { { tela = Tela.Excluir } },
                onNovaEtapa = { tela = Tela.NovaEtapa }, onModelo = { tela = Tela.Modelo(it) },
                onConfirmarLigar = { sup, aviso -> tela = Tela.Ligar(sup, aviso) })
            is Tela.Ligar -> ConfirmacaoDoModal(
                linhas = listOf(tr("Gesto: %d toques", atual.superficie.toques), atual.aviso, tr("Desligue o atalho e a ação volta.")),
                textoConfirmar = tr("Ligar"), tagConfirmar = "fluxos-confirmacao-ligar",
                onCancelar = { tela = Tela.Principal },
                onConfirmar = { atalhos?.onLigar(atual.superficie); tela = Tela.Principal },
            )
            Tela.Excluir -> ConfirmacaoDoModal(
                linhas = listOf(tr("Excluir “%s”? Os atalhos e os botões do teclado ligados a ele também saem.", nomeNaTela)),
                textoConfirmar = tr("Excluir"), tagConfirmar = "fluxos-excluir-confirmar",
                onCancelar = { tela = Tela.Principal },
                onConfirmar = { onExcluir?.invoke() },
            )
            Tela.NovaEtapa -> SeletorDeEtapa(fluxo, onEscolher = { tipo ->
                etapaNova(tipo)?.let { nova -> onAlterar { f -> if (f.etapas.size < LIMITES.ETAPAS) f.copy(etapas = f.etapas + nova) else f } }
                tela = Tela.Principal
            })
            is Tela.Modelo -> etapaDoModelo?.let { etapa ->
                FluxosSeletorModeloConteudo(etapa, fonteDeModelos.opcoes(etapa), notaDoAgente = fonteDeModelos.semFerramentas(etapa),
                    onEscolher = { ref -> trocarEtapa(onAlterar, atual.indice, etapa) { it.comModelo(ref) }; tela = Tela.Principal },
                    onConectar = { aba -> onAbrirAba(abaDe(aba)) })
            }
        }
    }
}

private fun trocarEtapa(onAlterar: ((Fluxo) -> Fluxo) -> Unit, indice: Int, esperada: Etapa, nova: (Etapa) -> Etapa) {
    onAlterar { f ->
        // Só troca se a etapa ainda é a que a pessoa viu (o fluxo pode ter mudado entre o toque e a gravação).
        if (f.etapas.getOrNull(indice) != esperada) f else f.copy(etapas = f.etapas.mapIndexed { i, e -> if (i == indice) nova(e) else e })
    }
}

@Composable
private fun Principal(
    fluxo: Fluxo,
    estado: EstadoExecucao,
    settings: Settings,
    fonte: FonteDeModelos,
    onAlterar: ((Fluxo) -> Fluxo) -> Unit,
    onCancelar: () -> Unit,
    onRodar: () -> Unit,
    erroAoSalvar: Boolean,
    atalhos: AtalhosDoModal?,
    onExcluir: (() -> Unit)?,
    onNovaEtapa: () -> Unit,
    onModelo: (Int) -> Unit,
    onConfirmarLigar: (Superficie.Orbe, String) -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val validacao = ValidadorFluxo.validar(fluxo)
    val rodando = estado as? EstadoExecucao.Rodando
    val esteRodando = rodando?.takeIf { it.fluxoId == fluxo.id }
    // Um ModeloRef que sumiu marca a etapa e tira o fluxo do "100% no aparelho": o selo diz Indisponível.
    val indisponiveis = fluxo.etapas.indices.filter { i -> fonte.opcoes(fluxo.etapas[i]).any { it is OpcaoDeModelo.Indisponivel } }.toSet()
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (erroAoSalvar) SettingSub(tr("Não foi possível salvar o fluxo."))
        CampoDeTexto(
            valor = PadroesFluxo.nomeExibido(fluxo), rotulo = tr("Nome"), tag = "fluxos-modal-nome", limite = LIMITES.NOME,
            vazioVolta = true,
            onCommit = { novo -> onAlterar { f -> if (f.nome == novo || PadroesFluxo.nomeExibido(f) == novo) f else f.copy(nome = novo.take(LIMITES.NOME)) } },
        )
        Text(if (indisponiveis.isNotEmpty()) tr("Indisponível") else seloDoFluxo(settings, fluxo),
            color = if (indisponiveis.isNotEmpty()) palette.terracotta else palette.teal, fontFamily = SpaceMono, fontSize = 10.sp,
            modifier = Modifier.testTag("fluxos-modal-selo"))
        PadroesFluxo.dicaDe(fluxo)?.let { dica ->
            SettingSub(tr("Sugestão: %s", when (dica) {
                DicaDeAtalho.DOIS_TOQUES -> tr("ligar em 2 toques da orbe")
                DicaDeAtalho.TRES_TOQUES -> tr("ligar em 3 toques da orbe")
                DicaDeAtalho.BOTAO_DO_TECLADO -> tr("adicionar um botão à barra do teclado")
            }), Modifier.testTag("fluxos-modal-sugestao"))
        }
        Eyebrow(tr("Etapas"))
        if (fluxo.etapas.isEmpty()) InfoNote(tr("Toque em “+ Etapa” para começar. Ditar e Capturar abrem o fluxo."), "fluxos-modal-guia")
        EtapasLista(
            fluxo, validacao.problemas, fonte::opcoes, indisponiveis,
            onMover = { de, para -> onAlterar { f -> f.copy(etapas = reordenar(f.etapas, de, para)) } },
            onRemover = { i -> onAlterar { f -> f.copy(etapas = f.etapas.filterIndexed { j, _ -> j != i }) } },
            onTexto = { i, texto -> onAlterar { f -> f.copy(etapas = f.etapas.mapIndexed { j, e -> if (j == i) e.comTexto(texto) else e }) } },
            onModelo = onModelo,
        )
        if (!validacao.valido) Text("⚠ " + tr("Incompleto"), color = palette.terracotta, fontFamily = SpaceMono, fontSize = 11.sp,
            modifier = Modifier.testTag("fluxos-modal-incompleto"))
        validacao.avisos.forEach { InfoNote(it.texto, "fluxos-modal-aviso") }
        val cheio = fluxo.etapas.size >= LIMITES.ETAPAS
        BotaoContorno(tr("+ Etapa"), "fluxos-add-etapa", !cheio, onNovaEtapa)
        if (cheio) SettingSub(tr("No máximo 8 etapas"), Modifier.testTag("fluxos-add-etapa-motivo"))
        val motivo = when {
            esteRodando != null -> null
            rodando != null -> tr("Já há um fluxo em andamento")
            !validacao.valido -> tr("Complete as etapas para rodar")
            else -> null
        }
        if (esteRodando != null) {
            Text(tr("Em execução · %s", esteRodando.etapa.nomeExibido()), color = palette.teal, fontFamily = SpaceMono, fontSize = 11.sp)
            SecondaryButton(tr("Cancelar"), "fluxos-modal-cancelar", onCancelar, Modifier.heightIn(min = 48.dp))
        } else {
            PrimaryButton(tr("Rodar agora"), FLUXOS_RODAR_TAG, onRodar, Modifier.heightIn(min = 48.dp), enabled = motivo == null && validacao.valido)
            if (motivo != null) SettingSub(motivo, Modifier.testTag("fluxos-rodar-motivo"))
        }
        if (atalhos != null) FluxosAtalhosSecao(fluxo, atalhos, settings, onConfirmarLigar)
        if (onExcluir != null) SecondaryButton(tr("Excluir fluxo"), "fluxos-excluir", onExcluir, Modifier.heightIn(min = 48.dp), accent = palette.terracotta)
    }
}

/** Como o botão secundário, mas que desliga de verdade (semântica e toque) quando [habilitado] é falso. */
@Composable
private fun BotaoContorno(texto: String, tag: String, habilitado: Boolean, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val forma = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (habilitado) 1f else 0.4f).clip(forma).border(1.dp, palette.line, forma)
            .clickable(enabled = habilitado, role = Role.Button, onClick = onClick).testTag(tag).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp)
    }
}

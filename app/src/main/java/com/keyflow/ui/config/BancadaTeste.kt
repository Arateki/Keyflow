package com.keyflow.ui.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.corpus.TesteDef
import com.keyflow.bancada.executor.EstadoBancada
import com.keyflow.bancada.executor.EstimativaDeRodada
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.bancada.executor.Perfil
import com.keyflow.bancada.executor.Progresso
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.Historico
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.ResultadoCaso
import com.keyflow.bancada.resultado.ResultadoTeste
import com.keyflow.bancada.resultado.Variacao
import com.keyflow.bancada.resultado.epochDeIso
import com.keyflow.i18n.tr
import com.keyflow.bancada.executor.MotorDeCapacidade
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val BANCADA_DESCRICAO_TAG = "bancada-descricao"
const val BANCADA_MOTIVO_TAG = "bancada-motivo"
const val BANCADA_RODAR_TAG = "bancada-rodar"
const val BANCADA_BATERIA_TAG = "bancada-bateria"
const val BANCADA_PROGRESSO_TAG = "bancada-progresso"
const val BANCADA_PAUSA_TAG = "bancada-pausa"
const val BANCADA_ERRO_TAG = "bancada-erro"
const val BANCADA_RESULTADO_TAG = "bancada-resultado"
const val BANCADA_NOTA_TAG = "bancada-nota"
const val BANCADA_MEDIDAS_TAG = "bancada-medidas"
const val BANCADA_CONTEXTO_TAG = "bancada-contexto"
const val BANCADA_VARIACAO_TAG = "bancada-variacao"
const val BANCADA_INCOMPLETA_TAG = "bancada-incompleta"
const val BANCADA_HISTORICO_TAG = "bancada-historico"
const val BANCADA_CASOS_TOGGLE_TAG = "bancada-casos-toggle"
const val BANCADA_EXPORTAR_TAG = "bancada-exportar"
const val BANCADA_CUSTO_TAG = "bancada-custo"
const val BANCADA_CUSTO_CONFIRMAR_TAG = "bancada-custo-confirmar"
const val BANCADA_CUSTO_COMPLETO_TAG = "bancada-custo-completo"
const val BANCADA_CUSTO_CANCELAR_TAG = "bancada-custo-cancelar"
const val BANCADA_CUSTO_FECHAR_TAG = "bancada-custo-fechar"

fun bancadaHistoricoLinhaTag(id: String) = "bancada-historico-$id"
fun bancadaCasoTag(id: String) = "bancada-caso-$id"

/** O que o usuário pediu e ainda não confirmou (só a nuvem confirma, BON-01): quais testes e se a confirmação "completo" já foi dada. */
private data class RodadaPendente(val testes: List<String>, val motor: MotorDeCapacidade, val modelo: ModeloEscolhido, val confirmandoCompleto: Boolean = false)

/**
 * O conteúdo de uma sub-aba de teste (BNC-02): o que o teste mede, Rodar/Parar, o progresso, o último resultado, o histórico deste modelo e o
 * detalhe por caso. Também "Rodar a bateria" (BNC-04) e exportar o JSON (BHS-04).
 *
 * Com modelo da nuvem, **nenhuma chamada sai antes de o usuário confirmar** o aviso de custo (BON-01); no login por assinatura o aviso diz que consome o
 * limite do plano e a rodada reduzida (2 casos por teste) vem primeiro; a completa pede uma segunda confirmação (BON-02). Parar cancela a chamada HTTP de
 * verdade (BON-05). Sem preço informado, nenhum custo em dinheiro é mostrado — a bancada não leva tabela de preços (BEX-05).
 */
@Composable
internal fun BancadaTesteConteudo(
    def: TesteDef,
    abas: List<BancadaSubAba>,
    motivo: String?,
    motor: MotorDeCapacidade?,
    modelo: ModeloEscolhido?,
    servicos: BancadaServicos,
    modifier: Modifier = Modifier,
    preparar: (() -> Pair<MotorDeCapacidade, ModeloEscolhido>?)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val estado by servicos.runner.estado.collectAsStateOnMain()
    val rodando = estado is EstadoBancada.Rodando
    val pronto = motivo == null && motor != null && modelo != null

    // O histórico vem de arquivos: lido fora da thread principal e relido quando uma rodada acaba ou o modelo muda.
    var historico by remember(modelo?.id, modelo?.backend) { mutableStateOf<List<Execucao>>(emptyList()) }
    val fim = (estado as? EstadoBancada.Terminou)?.execucao?.id
    LaunchedEffect(modelo?.id, modelo?.backend, fim, rodando) {
        val lido = if (modelo == null) emptyList() else withContext(Dispatchers.IO) { servicos.historico(modelo) }
        withContext(Dispatchers.Main.immediate) { historico = lido }
    }
    val concluidas = historico.filter { it.estado != EstadoExecucao.EM_ANDAMENTO }
    val indiceDaUltima = concluidas.indexOfFirst { e -> e.testes.any { it.teste == def.id } }
    val ultima = concluidas.getOrNull(indiceDaUltima)
    val resultado = ultima?.testes?.firstOrNull { it.teste == def.id }

    var pendente by remember { mutableStateOf<RodadaPendente?>(null) }
    val testesDaBateria = abas.filter { it.id != BANCADA_SUBABA_RAPIDO && it.habilitada }.map { it.id }

    fun pedir(testes: List<String>) {
        val preparado = if (preparar != null) preparar() else if (motor != null && modelo != null) motor to modelo else null
        val (e, m) = preparado ?: return
        // A confirmação guarda o mesmo motor e modelo que serão usados após o aviso.
        if (m.online) pendente = RodadaPendente(testes, e, m) else servicos.iniciar(e, m, testes, Perfil.COMPLETO)
    }

    Column(modifier.fillMaxWidth()) {
        val descricao = when (def.id) {
            "ocr" -> tr("Mede a leitura de texto em imagens.")
            "objetos" -> tr("Mede a identificação de objetos em imagens.")
            else -> tr(def.descricao)
        }
        SettingSub(descricao, Modifier.testTag(BANCADA_DESCRICAO_TAG))
        if (motivo != null) {
            SettingSub(motivo, Modifier.padding(top = 10.dp).testTag(BANCADA_MOTIVO_TAG))
        }
        PrimaryButton(
            text = if (rodando) tr("Parar") else tr("Rodar"),
            tag = BANCADA_RODAR_TAG,
            enabled = rodando || pronto,
            onClick = { if (rodando) servicos.runner.parar() else pedir(listOf(def.id)) },
            modifier = Modifier.padding(top = 12.dp),
        )
        if (testesDaBateria.size > 1) {
            val duracao = remember(historico, modelo?.id, testesDaBateria) {
                if (modelo == null) null else servicos.planejar(modelo, testesDaBateria, Perfil.COMPLETO)?.let { EstimativaDeRodada.duracaoMs(it, concluidas) }
            }
            SecondaryButton(
                text = if (duracao != null) tr("Rodar a bateria (≈ %s)", duracaoLegivel(duracao)) else tr("Rodar a bateria"),
                tag = BANCADA_BATERIA_TAG,
                onClick = { if (!rodando && pronto) pedir(testesDaBateria) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        (estado as? EstadoBancada.Rodando)?.let { ProgressoDaRodada(it, Modifier.padding(top = 12.dp)) }
        (estado as? EstadoBancada.Falhou)?.let {
            Text(
                tr("A rodada não pôde continuar (%s).", it.mensagem),
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(BANCADA_ERRO_TAG),
            )
        }

        if (resultado != null && ultima != null) {
            val variacao = Historico.variacao(ultima, def.id, concluidas.drop(indiceDaUltima + 1))
            CartaoDoResultado(ultima, resultado, variacao, modelo, servicos, Modifier.padding(top = 16.dp))
            HistoricoDoTeste(def.id, concluidas, Modifier.padding(top = 14.dp))
            DetalheDosCasos(def, resultado, Modifier.padding(top = 14.dp))
        } else {
            SettingSub(tr("Ainda não há resultado deste teste para este modelo."), Modifier.padding(top = 14.dp))
        }
    }

    val aviso = pendente
    if (aviso != null) {
        AvisoDeCusto(
            modelo = aviso.modelo,
            aviso = aviso,
            servicos = servicos,
            onSegundaConfirmacao = { pendente = aviso.copy(confirmandoCompleto = true) },
            onVoltar = { pendente = aviso.copy(confirmandoCompleto = false) },
            onRodar = { perfil ->
                pendente = null
                servicos.iniciar(aviso.motor, aviso.modelo, aviso.testes, perfil)
            },
            onCancelar = { pendente = null },
        )
    }
}

@Composable
private fun ProgressoDaRodada(estado: EstadoBancada.Rodando, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val p = estado.progresso
    Column(modifier.fillMaxWidth()) {
        if (p == null) {
            SettingSub(tr("Preparando…"), Modifier.testTag(BANCADA_PROGRESSO_TAG))
            return
        }
        val texto = when (p.fase) {
            Progresso.Fase.DETERMINISMO -> tr("Conferindo se o modelo repete a mesma saída…")
            else -> if (p.totalDeTestes > 1) {
                tr("%s · caso %d de %d (teste %d de %d)", rotuloDoTeste(p.teste), p.casoAtual, p.totalDeCasos, p.indiceDoTeste, p.totalDeTestes)
            } else {
                tr("%s · caso %d de %d", rotuloDoTeste(p.teste), p.casoAtual, p.totalDeCasos)
            }
        }
        SettingSub(texto, Modifier.testTag(BANCADA_PROGRESSO_TAG))
        val fracao = if (p.totalDeCasos == 0) 0f else (p.casoAtual.coerceAtLeast(0).toFloat() / p.totalDeCasos).coerceIn(0f, 1f)
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(palette.line2)) {
            Box(Modifier.fillMaxWidth(fracao).height(4.dp).clip(RoundedCornerShape(2.dp)).background(palette.teal))
        }
        when (p.fase) {
            Progresso.Fase.PAUSADO_TERMICO ->
                SettingSub(tr("Pausado: o aparelho está quente. A rodada segue sozinha quando esfriar (até 10 min)."), Modifier.padding(top = 6.dp).testTag(BANCADA_PAUSA_TAG))
            Progresso.Fase.PAUSADO_BATERIA ->
                SettingSub(tr("Pausado: bateria baixa. Ligue o carregador (a rodada espera até 10 min)."), Modifier.padding(top = 6.dp).testTag(BANCADA_PAUSA_TAG))
            else -> Unit
        }
    }
}

@Composable
private fun CartaoDoResultado(
    execucao: Execucao,
    teste: ResultadoTeste,
    variacao: Variacao?,
    modelo: ModeloEscolhido?,
    servicos: BancadaServicos,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportando by remember { mutableStateOf<Execucao?>(null) }
    val seletor = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val alvo = exportando
        exportando = null
        if (uri != null && alvo != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { servicos.exportar(alvo, it) } }
                }
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bg)
            .padding(14.dp)
            .testTag(BANCADA_RESULTADO_TAG),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                teste.nota?.let { tr("Nota %s", casas(it)) } ?: tr("Sem nota"),
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.testTag(BANCADA_NOTA_TAG),
            )
            if (variacao != null) {
                Text(
                    setaDe(variacao.delta),
                    color = if (variacao.delta >= 0) palette.teal else palette.terracotta,
                    fontFamily = SpaceGrotesk,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(BANCADA_VARIACAO_TAG),
                )
            }
        }
        SettingSub(medidasDe(teste, execucao), Modifier.padding(top = 6.dp).testTag(BANCADA_MEDIDAS_TAG))
        SettingSub(contextoDe(execucao, modelo), Modifier.padding(top = 2.dp).testTag(BANCADA_CONTEXTO_TAG))
        if (execucao.estado == EstadoExecucao.INCOMPLETA) {
            Text(
                tr("Incompleta: %s. Não entra em comparação.", motivoLegivel(execucao.motivo)),
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp).testTag(BANCADA_INCOMPLETA_TAG),
            )
        } else if (variacao == null) {
            SettingSub(tr("Sem execução anterior comparável (mesmo corpus, opções e backend)."), Modifier.padding(top = 6.dp))
        }
        SecondaryButton(
            text = tr("Exportar JSON"),
            tag = BANCADA_EXPORTAR_TAG,
            onClick = {
                exportando = execucao
                seletor.launch("bancada-${execucao.modelo.id.replace('/', '-')}-${execucao.iniciada.take(10)}.json")
            },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun HistoricoDoTeste(teste: String, concluidas: List<Execucao>, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val comTeste = concluidas.withIndex().filter { (_, e) -> e.testes.any { it.teste == teste } }.take(HISTORICO_MAXIMO)
    Column(modifier.fillMaxWidth().testTag(BANCADA_HISTORICO_TAG)) {
        SettingTitle(tr("Histórico deste modelo"))
        comTeste.forEach { (i, e) ->
            val r = e.testes.first { it.teste == teste }
            val v = Historico.variacao(e, teste, concluidas.drop(i + 1))
            val partes = buildList {
                add(dataLegivel(e.iniciada))
                add(r.nota?.let { casas(it) } ?: "—")
                v?.let { add(setaDe(it.delta)) }
                if (e.estado == EstadoExecucao.INCOMPLETA) add(tr("incompleta"))
            }
            Text(
                partes.joinToString(" · "),
                color = if (e.estado == EstadoExecucao.INCOMPLETA) palette.faint else palette.fg2,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp).testTag(bancadaHistoricoLinhaTag(e.id)),
            )
        }
    }
}

@Composable
private fun DetalheDosCasos(def: TesteDef, teste: ResultadoTeste, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    var aberto by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            (if (aberto) "▾ " else "▸ ") + tr("Casos (%d)", teste.casos.size),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 12.5.sp,
            modifier = Modifier.clickable { aberto = !aberto }.padding(vertical = 4.dp).testTag(BANCADA_CASOS_TOGGLE_TAG),
        )
        if (aberto) {
            teste.casos.forEach { c ->
                val original = def.casos.firstOrNull { it.id == c.id }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(bancadaCasoTag(c.id))) {
                    Text(
                        "${c.id} · ${estadoLegivel(c)}",
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontSize = 11.5.sp,
                    )
                    if (original != null) {
                        SettingSub(tr("Entrada: %s", cortar(original.entrada, 160)))
                        original.esperado?.takeIf { it.toString() != "null" }?.let { SettingSub(tr("Esperado: %s", cortar(it.toString(), 160))) }
                    }
                    c.saida?.let { SettingSub(tr("Saída: %s", cortar(it, 200))) }
                    c.motivo?.let { SettingSub(tr("Motivo: %s", cortar(it, 160))) }
                }
            }
        }
    }
}

/** O aviso de custo e de limite antes de qualquer chamada à nuvem (BON-01/02). Nenhum valor em dinheiro: sem preço informado, não há custo a mostrar. */
@Composable
private fun AvisoDeCusto(
    modelo: ModeloEscolhido,
    aviso: RodadaPendente,
    servicos: BancadaServicos,
    onSegundaConfirmacao: () -> Unit,
    onVoltar: () -> Unit,
    onRodar: (Perfil) -> Unit,
    onCancelar: () -> Unit,
) {
    val reduzida = remember(modelo.id, aviso.testes) {
        servicos.planejar(modelo, aviso.testes, Perfil.FUMACA)?.let { EstimativaDeRodada.de(it) }
    }
    val completa = remember(modelo.id, aviso.testes) {
        servicos.planejar(modelo, aviso.testes, Perfil.COMPLETO)?.let { EstimativaDeRodada.de(it) }
    }
    val estimativa = if (modelo.porAssinatura && !aviso.confirmandoCompleto) reduzida else completa
    ConfigModal(
        title = tr("Rodar no provedor"),
        onDismiss = onCancelar,
        closeTag = BANCADA_CUSTO_FECHAR_TAG,
        cardTag = BANCADA_CUSTO_TAG,
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
            if (estimativa != null) {
                SettingTitle(tr("%s · %s", provedorLegivel(modelo.origem), modelo.nome))
                SettingSub(tr("Faz %d chamadas com a sua credencial.", estimativa.chamadas), Modifier.padding(top = 6.dp))
                val teto = tetoEfetivoDeSaidaNaNuvem(modelo.origem)
                SettingSub(
                    if (teto != null) {
                        tr("Entrada: cerca de %d tokens (estimativa) · teto de saída: %d tokens por chamada.", estimativa.tokensEntradaEstimados, teto)
                    } else {
                        tr("Entrada: cerca de %d tokens (estimativa) · sem teto de saída: vale o limite do modelo.", estimativa.tokensEntradaEstimados)
                    },
                    Modifier.padding(top = 4.dp),
                )
            }
            if (modelo.porAssinatura) {
                SettingSub(tr("Consome o limite do seu plano."), Modifier.padding(top = 10.dp))
            }
            if (aviso.confirmandoCompleto) {
                SettingSub(tr("Confirme: a rodada completa gasta bem mais do limite do seu plano que a reduzida."), Modifier.padding(top = 6.dp))
                PrimaryButton(tr("Confirmar rodada completa"), BANCADA_CUSTO_CONFIRMAR_TAG, { onRodar(Perfil.COMPLETO) }, Modifier.padding(top = 16.dp))
                SecondaryButton(tr("Voltar"), BANCADA_CUSTO_COMPLETO_TAG, onVoltar, Modifier.padding(top = 8.dp))
            } else if (modelo.porAssinatura) {
                SettingSub(tr("Por padrão a rodada é reduzida: 2 casos por teste."), Modifier.padding(top = 6.dp))
                PrimaryButton(tr("Rodar reduzida"), BANCADA_CUSTO_CONFIRMAR_TAG, { onRodar(Perfil.FUMACA) }, Modifier.padding(top = 16.dp))
                SecondaryButton(tr("Rodar completa…"), BANCADA_CUSTO_COMPLETO_TAG, onSegundaConfirmacao, Modifier.padding(top = 8.dp))
            } else {
                PrimaryButton(tr("Rodar"), BANCADA_CUSTO_CONFIRMAR_TAG, { onRodar(Perfil.COMPLETO) }, Modifier.padding(top = 16.dp))
            }
            SecondaryButton(tr("Cancelar"), BANCADA_CUSTO_CANCELAR_TAG, onCancelar, Modifier.padding(top = 8.dp))
        }
    }
}

// ── formatação ───────────────────────────────────────────────────────────────────────────

private const val HISTORICO_MAXIMO = 10

internal fun casas(valor: Double): String = String.format(Locale.getDefault(), "%.2f", valor)

internal fun setaDe(delta: Double): String = (if (delta >= 0) "▲ " else "▼ ") + casas(abs(delta))

internal fun cortar(texto: String, max: Int): String {
    val limpo = texto.replace('\n', ' ').trim()
    return if (limpo.length > max) limpo.take(max) + "…" else limpo
}

/** "28/10 14:03" no fuso do aparelho. */
internal fun dataLegivel(iso: String): String {
    val ms = epochDeIso(iso) ?: return iso
    return SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))
}

/** "24 min", "1 h 05 min" ou "40 s". */
internal fun duracaoLegivel(ms: Long): String {
    val s = (ms / 1000.0).roundToInt()
    return when {
        s < 90 -> tr("%d s", s)
        s < 3600 -> tr("%d min", (s / 60.0).roundToInt())
        else -> tr("%d h %02d min", s / 3600, (s % 3600) / 60)
    }
}

internal fun provedorLegivel(origem: String): String = when (origem) {
    "openai" -> "OpenAI"
    "anthropic" -> "Anthropic"
    "google" -> "Google"
    "custom" -> "Custom"
    else -> origem
}

internal fun motivoLegivel(motivo: MotivoIncompleta?): String = when (motivo) {
    MotivoIncompleta.CANCELADA -> tr("você parou a rodada")
    MotivoIncompleta.TERMICO -> tr("o aparelho esquentou demais")
    MotivoIncompleta.BATERIA -> tr("bateria baixa")
    MotivoIncompleta.RATE_LIMIT -> tr("limite de uso do provedor")
    MotivoIncompleta.CREDENCIAL -> tr("credencial recusada")
    MotivoIncompleta.MODELO_AUSENTE -> tr("arquivos do modelo ausentes")
    MotivoIncompleta.INTERROMPIDA -> tr("o app foi fechado no meio")
    null -> tr("sem motivo registrado")
}

private fun estadoLegivel(c: ResultadoCaso): String = when (c.estado) {
    EstadoCaso.OK, EstadoCaso.FALHA -> casas(c.nota ?: 0.0) + (if (c.estado == EstadoCaso.FALHA) " (${tr("falha")}${c.causa?.let { ": $it" }.orEmpty()})" else "")
    EstadoCaso.INVALIDO -> tr("inválido (%s)", c.causa.orEmpty())
    EstadoCaso.NAO_SE_APLICA -> tr("não se aplica")
}

private fun medidasDe(t: ResultadoTeste, e: Execucao): String {
    val partes = ArrayList<String>()
    partes += tr("%d casos", t.n)
    if (t.invalidos > 0) partes += tr("%d inválidos", t.invalidos)
    if (t.naoSeAplica > 0) partes += tr("%d não se aplicam", t.naoSeAplica)
    t.primeiroTrechoMsP50?.let { partes += tr("1º trecho %s", formatTestDuration(it)) }
    t.totalMsP50?.let { partes += tr("total %s", formatTestDuration(it)) }
    t.tokensPorSegundoMedia?.let { v ->
        val estimado = t.casos.any { it.tokensPorSegundo != null && it.tokensEstimados }
        partes += (if (estimado) "~" else "") + tr("%s tok/s", String.format(Locale.getDefault(), "%.1f", v))
    }
    e.cargas?.ramPicoMb?.let { partes += tr("RAM %s GB", String.format(Locale.getDefault(), "%.2f", it / 1024.0)) }
    return partes.joinToString(" · ")
}

private fun contextoDe(e: Execucao, modelo: ModeloEscolhido?): String {
    val partes = ArrayList<String>()
    partes += e.modelo.backend
    partes += if (e.modelo.pensamento) tr("pensamento ligado") else tr("pensamento desligado")
    partes += dataLegivel(e.iniciada)
    if (e.perfil == Perfil.FUMACA.id) partes += tr("reduzida")
    if (e.testes.any { it.naoDeterministico }) partes += tr("saída não determinística (mediana de 3)")
    if (e.opcoes.ignoradas.isNotEmpty()) partes += tr("sem aplicar: %s", e.opcoes.ignoradas.sorted().joinToString(", "))
    if (modelo != null && e.modelo.id != modelo.id) partes += e.modelo.id
    return partes.joinToString(" · ")
}

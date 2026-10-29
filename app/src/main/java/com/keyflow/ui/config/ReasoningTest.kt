package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val REASONING_TEST_BUTTON_TAG = "raciocinio-testar"
const val REASONING_TEST_REASON_TAG = "raciocinio-testar-motivo"
const val REASONING_TEST_RESPONSE_TAG = "raciocinio-resposta"
const val REASONING_TEST_TIMES_TAG = "raciocinio-tempos"
const val REASONING_TEST_ERROR_TAG = "raciocinio-erro"
const val REASONING_TEST_MANAGE_USAGE_TAG = "raciocinio-gerenciar-uso"

/**
 * O que a área do Testar mostra num instante. [firstDeltaMs] é o tempo até o 1º trecho e [totalMs] a duração toda —
 * `null` enquanto não houve (RTS-03). [unavailable] só vem preenchido quando o clique não pôde iniciar nada (RTS-02).
 */
data class TestState(
    val text: String = "",
    val running: Boolean = false,
    val firstDeltaMs: Long? = null,
    val totalMs: Long? = null,
    val error: ReasoningError? = null,
    val unavailable: UnavailableReason? = null,
)

/**
 * Roda a frase de teste num [com.keyflow.reasoning.ReasoningEngine] e expõe o [TestState] (RTS-01..04). Kotlin puro: o
 * [scope] e o [clock] entram por parâmetro. Começar de novo cancela o anterior; parar cancela o motor de verdade e
 * mantém o texto parcial; morrer o escopo (sair da tela, girar) cancela a geração junto.
 */
class TestRunner(
    private val scope: CoroutineScope,
    initial: TestState = TestState(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<TestState> = _state.asStateFlow()
    private var job: Job? = null

    /** Cada `start` é uma geração; só a atual mexe no estado — a limpeza de uma execução já cancelada não pode apagar a nova. */
    private var generation = 0

    fun start(resolution: ReasoningResolution, request: ReasoningRequest) {
        job?.cancel()
        job = null
        val mine = ++generation
        when (resolution) {
            is ReasoningResolution.Unavailable -> _state.value = TestState(unavailable = resolution.reason)
            is ReasoningResolution.Ready -> {
                val startedAt = clock()
                _state.value = TestState(running = true)
                job = scope.launch {
                    try {
                        resolution.engine.generate(request).collect { event ->
                            when (event) {
                                is ReasoningEvent.Delta -> _state.update {
                                    it.copy(text = it.text + event.text, firstDeltaMs = it.firstDeltaMs ?: (clock() - startedAt))
                                }
                                ReasoningEvent.Done -> _state.update { it.copy(running = false, totalMs = clock() - startedAt) }
                                is ReasoningEvent.Error ->
                                    _state.update { it.copy(running = false, error = event.kind, totalMs = clock() - startedAt) }
                                is ReasoningEvent.Metrics -> Unit // o Testar de uma frase não pede opções
                            }
                        }
                        // O fluxo acabou sem Done nem Error: não deixa a tela presa em "Parar".
                        _state.update { if (it.running) it.copy(running = false, totalMs = clock() - startedAt) else it }
                    } catch (e: CancellationException) {
                        if (generation == mine) _state.update { it.copy(running = false) }
                        throw e
                    } catch (e: Exception) {
                        // Um motor que lança (cofre, cabeçalho inválido, bug) vira erro na tela — este escopo é o da UI e uma exceção
                        // solta derrubaria o app.
                        if (generation == mine) {
                            _state.update { it.copy(running = false, error = ReasoningError.ENGINE, totalMs = clock() - startedAt) }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        generation++
        _state.update { it.copy(running = false) }
    }
}

/** As mensagens da área do Testar — legíveis, sem exceção nem pilha (RTS-04). */
object ReasoningTestMessages {
    fun error(kind: ReasoningError): String = when (kind) {
        ReasoningError.OOM -> tr("Memória insuficiente para este modelo — feche outros apps ou escolha o modelo Leve.")
        ReasoningError.MODEL_MISSING -> tr("Os arquivos do modelo sumiram — baixe de novo.")
        ReasoningError.LOCAL -> tr("O motor local foi interrompido — tente de novo.")
        ReasoningError.NETWORK -> tr("Sem conexão com o provedor — confira a rede e tente de novo.")
        ReasoningError.UNAUTHORIZED -> tr("A credencial foi recusada — entre de novo ou confira a chave de API.")
        ReasoningError.RATE_LIMIT -> tr("Limite de uso atingido — tente de novo mais tarde.")
        ReasoningError.NOT_ELIGIBLE -> tr("Esta conta do ChatGPT não pode ser usada por aqui — confira o seu plano ou use uma chave de API.")
        ReasoningError.ENGINE -> tr("O modelo falhou ao responder.")
    }

    fun unavailable(reason: UnavailableReason): String = when (reason) {
        UnavailableReason.NO_LOCAL_MODEL -> tr("Baixe e selecione um modelo para testar.")
        UnavailableReason.MODEL_NOT_INSTALLED -> tr("O modelo selecionado não está instalado — baixe de novo.")
        UnavailableReason.NOT_CONNECTED -> tr("Conecte a conta ou informe a chave de API para testar.")
        UnavailableReason.NO_MODEL_CHOSEN -> tr("Escolha um modelo para testar.")
    }
}

/** "812 ms" abaixo de um segundo; "3,2 s" depois. */
internal fun formatTestDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(Locale.getDefault(), "%.1f s", ms / 1_000.0)

/**
 * O botão Testar (RTS-01..04): manda uma frase à origem ativa, mostra a resposta chegando, vira "Parar" enquanto gera e,
 * no fim, os tempos. Sem motor pronto o botão fica desabilitado com o motivo escrito ao lado. [onManageUsage] não nulo faz do
 * limite de uso atingido um botão principal "Manage usage" (a doc da OpenAI: é a ação que resolve o limite do plano).
 * [initialState] é só para a galeria de verificação mostrar a área já em andamento (como `initialTab` no ConfigScreen). [resolveOnStart], quando
 * existe, escolhe o motor no momento do toque, depois de confirmar os campos de texto pendentes.
 */
@Composable
fun ReasoningTestArea(
    resolution: ReasoningResolution,
    modifier: Modifier = Modifier,
    onManageUsage: (() -> Unit)? = null,
    initialState: TestState = TestState(),
    resolveOnStart: (() -> ReasoningResolution)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    // Main explícito: o motor local entrega os pedaços numa thread de binder, e é o dispatcher do coletor que decide
    // onde o estado muda. O da composição salta para a thread principal no app, mas não sob o relógio dos testes de
    // Compose — lá a thread de binder seguiu até a recomposição e caiu em CalledFromWrongThreadException (CI, 2026-10-09).
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val runner = remember { TestRunner(scope, initialState) }
    val state by runner.state.collectAsStateOnMain()
    val prompt = tr("Diga olá em uma frase curta.")
    val unavailable = (resolution as? ReasoningResolution.Unavailable)?.reason

    Column(modifier.fillMaxWidth()) {
        PrimaryButton(
            text = if (state.running) tr("Parar") else tr("Testar"),
            tag = REASONING_TEST_BUTTON_TAG,
            enabled = state.running || unavailable == null,
            // O motor sai do que está nos campos no toque (com o texto ainda não confirmado), não da resolução da última composição.
            onClick = { if (state.running) runner.stop() else runner.start(resolveOnStart?.invoke() ?: resolution, ReasoningRequest(user = prompt)) },
        )
        if (unavailable != null) {
            SettingSub(ReasoningTestMessages.unavailable(unavailable), Modifier.padding(top = 10.dp).testTag(REASONING_TEST_REASON_TAG))
        }
        if (state.text.isNotEmpty() || state.running) {
            Text(
                state.text,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.bg)
                    .padding(14.dp)
                    .testTag(REASONING_TEST_RESPONSE_TAG),
            )
        }
        val total = state.totalMs
        if (total != null) {
            val times = state.firstDeltaMs?.let { tr("Primeiro trecho: %s · total: %s", formatTestDuration(it), formatTestDuration(total)) }
                ?: tr("Total: %s", formatTestDuration(total))
            SettingSub(times, Modifier.padding(top = 8.dp).testTag(REASONING_TEST_TIMES_TAG))
        }
        state.error?.let { kind ->
            Text(
                ReasoningTestMessages.error(kind),
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(REASONING_TEST_ERROR_TAG),
            )
            if (kind == ReasoningError.RATE_LIMIT && onManageUsage != null) {
                // Texto aprovado pela OpenAI: igual em todos os idiomas.
                PrimaryButton("Manage usage", REASONING_TEST_MANAGE_USAGE_TAG, onManageUsage, Modifier.padding(top = 12.dp))
            }
        }
    }
}

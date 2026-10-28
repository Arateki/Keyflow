package com.keyflow.ui.config

import com.keyflow.vision.VisionResult
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.UnavailableReason

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.EngineType
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.vision.client.VisionCache
import com.keyflow.vision.client.VisionOcrClient
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.RgbaImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val VISAO_TEST_BUTTON_TAG = "visao-testar"
const val VISAO_TEST_RESPONSE_TAG = "visao-resposta"
const val VISAO_TEST_TIMES_TAG = "visao-tempos"
const val VISAO_TEST_REASON_TAG = "visao-testar-motivo"
const val VISAO_TEST_ERROR_TAG = "visao-erro"
const val VISAO_TEST_IMAGE_TAG = "visao-imagem-teste"

/** Quanto o Testar espera as Settings confirmadas chegarem à tela antes de partir assim mesmo. */
private const val START_ON_ARRIVAL_TIMEOUT_MS = 2_000L

/** Por que a pergunta do Testar não saiu da imagem de teste: o que o aviso da tela diz. */
enum class VisionTestError { EMPTY, UNAVAILABLE, OUT_OF_MEMORY, FAILED, INTERRUPTED, UNAUTHORIZED, NETWORK, RATE_LIMIT, NOT_ELIGIBLE }

/** O que a área do Testar mostra num instante. [durationMs] é o tempo do reconhecimento, `null` enquanto não houve. */
data class VisionTestState(
    val running: Boolean = false,
    val text: String = "",
    val durationMs: Long? = null,
    val error: VisionTestError? = null,
)

/** Por que o botão Testar está desligado. */
enum class VisionTestUnavailable {
    /** Origem Local sem modelo escolhido. */
    NO_LOCAL_MODEL,

    /** O modelo escolhido não está (mais) instalado. */
    MODEL_NOT_INSTALLED,

    /** Origem não habilitada (Créditos). */
    NOT_YET,
    NOT_CONNECTED,
    NO_MODEL_CHOSEN,
}

/** O Testar pode rodar com estas Settings? Decidido numa função pura ([visionTestAvailability]) para a tela só desenhar. */
sealed interface VisionTestAvailability {
    data object Ready : VisionTestAvailability
    data class Unavailable(val reason: VisionTestUnavailable) : VisionTestAvailability
}

/**
 * O Testar usa a imagem nossa com OCR, Gemma ou o provedor escolhido. Local exige instalação; nuvem exige conexão.
 * Os modelos de nuvem vêm da lista curada e o Custom exige endpoint e modelo.
 */
fun visionTestAvailability(settings: Settings, models: LocalModelsUi, connected: Boolean = false): VisionTestAvailability {
    if (settings.visionSource == VisionSource.CREDITS) return VisionTestAvailability.Unavailable(VisionTestUnavailable.NOT_YET)
    if (settings.visionSource == VisionSource.CUSTOM) return when {
        settings.visionCustomEndpoint.isBlank() -> VisionTestAvailability.Unavailable(VisionTestUnavailable.NOT_CONNECTED)
        settings.visionCustomModel.isBlank() -> VisionTestAvailability.Unavailable(VisionTestUnavailable.NO_MODEL_CHOSEN)
        else -> VisionTestAvailability.Ready
    }
    if (settings.visionSource != VisionSource.LOCAL) return if (connected) VisionTestAvailability.Ready
        else VisionTestAvailability.Unavailable(VisionTestUnavailable.NOT_CONNECTED)
    val id = settings.visionLocalModelId ?: return VisionTestAvailability.Unavailable(VisionTestUnavailable.NO_LOCAL_MODEL)
    val item = models.items.firstOrNull { it.spec.id == id }
    if (item == null || item.status != LocalModelStatus.INSTALLED) {
        return VisionTestAvailability.Unavailable(VisionTestUnavailable.MODEL_NOT_INSTALLED)
    }
    return VisionTestAvailability.Ready
}

/** Quem lê a imagem de teste. Em produção é o [VisionOcrTester] (o `:vision` de verdade); a galeria e os testes trocam por um falso. */
fun interface OcrTester {
    suspend fun run(): OcrResult

    companion object {
        /** Sem motor (galeria, testes de outras abas): responde "indisponível", que é o que a tela diz. */
        val None = OcrTester { OcrResult.Unavailable }
    }
}

/**
 * A imagem do Testar, desenhada em `Canvas` na hora: duas linhas de texto escuro sobre fundo claro, em português com acento. É nossa (nada
 * baixado nem embutido como arquivo) e determinística o bastante para o OCR ler: texto grande, contraste alto.
 */
object VisionTestImage {
    const val WIDTH = 520
    const val HEIGHT = 132
    const val LINE_1 = "Texto de teste do KeyFlow"
    const val LINE_2 = "Visão 100% no aparelho"

    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x1A, 0x1A, 0x1A)
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(LINE_1, 24f, 54f, paint)
        canvas.drawText(LINE_2, 24f, 102f, paint)
        return bitmap
    }

    /** Os bytes RGBA, linha a linha — o que o `:vision` lê. */
    fun rgba(bitmap: Bitmap = bitmap()): RgbaImage {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i * 4] = (c shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (c and 0xFF).toByte()
            out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return RgbaImage(w, h, out)
    }
}

/**
 * O Testar de verdade: grava a imagem de teste no cache da Visão, manda ao OCR no `:vision` pelo mesmo cliente do fluxo de captura e apaga o
 * arquivo. Não baixa nada (o modelo é o que a aba já instalou) e não fala com a rede.
 */
class VisionOcrTester(
    private val context: Context,
    private val client: VisionOcrClient = VisionOcrClient.create(context),
) : OcrTester {
    override suspend fun run(): OcrResult = withContext(Dispatchers.IO) {
        val image = VisionTestImage.rgba()
        val file = VisionCache.writeCrop(context, image)
        try {
            client.recognize(file, image.width, image.height)
        } finally {
            file.delete()
        }
    }
}

/** As mensagens da área do Testar — legíveis, sem exceção nem pilha. */
object VisionTestMessages {
    fun error(kind: VisionTestError): String = when (kind) {
        VisionTestError.EMPTY -> tr("Nenhum texto foi reconhecido na imagem de teste.")
        VisionTestError.UNAVAILABLE -> tr("O modelo de leitura não está disponível neste aparelho — baixe de novo na lista acima.")
        VisionTestError.OUT_OF_MEMORY -> tr("Memória insuficiente para ler a imagem — feche outros apps e tente de novo.")
        VisionTestError.FAILED -> tr("O modelo falhou ao ler a imagem de teste.")
        VisionTestError.UNAUTHORIZED -> ReasoningTestMessages.error(ReasoningError.UNAUTHORIZED)
        VisionTestError.NETWORK -> ReasoningTestMessages.error(ReasoningError.NETWORK)
        VisionTestError.RATE_LIMIT -> ReasoningTestMessages.error(ReasoningError.RATE_LIMIT)
        VisionTestError.NOT_ELIGIBLE -> ReasoningTestMessages.error(ReasoningError.NOT_ELIGIBLE)
        VisionTestError.INTERRUPTED -> tr("O motor de leitura foi interrompido — tente de novo.")
    }

    fun unavailable(reason: VisionTestUnavailable): String = when (reason) {
        VisionTestUnavailable.NO_LOCAL_MODEL -> tr("Baixe e selecione um modelo para testar.")
        VisionTestUnavailable.MODEL_NOT_INSTALLED -> tr("O modelo selecionado não está instalado — baixe de novo.")
        VisionTestUnavailable.NOT_CONNECTED -> ReasoningTestMessages.unavailable(UnavailableReason.NOT_CONNECTED)
        VisionTestUnavailable.NO_MODEL_CHOSEN -> ReasoningTestMessages.unavailable(UnavailableReason.NO_MODEL_CHOSEN)
        VisionTestUnavailable.NOT_YET -> tr("Este modelo ainda não pode ser testado aqui — o teste chega junto com a captura da tela.")
    }
}

/** O resultado do OCR como o Testar o mostra: o texto lido, ou a causa. */
internal fun visionTestStateFor(result: OcrResult, elapsedMs: Long): VisionTestState = when (result) {
    is OcrResult.Text -> VisionTestState(text = result.text, durationMs = elapsedMs)
    OcrResult.Empty -> VisionTestState(durationMs = elapsedMs, error = VisionTestError.EMPTY)
    OcrResult.Unavailable -> VisionTestState(error = VisionTestError.UNAVAILABLE)
    is OcrResult.Failed -> VisionTestState(
        durationMs = elapsedMs,
        error = when (result.kind) {
            OcrFailure.OOM -> VisionTestError.OUT_OF_MEMORY
            OcrFailure.PROCESS_DIED, OcrFailure.TIMEOUT -> VisionTestError.INTERRUPTED
            OcrFailure.ENGINE, OcrFailure.BAD_INPUT -> VisionTestError.FAILED
            // Cancelado por quem pediu: a tela já voltou ao "Testar", não há o que dizer.
            OcrFailure.CANCELLED -> null
        },
    )
}

/** A causa do motor fica legível sem apresentar corpo HTTP, imagem ou exceção. */
internal fun visionTestStateFor(result: VisionResult, elapsedMs: Long): VisionTestState {
    val error = when (result) {
        is VisionResult.Text -> return VisionTestState(text = result.text, durationMs = elapsedMs)
        VisionResult.Empty -> VisionTestError.EMPTY
        VisionResult.Unavailable -> VisionTestError.UNAVAILABLE
        is VisionResult.Failed -> when (result.kind) {
            ReasoningError.OOM -> VisionTestError.OUT_OF_MEMORY
            ReasoningError.MODEL_MISSING -> VisionTestError.UNAVAILABLE
            ReasoningError.LOCAL -> VisionTestError.INTERRUPTED
            ReasoningError.UNAUTHORIZED -> VisionTestError.UNAUTHORIZED
            ReasoningError.NETWORK -> VisionTestError.NETWORK
            ReasoningError.RATE_LIMIT -> VisionTestError.RATE_LIMIT
            ReasoningError.NOT_ELIGIBLE -> VisionTestError.NOT_ELIGIBLE
            ReasoningError.ENGINE -> VisionTestError.FAILED
        }
    }
    return VisionTestState(durationMs = elapsedMs, error = error)
}

/**
 * O botão Testar: lê a imagem de teste com o OCR escolhido, mostra o texto que saiu e o tempo, e vira "Parar" enquanto lê. Sem motor pronto
 * o botão fica desabilitado com o motivo escrito ao lado (o desenho da área do Testar do Raciocínio). [initialState] é só para a galeria
 * mostrar a área já com o resultado.
 */
@Composable
fun VisaoTestArea(
    availability: VisionTestAvailability,
    tester: OcrTester,
    modifier: Modifier = Modifier,
    initialState: VisionTestState = VisionTestState(),
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    modelTester: (suspend () -> VisionResult)? = null,
    remote: Boolean = false,
    requestKey: Any? = null,
    confirmPending: (() -> Boolean)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    // Main explícito: o resultado volta de outro processo numa thread de binder e é o dispatcher do coletor que decide onde o estado muda.
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    var state by remember(requestKey) { mutableStateOf(initialState) }
    var job by remember(requestKey) { mutableStateOf<Job?>(null) }
    val generation = remember(requestKey) { java.util.concurrent.atomic.AtomicLong() }
    DisposableEffect(requestKey) { onDispose { generation.incrementAndGet(); job?.cancel() } }
    val unavailable = (availability as? VisionTestAvailability.Unavailable)?.reason
    val bitmap = remember(unavailable == null) { if (unavailable == null) VisionTestImage.bitmap() else null }
    // Fora da chave de propósito: é a troca da chave, trazendo as Settings que o toque confirmou, que refaz a área e deixa o teste partir nela.
    var startOnArrival by remember { mutableStateOf(false) }

    val start: () -> Unit = {
        val mine = generation.incrementAndGet()
        state = VisionTestState(running = true)
        job = scope.launch {
            val startedAt = clock()
            try {
                val result = if (modelTester != null) visionTestStateFor(modelTester(), clock() - startedAt)
                    else visionTestStateFor(tester.run(), clock() - startedAt)
                if (mine == generation.get()) state = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Um tester que lança vira aviso na tela: este escopo é o da UI e uma exceção solta derrubaria o app.
                if (mine == generation.get()) state = VisionTestState(error = VisionTestError.FAILED)
            }
        }
    }
    LaunchedEffect(requestKey) {
        if (startOnArrival) {
            startOnArrival = false
            if (unavailable == null) start()
        }
    }
    // Se as Settings confirmadas não chegarem a trocar a chave, o toque não pode ficar sem resposta.
    LaunchedEffect(startOnArrival) {
        if (startOnArrival) {
            delay(START_ON_ARRIVAL_TIMEOUT_MS)
            startOnArrival = false
            if (unavailable == null) start()
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (bitmap != null) {
            SettingSub(if (remote) tr("O teste envia esta imagem de exemplo ao provedor escolhido.")
                else tr("O teste lê esta imagem de exemplo, no aparelho."), Modifier.padding(bottom = 10.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = tr("Imagem de exemplo do teste"),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .testTag(VISAO_TEST_IMAGE_TAG),
            )
        }
        PrimaryButton(
            text = if (state.running) tr("Parar") else tr("Testar"),
            tag = VISAO_TEST_BUTTON_TAG,
            enabled = state.running || unavailable == null,
            onClick = {
                if (state.running) {
                    generation.incrementAndGet()
                    job?.cancel()
                    job = null
                    state = state.copy(running = false)
                } else if (confirmPending?.invoke() == true) {
                    // Havia texto por confirmar num campo: o teste usa as Settings que saem dele, não as desta composição.
                    startOnArrival = true
                } else {
                    start()
                }
            },
        )
        if (unavailable != null) {
            SettingSub(VisionTestMessages.unavailable(unavailable), Modifier.padding(top = 10.dp).testTag(VISAO_TEST_REASON_TAG))
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
                    .testTag(VISAO_TEST_RESPONSE_TAG),
            )
        }
        state.durationMs?.let { total ->
            SettingSub(
                tr("Tempo de reconhecimento: %s", formatTestDuration(total)),
                Modifier.padding(top = 8.dp).testTag(VISAO_TEST_TIMES_TAG),
            )
        }
        state.error?.let { kind ->
            Text(
                VisionTestMessages.error(kind),
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(VISAO_TEST_ERROR_TAG),
            )
        }
    }
}

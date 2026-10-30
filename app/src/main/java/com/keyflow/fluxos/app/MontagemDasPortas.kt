package com.keyflow.fluxos.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import com.keyflow.core.KeyFlowController
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.execucao.ImagemHandle
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.Portas
import com.keyflow.reasoning.ReasoningError
import com.keyflow.speech.SpeechEngines
import com.keyflow.speech.AudioTrackPlayer
import com.keyflow.vision.VisionImage
import com.keyflow.vision.VisionResult
import com.keyflow.vision.client.VisionCache
import com.keyflow.vision.client.VisionOcrClient
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * O OCR local (PaddleOCR no `:vision`) sobre o `Bitmap` da captura. O transporte do `:vision` é um arquivo RGBA cru no cache do app,
 * o mesmo que a aba Visão usa no Testar: é gravado só para esta chamada e apagado em seguida, ganhando ou perdendo.
 */
class LeitorOcr(private val context: Context, private val client: VisionOcrClient = VisionOcrClient.create(context)) {
    suspend fun ler(imagem: ImagemHandle): VisionResult {
        val bitmap = (imagem as? ImagemBitmap)?.bitmap ?: return VisionResult.Failed(ReasoningError.ENGINE)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || w.toLong() * h > RgbaImage.MAX_PIXELS) return VisionResult.Failed(ReasoningError.ENGINE)
        val arquivo = withContext(Dispatchers.IO) {
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { i, c ->
                rgba[i * 4] = (c shr 16).toByte()
                rgba[i * 4 + 1] = (c shr 8).toByte()
                rgba[i * 4 + 2] = c.toByte()
                rgba[i * 4 + 3] = (c ushr 24).toByte()
            }
            VisionCache.writeCrop(context, RgbaImage(w, h, rgba))
        }
        return try {
            when (val r = client.recognize(arquivo, w, h)) {
                is OcrResult.Text -> VisionResult.Text(r.text)
                OcrResult.Empty -> VisionResult.Empty
                OcrResult.Unavailable -> VisionResult.Unavailable
                is OcrResult.Failed -> VisionResult.Failed(
                    when (r.kind) {
                        OcrFailure.OOM -> ReasoningError.OOM
                        OcrFailure.PROCESS_DIED, OcrFailure.TIMEOUT, OcrFailure.CANCELLED -> ReasoningError.LOCAL
                        else -> ReasoningError.ENGINE
                    },
                )
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { arquivo.delete() }
        }
    }
}

/**
 * As quatro portas que não dependem do serviço da orbe (Perguntar, Visão, Falar, Inserir) sobre os motores do [AmbienteMotores].
 * As do serviço (Ditar, Capturar) e a do Agente o `FluxoHost` da T14 acrescenta ao mapa.
 */
object MontagemDasPortas {
    fun base(context: Context): Map<TipoEtapa, PortaEtapa> {
        val app = context.applicationContext
        val ambiente = AmbienteMotores.of(app)
        val ocr by lazy { LeitorOcr(app) }
        return mapOf(
            TipoEtapa.PERGUNTAR to PortaPerguntar(ambiente::settingsAgora, ambiente.reasoning::resolve),
            TipoEtapa.VISAO to PortaVisao(
                settings = ambiente::settingsAgora,
                resolve = ambiente.vision::resolve,
                usaOcr = ::usaOcrLocal,
                ocr = { ocr.ler(it) },
                codificar = { VisionImage.encode((it as ImagemBitmap).bitmap) },
            ),
            TipoEtapa.FALAR to PortaFalar(
                settings = ambiente::settingsAgora,
                disponibilidade = { SpeechEngines.availability(it, ambiente.speechDeps) },
                motor = { SpeechEngines.forSource(it, ambiente.speechDeps) },
                novoPlayer = { AudioTrackPlayer() },
            ),
            TipoEtapa.INSERIR to PortaInserir(
                inserir = KeyFlowController::insertOrHold,
                copiar = { texto ->
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("KeyFlow", texto))
                },
            ),
        )
    }

    /**
     * A porta do Agente sobre uma sessão exclusiva por execução (`AgentParts.novaSessao`) e a janela de confirmação do KeyFlow. `null`
     * quando o agente está desligado neste build (`AgentBackends` nulo): o executor falha a etapa com `MOTOR`.
     */
    fun agente(context: Context, nomeDoFluxo: () -> String? = { null }): PortaEtapa? {
        val app = context.applicationContext
        val ambiente = AmbienteMotores.of(app)
        val backends = ambiente.agentBackends ?: return null
        return PortaAgenteApp(
            settings = ambiente::settingsAgora,
            resolver = backends::resolve,
            novaSessao = {
                val escopo = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
                val partes = com.keyflow.agenthost.AgentHost.of(app).novaSessao(escopo)
                SessaoDoFluxo(partes.controller, partes.gate, { nome -> partes.registry[nome]?.label ?: nome }) { escopo.cancel() }
            },
            abrirConfirmacao = { FluxoConfirmacaoActivity.abrir(app) },
            nomeDoFluxo = nomeDoFluxo,
        )
    }

    /** O Local da Visão com o PaddleOCR escolhido: ele só lê e não está no `VisionModelEngines` (que resolve os modelos de linguagem). */
    internal fun usaOcrLocal(settings: Settings): Boolean =
        settings.visionSource == VisionSource.LOCAL && settings.visionLocalModelId == ModelCatalog.OCR_PADDLE_LATIN.id

    /** O `Bitmap` da captura embrulhado como imagem do fluxo. */
    fun embrulhar(bitmap: Bitmap): ImagemHandle = ImagemBitmap(bitmap)

    fun comoPortas(mapa: Map<TipoEtapa, PortaEtapa>): Portas = PortasPorTipo(mapa)
}


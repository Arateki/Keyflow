package com.keyflow.vision

import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.reasoning.ReasoningRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach

/** VC-T15: só entrega texto depois do sucesso; uma falha nunca deixa texto parcial pronto para copiar. */
sealed interface VisionResult {
    data class Text(val text: String) : VisionResult
    data object Empty : VisionResult
    data object Unavailable : VisionResult
    data class Failed(val kind: ReasoningError) : VisionResult
}

interface VisionModelEngine {
    suspend fun ask(image: ReasoningImage, instruction: String): VisionResult
    suspend fun read(image: ReasoningImage): VisionResult = ask(image, READ_INSTRUCTION)
    companion object {
        const val READ_INSTRUCTION = "Transcreva exatamente o texto desta imagem, mantendo as quebras de linha; responda só com o texto."
        const val ASK_INSTRUCTION = "Descreva esta imagem."
    }
}

/** Compartilha o transporte, autenticação e cancelamento do Raciocínio. Não troca de provedor em caso de falha. */
open class CloudVisionEngine(private val engine: ReasoningEngine) : VisionModelEngine {
    override suspend fun ask(image: ReasoningImage, instruction: String): VisionResult {
        val text = StringBuilder()
        return try {
            val terminal = engine.generate(ReasoningRequest(user = instruction, image = image))
                .onEach { if (it is ReasoningEvent.Delta) text.append(it.text) }
                .firstOrNull { it is ReasoningEvent.Error || it == ReasoningEvent.Done }
            when (terminal) {
                ReasoningEvent.Done -> if (text.isBlank()) VisionResult.Empty else VisionResult.Text(text.toString())
                is ReasoningEvent.Error -> if (terminal.kind == ReasoningError.MODEL_MISSING) VisionResult.Unavailable
                    else VisionResult.Failed(terminal.kind)
                else -> VisionResult.Failed(ReasoningError.ENGINE)
            }
        } catch (e: CancellationException) { throw e
        } catch (_: OutOfMemoryError) { VisionResult.Failed(ReasoningError.OOM)
        } catch (_: Exception) { VisionResult.Failed(ReasoningError.ENGINE) }
    }
}

/** Mesmo contrato, mas o transporte é o binder local: sem cliente HTTP neste caminho. */
class LocalVisionEngine(engine: ReasoningEngine) : CloudVisionEngine(engine)

/** Chat Completions do endpoint digitado; as credenciais dos outros provedores não entram aqui. */
class CustomVisionEngine(engine: ReasoningEngine) : CloudVisionEngine(engine)

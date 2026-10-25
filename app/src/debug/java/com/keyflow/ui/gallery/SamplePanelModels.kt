package com.keyflow.ui.gallery

import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelFile
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelSpec

/**
 * Modelos de AMOSTRA para a galeria e para os testes dos painéis de Visão e de Voz. Não são catálogo: o
 * `ModelCatalog.VISION`/`VOICE` está vazio desde 2026-10-24 (as entradas antigas tinham pins inventados), mas os painéis
 * continuam existindo e precisam de uma lista para desenhar baixando, instalado e selecionado. Existe só no build de
 * debug; a URL é `.invalid` e o SHA-256 é zero, de propósito — ninguém baixa isto, e o `PinSanityTest` só olha o catálogo
 * real.
 */
object SamplePanelModels {

    private fun spec(id: String, name: String, kind: ModelKind, engine: EngineType, ramMb: Int, bytes: Long) = ModelSpec(
        id = id,
        displayName = name,
        summary = "Modelo de amostra para a galeria e os testes",
        languages = "Amostra",
        engineType = engine,
        acceptsLanguageHint = false,
        recommendedRamMb = ramMb,
        modelName = name,
        kind = kind,
        files = listOf(ModelFile(FileRole.MODEL, "$id.bin", "https://amostra.invalid/$id.bin", "0".repeat(64), bytes)),
    )

    val VISION_LIGHT = spec("amostra-visao-leve", "Amostra de visão leve", ModelKind.VISION, EngineType.OCR, 256, 4_000_000L)
    val VISION_BALANCED = spec("amostra-visao-media", "Amostra de visão média", ModelKind.VISION, EngineType.OCR, 512, 27_000_000L)
    val VISION_HEAVY = spec("amostra-visao-pesada", "Amostra de visão pesada", ModelKind.VISION, EngineType.LITERT_LM, 2048, 590_000_000L)

    val VOICE_LIGHT = spec("amostra-voz-leve", "Amostra de voz leve", ModelKind.VOICE, EngineType.TTS, 256, 52_000_000L)
    val VOICE_BALANCED = spec("amostra-voz-media", "Amostra de voz média", ModelKind.VOICE, EngineType.TTS, 512, 87_000_000L)
    val VOICE_HEAVY = spec("amostra-voz-pesada", "Amostra de voz pesada", ModelKind.VOICE, EngineType.TTS, 1024, 143_000_000L)

    /** Do mais leve ao mais pesado, como as listas do catálogo. */
    val VISION: List<ModelSpec> = listOf(VISION_LIGHT, VISION_BALANCED, VISION_HEAVY)
    val VOICE: List<ModelSpec> = listOf(VOICE_LIGHT, VOICE_BALANCED, VOICE_HEAVY)
}

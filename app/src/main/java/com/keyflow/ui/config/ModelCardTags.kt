package com.keyflow.ui.config

import com.keyflow.data.models.ModelCapability
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ThinkingSupport
import com.keyflow.i18n.tr

/**
 * Uma etiqueta do card de modelo: o que o modelo faz, no que difere dos outros. [key] identifica a etiqueta nos testes (`...-tag-<key>`);
 * [description], quando existe, é o que o leitor de tela diz no lugar do texto curto.
 */
internal data class CardTag(val key: String, val text: String, val description: String? = null)

/**
 * As etiquetas do card, todas derivadas de dado que o catálogo já tem (nada é texto solto na tela): o idioma, o diferencial de
 * idioma que o catálogo declara, o modo pensamento, as capacidades verificadas e a RAM recomendada. O resumo (`summary`) continua no
 * [ModelSpec], mas não aparece mais no card.
 */
internal fun modelTags(spec: ModelSpec): List<CardTag> = buildList {
    if (spec.languages.isNotBlank()) add(CardTag("language", tr(spec.languages)))
    if (ModelCapability.NATIVE_PORTUGUESE in spec.capabilities) add(CardTag("native_portuguese", tr("Português nativo")))
    when (spec.thinking) {
        ThinkingSupport.NONE -> Unit
        ThinkingSupport.OPTIONAL -> add(CardTag("thinking", tr("Pensamento opcional")))
        ThinkingSupport.ALWAYS -> add(CardTag("thinking", tr("Sempre pensa")))
    }
    ModelCapability.entries.filter { it in spec.capabilities && it != ModelCapability.NATIVE_PORTUGUESE }.forEach { capability ->
        add(CardTag("capability_${capability.name.lowercase()}", capabilityLabel(capability)))
    }
    if (spec.recommendedRamMb > 0) {
        val ram = ramLabel(spec.recommendedRamMb)
        add(CardTag("ram", tr("≥ %s de RAM", ram), tr("Recomenda %s de RAM", ram)))
    }
}

internal fun capabilityLabel(capability: ModelCapability): String = when (capability) {
    ModelCapability.READ_TEXT -> tr("Ler texto da tela")
    ModelCapability.DESCRIBE_IMAGE -> tr("Descrever imagem")
    ModelCapability.ANSWER_ABOUT_SCREEN -> tr("Responder sobre a tela")
    ModelCapability.TOOLS -> tr("Ferramentas")
    ModelCapability.NATIVE_PORTUGUESE -> tr("Português nativo")
}

/** "512 MB", "3 GB": o catálogo recomenda em múltiplos de 512 MB; o que não for número redondo de GB fica em MB. */
internal fun ramLabel(mb: Int): String = if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

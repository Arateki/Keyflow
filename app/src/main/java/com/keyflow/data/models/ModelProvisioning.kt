package com.keyflow.data.models

/**
 * Provedor e resolvedor dinâmico de links e infraestrutura para download de modelos locais.
 * Permite que a infraestrutura própria (servidor ou API em Rust / Hust) provisione, espelhe
 * ou redirecione os pesos e artefatos de IA, garantindo alta disponibilidade sem dependência
 * exclusiva de fontes externas (como Hugging Face) fora do controle da organização.
 */
object ModelProvisioning {

    /**
     * URL base ou modelo de URL da infraestrutura própria (ex: "https://api.keyflow.internal/models"
     * ou "https://api.keyflow.internal/v1/models/download/{modelId}/{fileName}").
     * Quando configurada, as requisições de download são direcionadas através dessa rota.
     */
    @Volatile
    var customMirrorUrl: String? = null

    private val dynamicOverrides = mutableMapOf<String, String>()

    /**
     * Registra ou sobrescreve uma URL específica para um arquivo de um modelo,
     * permitindo que um endpoint da API em Rust provisione links assinados ou temporários dinamicamente.
     */
    fun setOverride(modelId: String, fileName: String, url: String) {
        synchronized(dynamicOverrides) {
            dynamicOverrides["$modelId/$fileName"] = url
        }
    }

    /**
     * Remove uma sobreposição específica.
     */
    fun removeOverride(modelId: String, fileName: String) {
        synchronized(dynamicOverrides) {
            dynamicOverrides.remove("$modelId/$fileName")
        }
    }

    /**
     * Limpa todas as sobreposições de URLs dinâmicas.
     */
    fun clearOverrides() {
        synchronized(dynamicOverrides) {
            dynamicOverrides.clear()
        }
    }

    /**
     * Resolve a URL de download para um arquivo de modelo.
     * 1. Verifica se há uma URL provisionada via [setOverride] para "$modelId/$fileName".
     * 2. Se houver [customMirrorUrl] (ou [mirrorOverride]), aplica a regra de espelhamento:
     *    - Se tiver marcações `{modelId}`, `{fileName}` ou `{url}`, faz a interpolação.
     *    - Se for uma URL base normal, anexa `/$modelId/$fileName`.
     * 3. Caso contrário, retorna a URL padrão do catálogo ([file.url]).
     */
    fun resolveUrl(modelId: String, file: ModelFile, mirrorOverride: String? = null): String {
        synchronized(dynamicOverrides) {
            val override = dynamicOverrides["$modelId/${file.fileName}"]
            if (!override.isNullOrBlank()) return override
        }

        val mirror = (mirrorOverride ?: customMirrorUrl)?.trim()
        if (!mirror.isNullOrBlank()) {
            return when {
                mirror.contains("{modelId}") || mirror.contains("{fileName}") || mirror.contains("{url}") -> {
                    mirror.replace("{modelId}", modelId)
                        .replace("{fileName}", file.fileName)
                        .replace("{url}", file.url)
                }
                mirror.endsWith("/") -> "${mirror}${modelId}/${file.fileName}"
                else -> "${mirror}/${modelId}/${file.fileName}"
            }
        }

        return file.url
    }
}

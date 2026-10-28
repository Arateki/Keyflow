package com.keyflow.vision

/** Um modelo de nuvem que lê imagem: o [id] vai na chamada, o [displayName] vai na tela. */
data class VisionCloudModel(val id: String, val displayName: String)

/** Os provedores de nuvem da Visão (PRV-01). O Custom não tem lista: o usuário digita o id (PRV-02). */
enum class VisionProvider { OPENAI, ANTHROPIC, GOOGLE }

/**
 * Os modelos de nuvem **com visão** que a aba Visão oferece, por provedor (PRV-02, vision-capture VC-T17). É dado curado no app, por id:
 * a listagem de modelos dos provedores não informa visão de forma confiável, então um id que não está aqui **não aparece** no seletor
 * (o Custom é a exceção: ali o usuário digita o id). Cada lista foi conferida na documentação oficial do provedor em **2026-10-28** — as
 * fontes e o que cada página disse estão em `.specs/project/licoes/2026-10-28-visao-aba.md`.
 *
 * Ordem: do modelo que o app propõe ([default], o primeiro de cada lista) para os demais. Mexer aqui é a manutenção esperada quando um
 * provedor lança ou aposenta um modelo; `VisionModelCatalogTest` trava o formato (sem repetidos, padrão dentro da lista).
 */
object VisionModelCatalog {

    // OpenAI — developers.openai.com/api/docs/models: "All latest OpenAI models support text and image input"; as páginas de cada
    // modelo (gpt-6-astra, gpt-6.1-sol, gpt-6.1-terra, gpt-6-luna) listam "Input modalities: text, image". O `gpt-4o` aparece como
    // deprecado na mesma documentação e por isso não entra; os modelos só de áudio/imagem (realtime, transcribe, image) não leem captura.
    private val OPENAI = listOf(
        VisionCloudModel("gpt-6.1-sol", "GPT-6.1-Sol"),
        VisionCloudModel("gpt-6-astra", "GPT-6-Astra"),
        VisionCloudModel("gpt-6.1-terra", "GPT-6.1-Terra"),
        VisionCloudModel("gpt-6-luna", "GPT-6-Luna"),
    )

    // Anthropic — platform.claude.com/docs/en/about-claude/models/overview: "All current models support text and image input, text
    // output, multilingual capabilities, vision, and tool use" (Fable 5.1, Opus 5.5, Sonnet 5.5, Haiku 5.5); a visão não limita modelo
    // (platform.claude.com/docs/en/build-with-claude/vision). Os "legacy (still available)" da mesma página entram: Fable 5, Opus 5,
    // Sonnet 5, Opus 4.8, Sonnet 4.6 e Haiku 4.5.
    private val ANTHROPIC = listOf(
        VisionCloudModel("claude-sonnet-5-5", "Claude Sonnet 5.5"),
        VisionCloudModel("claude-opus-5-5", "Claude Opus 5.5"),
        VisionCloudModel("claude-haiku-5-5", "Claude Haiku 5.5"),
        VisionCloudModel("claude-fable-5-1", "Claude Fable 5.1"),
        VisionCloudModel("claude-sonnet-5", "Claude Sonnet 5"),
        VisionCloudModel("claude-opus-5", "Claude Opus 5"),
        VisionCloudModel("claude-fable-5", "Claude Fable 5"),
        VisionCloudModel("claude-opus-4-8", "Claude Opus 4.8"),
        VisionCloudModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        VisionCloudModel("claude-haiku-4-5", "Claude Haiku 4.5"),
    )

    // Google — ai.google.dev/gemini-api/docs/image-understanding: "All Gemini model versions are multimodal"; as páginas
    // gemini-4-argon, gemini-3.8-flash e gemini-3.5-flash-lite listam "Text, Image, Video, Audio, PDF" como entrada e a de modelos os dá
    // como estáveis. O gemini-2.0-flash está desligado na mesma documentação e o 2.5 só atende quem já o usava: não entram.
    private val GOOGLE = listOf(
        VisionCloudModel("gemini-3.8-flash", "Gemini 3.8 Flash"),
        VisionCloudModel("gemini-4-argon", "Gemini 4 Argon"),
        VisionCloudModel("gemini-3.6-flash", "Gemini 3.6 Flash"),
        VisionCloudModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
        VisionCloudModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
    )

    /** Os modelos com visão de [provider], o padrão primeiro. */
    fun models(provider: VisionProvider): List<VisionCloudModel> = when (provider) {
        VisionProvider.OPENAI -> OPENAI
        VisionProvider.ANTHROPIC -> ANTHROPIC
        VisionProvider.GOOGLE -> GOOGLE
    }

    /** O modelo que o app propõe quando nada foi escolhido (ou o escolhido deixou de ter visão). */
    fun default(provider: VisionProvider): String = models(provider).first().id

    /** [id] está na lista curada de [provider]. */
    fun isVision(provider: VisionProvider, id: String): Boolean = models(provider).any { it.id == id }

    /**
     * O modelo que vale para [stored], o que as Settings guardam (PRV-02): o próprio se ainda tem visão; senão o padrão — vazio (nunca
     * escolhido) ou um modelo que saiu da lista, como o `gpt-4o` das versões antigas, que não lê captura nem deveria ser chamado.
     */
    fun resolve(provider: VisionProvider, stored: String): String = stored.takeIf { isVision(provider, it) } ?: default(provider)

    /**
     * O que o seletor de [provider] oferece. [account] é a lista que a conta devolveu (vazia = não veio, ou a rede falhou): com ela, só os
     * modelos com visão que a conta realmente tem; se a conta não tem nenhum deles, ou a lista não veio, a lista curada inteira — o seletor
     * nunca fica vazio nem oferece um modelo sem visão.
     */
    fun options(provider: VisionProvider, account: List<String>): List<VisionCloudModel> {
        val curated = models(provider)
        if (account.isEmpty()) return curated
        val offered = account.toSet()
        return curated.filter { it.id in offered }.ifEmpty { curated }
    }
}

package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.vision.VisionModelCatalog
import com.keyflow.vision.VisionProvider

/**
 * As tags de teste de um painel de provedor, todas derivadas de um prefixo (`raciocinio-openai`, `visao-anthropic`…). O Raciocínio e a Visão
 * montam o MESMO painel de cada provedor ([OpenAiPanel], [AnthropicPanel], [GooglePanel]); só o prefixo muda, para o teste de uma aba não
 * esbarrar nos nós da outra. `ProviderTagsTest` trava que os nomes do Raciocínio continuam os de sempre.
 */
internal class ProviderTags(val prefix: String) {
    val status = "$prefix-status"
    val error = "$prefix-erro"
    val login = "$prefix-entrar"
    val loginReason = "$prefix-entrar-motivo"
    val cancel = "$prefix-cancelar"
    val key = "$prefix-chave"
    val getKey = "$prefix-obter-chave"
    val disconnect = "$prefix-desconectar"
    val plan = "$prefix-plano"
    val limits = "$prefix-limites"
    val manageUsage = "$prefix-gerenciar-uso"
    val planNote = "$prefix-plano-nota"
    val planMissing = "$prefix-sem-plano"
    val model = "$prefix-modelo"
    val modelsEmpty = "$prefix-modelos-vazio"
    val refresh = "$prefix-atualizar"
    val privacy = "$prefix-privacy"
    val welcome = "$prefix-boas-vindas"
    val welcomeClose = "$prefix-boas-vindas-fechar"
    val welcomeGotIt = "$prefix-boas-vindas-entendi"
}

/**
 * O que difere entre as abas no painel de um provedor de nuvem: **qual campo das Settings guarda o modelo**, **de onde vem a lista do
 * seletor** e as tags. A conexão (login, chave, cofre) é a mesma nas duas — conectar numa aba vale na outra (PRV-01) —, então tudo o mais
 * do painel (estado do login, plano, aviso de 1º uso, atualizar, desconectar) é o mesmo código.
 */
internal interface ProviderModelSlot {
    val tags: ProviderTags

    /** O modelo a mostrar escolhido. Vazio = ainda não escolhido (o seletor diz "Escolha um modelo"). */
    fun chosen(settings: Settings): String
    fun chosen(settings: Settings, account: List<AccountModel>): String = chosen(settings)
    fun normalizeChoice(settings: Settings, account: List<AccountModel>): Settings = settings
    fun choose(settings: Settings, id: String): Settings

    /** Os modelos do seletor da aba Assinatura. [account] é a lista que a conta devolveu (vazia = não veio). */
    fun ssoOptions(account: List<AccountModel>, chosen: String): List<AccountModel>

    /** Os modelos do seletor da aba API. */
    fun apiKeyOptions(account: List<AccountModel>, chosen: String): List<AccountModel>

    /** A nota do rodapé do card; `null` = a do card (a do Testar do Raciocínio: "O texto do teste é enviado à …"). */
    fun privacyNote(): String? = null

    /** O bloco de limites de uso (5 h e semanal) do card da assinatura. Hoje só tem valores de exemplo; a Visão não os mostra. */
    val showsUsageLimits: Boolean get() = true
}

/** O modelo já escolhido continua na lista mesmo que a conta não o devolva agora (falha de rede, ou modelo que saiu). */
private fun List<AccountModel>.withChosen(chosen: String): List<AccountModel> =
    if (chosen.isNotEmpty() && none { it.id == chosen }) listOf(AccountModel(chosen, chosen)) + this else this

// ── Raciocínio: o comportamento que as abas já tinham, movido para cá sem mudar ──────────────────────────────────────────────────

internal object ReasoningOpenAiSlot : ProviderModelSlot {
    override val tags = ProviderTags("raciocinio-openai")
    // Vazio = ainda não escolhido (RCL-06): o motor só responde com um modelo escolhido, então o seletor não finge um padrão.
    override fun chosen(settings: Settings) = settings.reasoningOpenAiModel
    override fun choose(settings: Settings, id: String) = settings.copy(reasoningOpenAiModel = id)
    override fun ssoOptions(account: List<AccountModel>, chosen: String) = account.ifEmpty { OPENAI_SSO_MODELS }.withChosen(chosen)
    override fun apiKeyOptions(account: List<AccountModel>, chosen: String) = account.ifEmpty { OPENAI_API_KEY_MODELS }.withChosen(chosen)
}

internal object ReasoningAnthropicSlot : ProviderModelSlot {
    override val tags = ProviderTags("raciocinio-anthropic")
    override fun chosen(settings: Settings) = settings.reasoningAnthropicModel.ifEmpty { "claude-sonnet-4-6" }
    override fun choose(settings: Settings, id: String) = settings.copy(reasoningAnthropicModel = id)
    override fun ssoOptions(account: List<AccountModel>, chosen: String) = account.ifEmpty { ANTHROPIC_SSO_MODELS }
    override fun apiKeyOptions(account: List<AccountModel>, chosen: String) = ANTHROPIC_API_KEY_MODELS
}

internal object ReasoningGoogleSlot : ProviderModelSlot {
    override val tags = ProviderTags("raciocinio-google")
    override fun chosen(settings: Settings) = settings.reasoningGoogleModel.ifEmpty { "gemini-2.0-flash" }
    override fun choose(settings: Settings, id: String) = settings.copy(reasoningGoogleModel = id)
    override fun ssoOptions(account: List<AccountModel>, chosen: String) = account.ifEmpty { GOOGLE_SSO_MODELS }
    override fun apiKeyOptions(account: List<AccountModel>, chosen: String) = GOOGLE_API_KEY_MODELS
}

// ── Visão: só modelos com visão (PRV-02) ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * O seletor da Visão para um provedor: a lista é a curada de [VisionModelCatalog] (cruzada com a da conta, quando ela veio), e o modelo
 * guardado que não está na lista efetiva cai para o primeiro disponível. A nota de privacidade diz que a IMAGEM sai do aparelho (PRV-04).
 */
internal class VisionCloudSlot(
    private val provider: VisionProvider,
    override val tags: ProviderTags,
    private val read: (Settings) -> String,
    private val write: (Settings, String) -> Settings,
    private val privacy: () -> String,
) : ProviderModelSlot {
    override fun chosen(settings: Settings) = VisionModelCatalog.resolve(provider, read(settings))
    override fun chosen(settings: Settings, account: List<AccountModel>) =
        VisionModelCatalog.resolve(provider, read(settings), account.map { it.id })
    override fun normalizeChoice(settings: Settings, account: List<AccountModel>): Settings =
        if (account.isEmpty()) settings else choose(settings, chosen(settings, account))
    override fun choose(settings: Settings, id: String) = write(settings, id)
    override fun ssoOptions(account: List<AccountModel>, chosen: String) = options(account)
    override fun apiKeyOptions(account: List<AccountModel>, chosen: String) = options(account)
    override fun privacyNote() = privacy()
    override val showsUsageLimits: Boolean get() = false

    private fun options(account: List<AccountModel>): List<AccountModel> =
        VisionModelCatalog.options(provider, account.map { it.id }).map { AccountModel(it.id, it.displayName) }

    companion object {
        val OpenAi = VisionCloudSlot(
            VisionProvider.OPENAI, ProviderTags("visao-openai"),
            { it.visionOpenAiModel }, { s, id -> s.copy(visionOpenAiModel = id) },
            { tr("A imagem da captura é enviada à OpenAI; as credenciais ficam só neste aparelho.") },
        )
        val Anthropic = VisionCloudSlot(
            VisionProvider.ANTHROPIC, ProviderTags("visao-anthropic"),
            { it.visionAnthropicModel }, { s, id -> s.copy(visionAnthropicModel = id) },
            { tr("A imagem da captura é enviada à Anthropic; as credenciais ficam só neste aparelho.") },
        )
        val Google = VisionCloudSlot(
            VisionProvider.GOOGLE, ProviderTags("visao-google"),
            { it.visionGoogleModel }, { s, id -> s.copy(visionGoogleModel = id) },
            { tr("A imagem da captura é enviada ao Google; as credenciais ficam só neste aparelho.") },
        )
    }
}

/** A lista efetiva resolve também o valor persistido, sem sobrescrever outras edições da tela. */
@Composable
internal fun ProviderModelSlot.selectedModel(
    settings: Settings,
    account: List<AccountModel>,
    onSettingsChange: (Settings) -> Unit,
): String {
    val editor = rememberSettingsEditor(settings, onSettingsChange)
    LaunchedEffect(this, account) { editor.edit { normalizeChoice(it, account) } }
    return chosen(settings, account)
}

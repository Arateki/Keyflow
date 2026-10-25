package com.keyflow.agent.tools

import com.keyflow.agent.ToolFailure
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey

/** O valor de um ajuste, no que o agente conhece: sempre um destes três (nunca um objeto do app). */
sealed interface SettingValue {
    data class Flag(val on: Boolean) : SettingValue
    data class Num(val n: Int) : SettingValue
    data class Text(val s: String) : SettingValue
}

/** O tipo e a faixa de um ajuste da lista de permitidos. */
sealed interface SettingType {
    /** Liga/desliga. */
    data object Flag : SettingType

    /** Inteiro de [min] a [max], em múltiplos de [step] (a mesma faixa e o mesmo passo do controle na tela). */
    data class IntRange(val min: Int, val max: Int, val step: Int = 1, val unit: String = "") : SettingType

    /** Uma das [options] (minúsculas). */
    data class Choice(val options: List<String>) : SettingType

    /** O id de uma skin em uso (só leitura: quem muda é o `skin_select`). */
    data object SkinId : SettingType

    /** O identificador de um modelo da nuvem ("gpt-4o", "claude-sonnet-5-5"): só caracteres de nome de modelo, nunca um endereço nem um segredo. */
    data class ModelName(val maxLength: Int = 100, val stripPrefix: String? = null, val singleSegment: Boolean = false) : SettingType
}

/**
 * Um ajuste que o agente pode ler e (se [writable]) mudar — uma linha da LISTA DE PERMITIDOS (D4, TLS-03, APP-02). O que não está
 * em [AppSettingsAllowlist] não existe para o modelo: não aparece em `settings_get`, não entra no esquema de `settings_set`.
 *
 * @property key o nome do campo em `Settings` (um teste confere que existe, tem o tipo certo e NÃO é um campo secreto).
 * @property labelPt o nome que o usuário lê, em português (traduzido por [label]).
 * @property description o que é, para o modelo (português fixo).
 * @property writable `false` = só leitura por aqui ([readOnlyHint] diz onde se muda: as skins, por `skin_select`, que confere a skin e o direito).
 */
class AllowedSetting(
    val key: String,
    val type: SettingType,
    val labelPt: String,
    val description: String,
    val writable: Boolean = true,
    val readOnlyHint: String? = null,
) {
    /** O nome no idioma da interface (o painel de confirmação e o registro). */
    val label: String get() = tr(labelPt)

    /** A faixa em palavras, para o modelo ler: "inteiro de 110 a 280, de 1 em 1 (px)". */
    fun rangeText(): String = when (type) {
        SettingType.Flag -> "true ou false"
        is SettingType.IntRange -> "inteiro de ${type.min} a ${type.max}" + (if (type.step > 1) ", em múltiplos de ${type.step}" else "") + (if (type.unit.isNotEmpty()) " (${type.unit})" else "")
        is SettingType.Choice -> "um destes: ${type.options.joinToString(", ")}"
        is SettingType.ModelName -> if (type.singleSegment) {
            "nome de modelo (letras, dígitos, . _ -; um único segmento; até ${type.maxLength} caracteres)"
        } else {
            "nome de modelo (letras, dígitos, . _ - e, entre partes, um / ou :; sem endereço; até ${type.maxLength} caracteres)"
        }
        SettingType.SkinId -> "id de skin"
    }

    /**
     * Lê o que o modelo escreveu em `value` (sempre um texto, porque o esquema de argumentos não tem união de tipos) e confere com o tipo
     * e a faixa. Lança [ToolFailure] com o motivo — o modelo o recebe e pode se corrigir; nada chega ao app.
     */
    fun parse(raw: String): SettingValue {
        val text = raw.trim()
        return when (type) {
            SettingType.Flag -> when (text.lowercase()) {
                "true" -> SettingValue.Flag(true)
                "false" -> SettingValue.Flag(false)
                else -> throw ToolFailure("«$key» liga ou desliga: o valor precisa ser true ou false.")
            }
            is SettingType.IntRange -> {
                val n = if (INTEGER.matches(text)) text.toInt() else throw ToolFailure("«$key» recebe um número inteiro (${rangeText()}).")
                if (n < type.min || n > type.max) throw ToolFailure("«$key» fora da faixa: $n não está entre ${type.min} e ${type.max}.")
                if ((n - type.min) % type.step != 0) throw ToolFailure("«$key» muda em múltiplos de ${type.step}: use um valor como ${type.min + ((n - type.min) / type.step) * type.step}.")
                SettingValue.Num(n)
            }
            is SettingType.Choice -> {
                val option = type.options.firstOrNull { it == text.lowercase() } ?: throw ToolFailure("«$key» aceita só ${rangeText()}.")
                SettingValue.Text(option)
            }
            is SettingType.ModelName -> {
                var candidate = text
                if (type.stripPrefix != null) candidate = candidate.removePrefix(type.stripPrefix)
                val regex = if (type.singleSegment) SINGLE_SEGMENT_MODEL_NAME else MODEL_NAME
                if (candidate.isEmpty() || candidate.length > type.maxLength || candidate.contains("..") || !regex.matches(candidate)) {
                    throw ToolFailure("«$key» recebe o nome de um modelo (${rangeText()}).")
                }
                SettingValue.Text(candidate)
            }
            // Só leitura: nunca chega aqui (o esquema de `settings_set` não lista a chave); recusar é a defesa se alguém a listar por engano.
            SettingType.SkinId -> throw ToolFailure("«$key» só pode ser lido aqui. ${readOnlyHint ?: ""}".trim())
        }
    }

    /** O valor que o app devolveu, conferido com o tipo: algo fora dele (dado corrompido) vira `null` em vez de seguir como se valesse. */
    fun coerce(value: Any?): SettingValue? = when (type) {
        SettingType.Flag -> (value as? Boolean)?.let { SettingValue.Flag(it) }
        is SettingType.IntRange -> (value as? Int)?.let { SettingValue.Num(it) }
        is SettingType.Choice, is SettingType.ModelName, SettingType.SkinId -> (value as? String)?.let { SettingValue.Text(it) }
    }

    /** O valor como o USUÁRIO lê (no painel e no registro), no idioma da interface. */
    fun show(value: SettingValue): String = when (value) {
        is SettingValue.Flag -> if (value.on) tr("ligado") else tr("desligado")
        is SettingValue.Num -> value.n.toString() + ((type as? SettingType.IntRange)?.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        is SettingValue.Text -> value.s
    }

    /** O valor como o MODELO lê no resultado: o primitivo do JSON. */
    fun json(value: SettingValue): Any = when (value) {
        is SettingValue.Flag -> value.on
        is SettingValue.Num -> value.n
        is SettingValue.Text -> value.s
    }

    /** O valor como texto de `value` (o que `parse` lê de volta): é o que o "desfazer" guarda. */
    fun argText(value: SettingValue): String = when (value) {
        is SettingValue.Flag -> value.on.toString()
        is SettingValue.Num -> value.n.toString()
        is SettingValue.Text -> value.s
    }

    private companion object {
        val INTEGER = Regex("-?[0-9]{1,9}")
        val SINGLE_SEGMENT_MODEL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        /**
         * Segmentos de nome de modelo separados por UM `/` ou `:` (ou `::`, o dos modelos ajustados da OpenAI): `gpt-4o`, `models/gemini-pro`,
         * `ft:gpt-4o-mini:org::abc`. Nunca um endereço (`https://…` tem `:` seguido de `/`) nem um caminho que sobe (`..`): o nome do modelo
         * entra no caminho da URL de alguns provedores.
         */
        val MODEL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(?:(?::{1,2}|/)[A-Za-z0-9][A-Za-z0-9._-]*)*")
    }
}

/**
 * A LISTA DE PERMITIDOS dos ajustes que o agente enxerga (D4, TLS-03): uma tabela no código, cada linha com tipo e faixa. Só campos NÃO
 * secretos e de baixo risco — aparência, tamanhos, vibração, a janela de rajada e a ESCOLHA de modelo (nunca uma credencial).
 *
 * **Fora, sempre**: tudo de `SettingsSecrets` (valores de cabeçalho de autenticação, senha e chave SSH), os `custom*Endpoint`/`*HeaderName`,
 * o espelho de modelos, o SSH, as origens e os modos de autenticação. O `AppSettingsAllowlistTest` FALHA se um campo secreto, um endereço
 * ou um nome com cara de credencial entrar aqui, se uma chave não existir em `Settings` ou tiver outro tipo, e se o padrão do app sair
 * da faixa; e o `AgentSettingKeysTest` confere que o app só grava as chaves desta tabela.
 *
 * Acrescentar uma linha é decidir, de propósito, que o modelo pode mexer naquilo — e o `settings_set` continua pedindo permissão.
 */
object AppSettingsAllowlist {

    val all: List<AllowedSetting> = listOf(
        AllowedSetting("themeDark", SettingType.Flag, trKey("Tema escuro"), "Tema escuro do app e do teclado (true) ou claro (false)."),
        AllowedSetting("keyboardLanguage", SettingType.Choice(listOf("pt", "en", "es")), trKey("Idioma do teclado"), "Idioma do teclado KeyFlow: pt, en ou es (não é o idioma da interface do app)."),
        AllowedSetting("keyboardScalePercent", SettingType.IntRange(70, 130, unit = "%"), trKey("Tamanho do teclado"), "Tamanho do teclado KeyFlow em porcentagem (100 = o tamanho padrão)."),
        AllowedSetting("keyboardNumberRowEnabled", SettingType.Flag, trKey("Fileira de números"), "Fileira dedicada de números acima das letras do teclado."),
        AllowedSetting("orbSizePx", SettingType.IntRange(110, 280, unit = "px"), trKey("Tamanho da orbe"), "Tamanho da orbe flutuante (modo Sobrepor), em pixels."),
        AllowedSetting("vibrationIntensity", SettingType.IntRange(0, 100), trKey("Intensidade da vibração"), "Vibração ao interagir com a orbe: 0 = sem vibração, 100 = a mais forte."),
        AllowedSetting("tapBurstWindowMs", SettingType.IntRange(100, 700, step = 10, unit = "ms"), trKey("Janela de rajada de toques"), "Intervalo máximo entre toques na orbe para contar como uma rajada (atalhos de 2 e 3 toques), em milissegundos."),
        AllowedSetting(
            "keyboardSkin", SettingType.SkinId, trKey("Skin do teclado"),
            "Id da skin do teclado em uso (system, atelie, um id de skin do app ou pack:<id> de um pacote instalado).",
            writable = false, readOnlyHint = "Para mudar a skin use skin_select.",
        ),
        AllowedSetting(
            "orbSkin", SettingType.SkinId, trKey("Skin da orbe"),
            "Id da skin da orbe em uso (atelie, aero, um id de skin do app ou pack:<id> de um pacote instalado).",
            writable = false, readOnlyHint = "Para mudar a skin use skin_select.",
        ),
        AllowedSetting("reasoningAnthropicModel", SettingType.ModelName(), trKey("Modelo da Anthropic (Raciocínio)"), "Nome do modelo da Anthropic usado na aba Raciocínio e pelo agente."),
        AllowedSetting("reasoningOpenAiModel", SettingType.ModelName(), trKey("Modelo da OpenAI (Raciocínio)"), "Nome do modelo da OpenAI usado na aba Raciocínio."),
        AllowedSetting(
            "reasoningGoogleModel",
            SettingType.ModelName(stripPrefix = "models/", singleSegment = true),
            trKey("Modelo do Google (Raciocínio)"),
            "Nome do modelo do Google (Gemini) usado na aba Raciocínio."
        ),
    )

    private val byKey: Map<String, AllowedSetting> = all.associateBy { it.key }

    operator fun get(key: String): AllowedSetting? = byKey[key]

    /** Os que o modelo pode mudar. */
    val writable: List<AllowedSetting> = all.filter { it.writable }
}

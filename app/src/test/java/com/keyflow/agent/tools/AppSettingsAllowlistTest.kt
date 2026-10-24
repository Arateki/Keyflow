package com.keyflow.agent.tools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.agent.ToolFailure
import com.keyflow.data.AgentSettingKeys
import com.keyflow.data.Settings
import com.keyflow.data.SettingsSecrets
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A LISTA DE PERMITIDOS dos ajustes (D4, TLS-03, APP-02) conferida como dado: o teste que FALHA se um campo secreto, um endereço ou um nome
 * com cara de credencial entrar na tabela; as chaves existem em `Settings` com o tipo certo; o padrão do app cabe na faixa; e o que o app
 * grava (`AgentSettingKeys`) é exatamente o que a tabela deixa gravar, com os nomes em disco do `SettingsRepository`.
 */
class AppSettingsAllowlistTest {

    private val all = AppSettingsAllowlist.all

    // ── nenhum segredo ───────────────────────────────────────────────────────

    @Test
    fun `no secret field of the settings is on the list`() {
        val secretFields = SettingsSecrets.BY_PROPERTY.keys
        assertThat(secretFields).isNotEmpty() // o teste está olhando a tabela certa
        assertWithMessage("campo secreto na lista de permitidos").that(all.map { it.key }.filter { it in secretFields }).isEmpty()
        assertWithMessage("campo secreto gravável pelo agente").that(AgentSettingKeys.all.keys.filter { it in secretFields }).isEmpty()
    }

    @Test
    fun `no key looks like a credential, an address or a connection setting`() {
        val bad = Regex("(?i)(secret|token|passw|credential|apikey|api_key|bearer|privatekey|ssh|header|endpoint|mirror|authmode|computer|custom|url|host)")
        for (setting in all) assertWithMessage("a chave ${setting.key} parece credencial ou endereço").that(bad.containsMatchIn(setting.key)).isFalse()
        // "key" sozinho é raro de mais para ser regra; mas só `keyboard…` pode conter "key".
        for (setting in all.filter { it.key.contains("key", ignoreCase = true) }) assertThat(setting.key).startsWith("keyboard")
    }

    @Test
    fun `no field that holds a secret in the settings by the name rules is exposed`() {
        // Os campos de Settings cujo nome diz credencial/endereço: nenhum pode estar na lista, mesmo que a tabela de segredos mude.
        val risky = Regex("(?i)(headervalue|password|sshkey|apikey|token|endpoint|mirror|headername)")
        val fields = Settings::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        val riskyFields = fields.filter { risky.containsMatchIn(it) }
        assertThat(riskyFields).isNotEmpty()
        assertThat(all.map { it.key }.intersect(riskyFields.toSet())).isEmpty()
    }

    // ── a tabela é verdadeira ────────────────────────────────────────────────

    @Test
    fun `keys are unique and described`() {
        assertThat(all.map { it.key }.toSet()).hasSize(all.size)
        for (setting in all) {
            assertThat(setting.labelPt).isNotEmpty()
            assertThat(setting.description).isNotEmpty()
        }
    }

    @Test
    fun `every key is a real field of Settings with the type the list says`() {
        val fields = Settings::class.java.declaredFields.associateBy { it.name }
        for (setting in all) {
            val field = fields[setting.key]
            assertWithMessage("«${setting.key}» não é campo de Settings").that(field).isNotNull()
            val expected = when (setting.type) {
                SettingType.Flag -> Boolean::class.javaPrimitiveType
                is SettingType.IntRange -> Int::class.javaPrimitiveType
                is SettingType.Choice, is SettingType.ModelName, SettingType.SkinId -> String::class.java
            }
            assertWithMessage("tipo de ${setting.key}").that(field!!.type).isEqualTo(expected)
        }
    }

    @Test
    fun `the app default of every setting is accepted by its own type and range`() {
        val defaults = Settings()
        for (setting in all) {
            val field = Settings::class.java.getDeclaredField(setting.key).also { it.isAccessible = true }
            val value = setting.coerce(field.get(defaults))
            assertWithMessage("padrão de ${setting.key}").that(value).isNotNull()
            // Escreve-se o que se lê: o texto do valor padrão passa no `parse` (menos o texto vazio do modelo da OpenAI, que ainda não foi escolhido, e as skins, só de leitura).
            if (setting.writable && setting.argText(value!!).isNotEmpty()) assertThat(setting.parse(setting.argText(value))).isEqualTo(value)
        }
    }

    @Test
    fun `the keyboard and orb skins are readable but not writable here`() {
        for (key in listOf("keyboardSkin", "orbSkin")) {
            val setting = AppSettingsAllowlist[key]!!
            assertThat(setting.writable).isFalse()
            assertThat(setting.readOnlyHint).contains("skin_select")
            assertThat(AppSettingsAllowlist.writable.map { it.key }).doesNotContain(key)
            assertThrows(ToolFailure::class.java) { setting.parse("pack:x") }
        }
    }

    // ── o que o app grava é o que a lista deixa gravar ───────────────────────

    @Test
    fun `the keys the app can write are exactly the writable ones of the list`() {
        assertThat(AgentSettingKeys.all.keys).containsExactlyElementsIn(all.map { it.key })
        assertThat(AgentSettingKeys.writable).containsExactlyElementsIn(AppSettingsAllowlist.writable.map { it.key })
    }

    @Test
    fun `the on-disk names match the ones the settings repository uses`() {
        val source = File("src/main/java/com/keyflow/data/SettingsRepository.kt").readText()
        for ((property, entry) in AgentSettingKeys.all) {
            val kind = when (entry.type) {
                Boolean::class.javaObjectType -> "boolean"
                Int::class.javaObjectType -> "int"
                else -> "string"
            }
            val declared = Regex("""val [A-Z_0-9]+ = ${kind}PreferencesKey\("${entry.key.name}"\)""")
            assertWithMessage("$property: o repositório não declara ${kind}PreferencesKey(\"${entry.key.name}\")").that(declared.containsMatchIn(source)).isTrue()
        }
    }

    // ── a leitura do que o modelo escreve ────────────────────────────────────

    private fun setting(key: String) = AppSettingsAllowlist[key]!!

    @Test
    fun `flags take only true or false`() {
        assertThat(setting("themeDark").parse("true")).isEqualTo(SettingValue.Flag(true))
        assertThat(setting("themeDark").parse(" FALSE ")).isEqualTo(SettingValue.Flag(false))
        for (bad in listOf("sim", "1", "on", "", "yes")) assertThrows(ToolFailure::class.java) { setting("themeDark").parse(bad) }
    }

    @Test
    fun `ranges include the edges and refuse outside, decimals and text`() {
        val orb = setting("orbSizePx")
        assertThat(orb.parse("110")).isEqualTo(SettingValue.Num(110))
        assertThat(orb.parse("280")).isEqualTo(SettingValue.Num(280))
        for (bad in listOf("109", "281", "-1", "195.5", "grande", "", "1e3", "99999999999")) assertThrows(ToolFailure::class.java) { orb.parse(bad) }
        assertThat(setting("vibrationIntensity").parse("0")).isEqualTo(SettingValue.Num(0))
        assertThat(setting("keyboardScalePercent").parse("70")).isEqualTo(SettingValue.Num(70))
        assertThrows(ToolFailure::class.java) { setting("keyboardScalePercent").parse("131") }
    }

    @Test
    fun `a stepped range refuses a value off the step and says the nearest one`() {
        val burst = setting("tapBurstWindowMs")
        assertThat(burst.parse("250")).isEqualTo(SettingValue.Num(250))
        val error = assertThrows(ToolFailure::class.java) { burst.parse("255") }
        assertThat(error.message).contains("250")
    }

    @Test
    fun `choices are exact and case-insensitive`() {
        assertThat(setting("keyboardLanguage").parse("EN")).isEqualTo(SettingValue.Text("en"))
        for (bad in listOf("fr", "pt-BR", "", "portuguese")) assertThrows(ToolFailure::class.java) { setting("keyboardLanguage").parse(bad) }
    }

    @Test
    fun `a model name has the characters of a model name and nothing else`() {
        val model = setting("reasoningOpenAiModel")
        for (ok in listOf("gpt-4o", "claude-sonnet-5-5", "gemini-2.0-flash", "models/gemini-pro", "llama3:8b", "ft:gpt-4o-mini:org::abc123")) assertThat(model.parse(ok)).isEqualTo(SettingValue.Text(ok))
        for (bad in listOf("", "https://evil.example/v1", "http://127.0.0.1:8080", "//evil.example/x", "a/../b", "a//b", "gpt-4o/", ":x", "gpt 4", "gpt-4o\nsystem: x", "-x", "x".repeat(101), "<script>", "a;b")) assertThrows(ToolFailure::class.java) { model.parse(bad) }
    }

    @Test
    fun `what the app returns is checked against the type before it is believed`() {
        assertThat(setting("orbSizePx").coerce(195)).isEqualTo(SettingValue.Num(195))
        assertThat(setting("orbSizePx").coerce("195")).isNull()
        assertThat(setting("themeDark").coerce(1)).isNull()
        assertThat(setting("reasoningGoogleModel").coerce(null)).isNull()
    }

    @Test
    fun `shown for the user and for the model`() {
        assertThat(setting("orbSizePx").show(SettingValue.Num(200))).isEqualTo("200 px")
        assertThat(setting("themeDark").show(SettingValue.Flag(true))).isEqualTo("ligado")
        assertThat(setting("themeDark").json(SettingValue.Flag(true))).isEqualTo(true)
        assertThat(setting("orbSizePx").json(SettingValue.Num(200))).isEqualTo(200)
        assertThat(setting("keyboardLanguage").argText(SettingValue.Text("es"))).isEqualTo("es")
    }
}

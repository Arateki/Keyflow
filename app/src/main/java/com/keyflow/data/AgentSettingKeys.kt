package com.keyflow.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Os ajustes que o agente pode ler e gravar (APP-02), como CHAVES DO DATASTORE: uma linha por item da lista de permitidos
 * (`AppSettingsAllowlist`, no núcleo do agente). É o ÚNICO lugar por onde o agente escreve nas Settings — [SettingsRepository.updateAgentSettings]
 * só grava chaves desta tabela, então um campo secreto não é gravável mesmo que alguém o liste por engano na outra ponta.
 *
 * Cada linha liga o nome do campo em [Settings] (conferido pelo compilador, no `default`) à chave do DataStore (o nome em disco, conferido
 * pelo `AgentSettingKeysTest` contra o `SettingsRepository`). Nenhuma chave aqui guarda segredo — os segredos moram nos cofres, fora do
 * DataStore (ver [SettingsSecrets]).
 */
internal object AgentSettingKeys {

    /** Uma chave: lê (com o padrão do app) e grava um valor do tipo certo — outro tipo é recusado em vez de convertido. */
    class Entry<T : Any>(
        val property: String,
        val key: Preferences.Key<T>,
        val type: Class<T>,
        /** O valor quando o DataStore ainda não tem a chave: o mesmo que o `Settings` e o `settingsFlow` usam. */
        val default: (prefs: Preferences, defaults: Settings) -> T,
    ) {
        fun read(prefs: Preferences, defaults: Settings): Any = prefs[key] ?: default(prefs, defaults)

        fun write(prefs: MutablePreferences, value: Any) {
            require(type.isInstance(value)) { "$property espera ${type.simpleName}" }
            prefs[key] = type.cast(value)!!
        }
    }

    private fun flag(property: String, name: String, default: (Settings) -> Boolean) =
        Entry(property, booleanPreferencesKey(name), Boolean::class.javaObjectType) { _, d -> default(d) }

    private fun number(property: String, name: String, default: (Settings) -> Int) =
        Entry(property, intPreferencesKey(name), Int::class.javaObjectType) { _, d -> default(d) }

    private fun text(property: String, name: String, default: (Settings) -> String) =
        Entry(property, stringPreferencesKey(name), String::class.java) { _, d -> default(d) }

    /** O nome em disco da intensidade de vibração do app antigo (liga/desliga): `false` virava 0 e o resto 60. */
    private val LEGACY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")

    /** Todas as chaves que o agente enxerga, por nome de campo. Os que o agente só LÊ (as skins) também estão aqui; a gravação é do `skin_select`. */
    val all: Map<String, Entry<*>> = listOf<Entry<*>>(
        flag("themeDark", "theme_dark") { it.themeDark },
        text("keyboardLanguage", "keyboard_language") { it.keyboardLanguage },
        number("keyboardScalePercent", "keyboard_scale_percent") { it.keyboardScalePercent },
        flag("keyboardNumberRowEnabled", "keyboard_number_row_enabled") { it.keyboardNumberRowEnabled },
        number("orbSizePx", "orb_size_px") { it.orbSizePx },
        // O app antigo só tinha liga/desliga (`haptic_feedback`): a leitura do app (settingsFlow) cai nele quando a chave nova não existe.
        Entry("vibrationIntensity", intPreferencesKey("vibration_intensity"), Int::class.javaObjectType) { prefs, d ->
            if (prefs[LEGACY_HAPTIC_FEEDBACK] == false) 0 else d.vibrationIntensity
        },
        number("tapBurstWindowMs", "tap_burst_window_ms") { it.tapBurstWindowMs },
        text("keyboardSkin", "keyboard_skin") { it.keyboardSkin },
        text("orbSkin", "orb_skin") { it.orbSkin },
        text("reasoningAnthropicModel", "reasoning_anthropic_model") { it.reasoningAnthropicModel },
        text("reasoningOpenAiModel", "reasoning_openai_model") { it.reasoningOpenAiModel },
        text("reasoningGoogleModel", "reasoning_google_model") { it.reasoningGoogleModel },
    ).associateBy { it.property }

    /** As que o agente pode GRAVAR (as skins ficam de fora: `skin_select` as confere). */
    val writable: Set<String> = all.keys - setOf("keyboardSkin", "orbSkin")
}

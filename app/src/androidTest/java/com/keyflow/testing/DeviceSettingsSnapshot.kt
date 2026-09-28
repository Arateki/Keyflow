package com.keyflow.testing

import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tira a config de quem é dono do aparelho do caminho da suíte e devolve no fim.
 *
 * Os testes do teclado liam a config VIVA do app — barras, idioma, fileira de números, expandido… — e
 * quebravam conforme o que o Yan tivesse montado: com o mic fora das barras dele, 5 testes do teclado
 * falharam no A32 (2026-09-28) e passavam no emulador. Pedido do Yan: a suíte no aparelho parte da config
 * de fábrica, como numa instalação limpa, e não da dele.
 *
 * [enterFactoryState] grava a config atual em [file] e zera o DataStore e as prefs do teclado;
 * [restoreOwnerState] devolve o que está em [file] e apaga o arquivo. Se a suíte morrer no meio (o
 * processo cai e o fim da rodada não chega), o arquivo fica: a próxima rodada NÃO o sobrescreve com o
 * estado de fábrica — usa-o como a config real e devolve no fim dela.
 *
 * Teclado padrão: se for o KeyFlow, a suíte começa com OUTRO teclado habilitado como padrão e devolve o
 * KeyFlow no fim. Medido no A32 (2026-09-28): com o KeyFlow já de pé como IME quando a config é zerada,
 * os testes do teclado esperavam 30 s e o teclado nunca anexava ao campo; com outro IME como padrão no
 * início (o KeyFlow sobe do zero quando o teste o seleciona), o mesmo teste passa — e é assim que o
 * emulador sempre rodou.
 *
 * Fora daqui de propósito: tokens de API (credenciais, não configuração, e cifrados no Keystore) e
 * `show_ime_with_hard_keyboard`, que o [com.keyflow.ime.UserSettingsGuard] guarda e devolve teste a teste.
 */
class DeviceSettingsSnapshot(
    private val repo: SettingsRepository,
    private val uiPrefs: SharedPreferences,
    private val file: File,
    private val shell: (String) -> String = { "" },
    private val ownImeId: String = KEYFLOW_IME,
) {
    val hasPendingSnapshot: Boolean get() = file.exists()

    fun enterFactoryState() {
        if (!file.exists()) {
            val current = runBlocking { repo.rawPreferencesOnce() }
            val json = JSONObject()
                .put("datastore", encode(current.asMap().mapKeys { it.key.name }))
                .put("ui", encode(uiPrefs.all.filterValues { it != null }.mapValues { it.value!! }))
            currentIme()?.let { json.put("defaultIme", it) }
            // Escreve num temporário e renomeia: um arquivo pela metade seria pior que nenhum.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            check(tmp.renameTo(file)) { "não consegui gravar a config do dono em $file" }
        }
        if (currentIme() == ownImeId) otherEnabledIme()?.let { shell("ime set $it") }
        runBlocking { repo.replaceRawPreferences(emptyPreferences()) }
        uiPrefs.edit().clear().commit()
    }

    /** Devolve a config guardada; `false` se não havia nada guardado. */
    fun restoreOwnerState(): Boolean {
        if (!file.exists()) return false
        val json = JSONObject(file.readText())
        runBlocking { repo.replaceRawPreferences(decodePreferences(json.getJSONArray("datastore"))) }
        val ui = uiPrefs.edit().clear()
        val entries = json.getJSONArray("ui")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val key = entry.getString("key")
            when (entry.getString("type")) {
                "string" -> ui.putString(key, entry.getString("value"))
                "int" -> ui.putInt(key, entry.getInt("value"))
                "boolean" -> ui.putBoolean(key, entry.getBoolean("value"))
                "long" -> ui.putLong(key, entry.getLong("value"))
                "float" -> ui.putFloat(key, entry.getDouble("value").toFloat())
                "stringSet" -> ui.putStringSet(key, entry.getJSONArray("value").toStringSet())
            }
        }
        ui.commit()
        json.optString("defaultIme").takeIf { it.isNotBlank() }?.let { shell("ime set $it") }
        file.delete()
        return true
    }

    private fun currentIme(): String? =
        shell("settings get secure default_input_method").trim().takeIf { it.isNotBlank() && it != "null" }

    /** Primeiro IME habilitado que não é o KeyFlow (o do sistema, na prática). */
    private fun otherEnabledIme(): String? =
        shell("settings get secure enabled_input_methods").trim()
            .split(':')
            .map { it.substringBefore(';') }
            .firstOrNull { it.isNotBlank() && it != "null" && it != ownImeId }

    private fun encode(values: Map<String, Any>): JSONArray = JSONArray().apply {
        values.toSortedMap().forEach { (key, value) ->
            val (type, encoded) = when (value) {
                is String -> "string" to value
                is Int -> "int" to value
                is Boolean -> "boolean" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is Double -> "double" to value
                is Set<*> -> "stringSet" to JSONArray(value.map { it.toString() })
                else -> error("tipo de preferência sem suporte no snapshot: ${value::class} ($key)")
            }
            put(JSONObject().put("key", key).put("type", type).put("value", encoded))
        }
    }

    private fun decodePreferences(entries: JSONArray): Preferences {
        val prefs = mutablePreferencesOf()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val key = entry.getString("key")
            when (entry.getString("type")) {
                "string" -> prefs[stringPreferencesKey(key)] = entry.getString("value")
                "int" -> prefs[intPreferencesKey(key)] = entry.getInt("value")
                "boolean" -> prefs[booleanPreferencesKey(key)] = entry.getBoolean("value")
                "long" -> prefs[longPreferencesKey(key)] = entry.getLong("value")
                "float" -> prefs[floatPreferencesKey(key)] = entry.getDouble("value").toFloat()
                "double" -> prefs[doublePreferencesKey(key)] = entry.getDouble("value")
                "stringSet" -> prefs[stringSetPreferencesKey(key)] = entry.getJSONArray("value").toStringSet()
            }
        }
        return prefs
    }

    private fun JSONArray.toStringSet(): Set<String> = (0 until length()).map { getString(it) }.toSet()
}

const val KEYFLOW_IME = "com.keyflow/.ime.KeyFlowKeyboardService"

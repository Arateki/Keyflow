package com.keyflow.agenthost

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.keyflow.agent.ConfirmationMode
import com.keyflow.agent.PolicyConfigStore
import com.keyflow.agent.ToolPolicyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A configuração das ferramentas do agente (AUI-03) no DataStore, num arquivo PRÓPRIO (`keyflow_agent`): a chave geral, o modo de
 * confirmação, as ferramentas desligadas e as liberadas com "permitir sempre". **Nenhum segredo**: só nomes de ferramenta e dois enums, e o
 * DataStore é um arquivo em claro. Fora do `keyflow_settings` de propósito — o auto-save da tela regrava as Settings inteiras e não
 * pode disputar com um "permitir sempre" dado no meio de uma conversa.
 *
 * **Padrões** (a spec, A2): chave geral LIGADA, modo `ASK_FOR_CHANGES`, nenhuma ferramenta desligada, nenhuma em "permitir sempre".
 *
 * A [ToolPolicy][com.keyflow.agent.ToolPolicy] lê [config] de forma síncrona a cada decisão, então a leitura do disco acontece UMA vez, no
 * começo, e [update] muda o valor em memória na hora e grava em seguida (serializado, sempre o último valor). **Até a leitura terminar
 * o valor é o mais RESTRITO** (ferramentas desligadas, perguntar tudo): falha fechado; quem usa a política espera [awaitLoaded].
 */
internal class DataStorePolicyConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : PolicyConfigStore {

    private val state = MutableStateFlow(FAIL_CLOSED)
    private val ready = MutableStateFlow(false)
    private val writer = Mutex()

    override val config: StateFlow<ToolPolicyConfig> = state.asStateFlow()
    override val loaded: StateFlow<Boolean> = ready.asStateFlow()

    init {
        scope.launch {
            // Um arquivo que não abre (corrompido, disco) NÃO volta aos padrões: eles têm a chave geral ligada, e quem a desligou ficaria com
            // as ferramentas de volta sem saber. Fica tudo desligado (visível nos Ajustes, que o usuário religa) — falha fechado.
            val stored = runCatching { decode(dataStore.data.first()) }.getOrDefault(READ_FAILED)
            state.value = stored
            ready.value = true
        }
    }

    override fun update(transform: (ToolPolicyConfig) -> ToolPolicyConfig) {
        if (ready.value) {
            // O valor novo vale NA HORA (a política lê de forma síncrona e não espera o disco); a gravação vem em seguida.
            state.update(transform)
            persist()
        } else {
            // Antes da leitura, "mudar" seria mudar o valor restrito de mentira: espera a leitura e aplica por cima do que estava gravado.
            scope.launch {
                awaitLoaded()
                state.update(transform)
                persist()
            }
        }
    }

    /** Grava o valor de AGORA, uma gravação por vez (várias mudanças seguidas acabam todas no último valor). */
    private fun persist() {
        scope.launch { writer.withLock { runCatching { dataStore.edit { encode(it, state.value) } } } }
    }

    override suspend fun awaitLoaded() {
        ready.first { it }
    }

    companion object {
        /** O que vale até a configuração gravada ser lida: nada de ferramenta, e tudo pergunta. */
        val FAIL_CLOSED = ToolPolicyConfig(toolsEnabled = false, mode = ConfirmationMode.ASK_FOR_ALL)

        /** O que vale quando a configuração gravada não pôde ser lida: ferramentas desligadas (o resto no padrão, para religar ser um toque). */
        val READ_FAILED = ToolPolicyConfig(toolsEnabled = false)

        private val TOOLS_ENABLED = booleanPreferencesKey("agent_tools_enabled")
        private val CONFIRM_MODE = stringPreferencesKey("agent_confirm_mode")
        private val DISABLED_TOOLS = stringSetPreferencesKey("agent_disabled_tools")
        private val ALWAYS_ALLOWED = stringSetPreferencesKey("agent_always_allowed")

        internal fun decode(prefs: Preferences) = ToolPolicyConfig(
            toolsEnabled = prefs[TOOLS_ENABLED] ?: true,
            mode = prefs[CONFIRM_MODE]?.let { name -> runCatching { ConfirmationMode.valueOf(name) }.getOrNull() } ?: ConfirmationMode.ASK_FOR_CHANGES,
            disabledTools = prefs[DISABLED_TOOLS].orEmpty(),
            alwaysAllowed = prefs[ALWAYS_ALLOWED].orEmpty(),
        )

        internal fun encode(prefs: androidx.datastore.preferences.core.MutablePreferences, config: ToolPolicyConfig) {
            prefs[TOOLS_ENABLED] = config.toolsEnabled
            prefs[CONFIRM_MODE] = config.mode.name
            prefs[DISABLED_TOOLS] = config.disabledTools
            prefs[ALWAYS_ALLOWED] = config.alwaysAllowed
        }
    }
}

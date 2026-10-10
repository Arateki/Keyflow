package com.keyflow.agenthost

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.ConfirmationMode
import com.keyflow.agent.ToolPolicyConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A configuração das ferramentas do agente no DataStore próprio (AUI-03): padrões da spec, persistência e falha fechado. */
class DataStorePolicyConfigStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "keyflow_agent.preferences_pb")

    /** Abre o store, roda [block] e fecha (dois DataStores vivos no mesmo arquivo são proibidos). */
    private fun <T> withStore(block: suspend (DataStorePolicyConfigStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T =
        runBlocking {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = ::file)
            try {
                withTimeout(10_000) { block(DataStorePolicyConfigStore(dataStore, scope), dataStore) }
            } finally {
                // Espera o DataStore fechar de verdade: abrir outro no mesmo arquivo antes disso é proibido (e a leitura falharia).
                job.cancelAndJoin()
            }
        }

    @Test
    fun `an empty file means the documented defaults`() {
        assertThat(DataStorePolicyConfigStore.decode(emptyPreferences())).isEqualTo(ToolPolicyConfig())
        val defaults = ToolPolicyConfig()
        assertThat(defaults.toolsEnabled).isTrue()
        assertThat(defaults.mode).isEqualTo(ConfirmationMode.ASK_FOR_CHANGES)
        assertThat(defaults.alwaysAllowed).isEmpty()
    }

    @Test
    fun `before the stored config is read the store is the most restrictive`() {
        val closed = DataStorePolicyConfigStore.FAIL_CLOSED
        assertThat(closed.toolsEnabled).isFalse()
        assertThat(closed.mode).isEqualTo(ConfirmationMode.ASK_FOR_ALL)
    }

    @Test
    fun `an update is visible at once and survives reopening`() {
        withStore { store, dataStore ->
            store.awaitLoaded()
            store.update { it.copy(mode = ConfirmationMode.ASK_FOR_ALL, disabledTools = setOf("skin_remove"), alwaysAllowed = setOf("skin_select")) }
            assertThat(store.config.value.mode).isEqualTo(ConfirmationMode.ASK_FOR_ALL)
            // A gravação é assíncrona: espera ela chegar ao arquivo antes de fechar.
            while (DataStorePolicyConfigStore.decode(dataStore.data.first()).disabledTools.isEmpty()) delay(20)
        }
        withStore { store, _ ->
            store.awaitLoaded()
            assertThat(store.config.value).isEqualTo(
                ToolPolicyConfig(mode = ConfirmationMode.ASK_FOR_ALL, disabledTools = setOf("skin_remove"), alwaysAllowed = setOf("skin_select")),
            )
        }
    }

    @Test
    fun `a config that cannot be read leaves the tools off instead of back on`() {
        // Quem desligou as ferramentas não pode tê-las de volta porque o arquivo estragou: falha fechado.
        assertThat(DataStorePolicyConfigStore.READ_FAILED.toolsEnabled).isFalse()
        file().writeBytes(byteArrayOf(0x7f, 0x13, 0x00, 0x55, 0x01, 0x02))
        withStore { store, _ ->
            store.awaitLoaded()
            assertThat(store.config.value.toolsEnabled).isFalse()
        }
    }

    @Test
    fun `an unknown confirmation mode on disk falls back to the default, never to looser`() {
        withStore { _, dataStore ->
            dataStore.edit { it[stringPreferencesKey("agent_confirm_mode")] = "NEVER_ASK" }
        }
        withStore { store, _ ->
            store.awaitLoaded()
            assertThat(store.config.value.mode).isEqualTo(ConfirmationMode.ASK_FOR_CHANGES)
        }
    }
}

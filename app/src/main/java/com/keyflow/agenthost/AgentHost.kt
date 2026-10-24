package com.keyflow.agenthost

import android.content.Context
import com.keyflow.App
import com.keyflow.agent.ActionLog
import com.keyflow.agent.AgentController
import com.keyflow.agent.AgentTool
import com.keyflow.agent.ConfirmationGate
import com.keyflow.agent.FileActionLog
import com.keyflow.agent.PolicyConfigStore
import com.keyflow.agent.SecretFilter
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolPolicy
import com.keyflow.agent.ToolRegistry
import com.keyflow.agent.tools.AppTools
import com.keyflow.agent.tools.SkinTools
import com.keyflow.auth.CredentialVaultLock
import com.keyflow.data.SettingsRepository
import com.keyflow.skins.SkinPackStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** As peças do agente que vivem no processo (o que o [AgentRuntime] entrega à tela, mais o executor). */
class AgentParts internal constructor(
    val controller: AgentController,
    val registry: ToolRegistry,
    val policy: PolicyConfigStore,
    val actionLog: ActionLog,
    val executor: ToolExecutor,
    val gate: ConfirmationGate,
    val secretFilter: SecretFilter,
) {
    fun runtime(backends: AgentBackends) = AgentRuntime(controller, registry, policy, actionLog, backends)
}

/**
 * Monta o agente do app: o catálogo de ferramentas, a política, o filtro de segredos ligado aos cofres, o painel de confirmação e o
 * controlador do chat — UM conjunto por processo (a conversa e o painel não podem se duplicar quando a Activity é recriada), como o
 * grafo de autenticação do [App].
 *
 * Tudo preguiçoso: nada é criado até a aba KeyFlow pedir.
 */
object AgentHost {
    @Volatile
    private var parts: AgentParts? = null

    /** Quem abre as abas do app a pedido de `open_tab`: a `SetupActivity` se anexa enquanto está na tela. */
    val tabNavigator = AgentTabNavigator()

    /** As peças do processo (criadas na primeira chamada). */
    fun of(context: Context): AgentParts = parts ?: synchronized(this) { parts ?: create(context.applicationContext).also { parts = it } }

    /**
     * Monta o conjunto a partir das peças dadas. Os instrumentados e a galeria montam o seu com cofres, ferramentas e motor de teste;
     * o app usa [of]. A política lê [policy] a cada decisão; o [secrets] alimenta o [SecretFilter].
     */
    fun assemble(
        scope: CoroutineScope,
        tools: List<AgentTool>,
        policy: PolicyConfigStore,
        actionLog: ActionLog,
        secrets: () -> Collection<String>,
        clock: () -> Long = System::currentTimeMillis,
    ): AgentParts {
        val filter = SecretFilter(secrets)
        val gate = ConfirmationGate()
        val registry = ToolRegistry(tools)
        val toolPolicy = ToolPolicy(
            config = { policy.config.value },
            // "Permitir sempre": a política avisa, a configuração persiste (o valor novo já vale na hora; o disco termina depois).
            onAlwaysAllow = { name -> policy.update { it.copy(alwaysAllowed = it.alwaysAllowed + name) } },
        )
        val executor = ToolExecutor(registry, toolPolicy, gate, filter, actionLog, clock)
        val controller = AgentController(scope, executor, gate, actionLog, awaitReady = policy::awaitLoaded)
        return AgentParts(controller, registry, policy, actionLog, executor, gate, filter)
    }

    private fun create(app: Context): AgentParts {
        val repository = SettingsRepository(app)
        val auth = (app as App).authServices
        val secrets = AgentSecretSource(
            vaultProvider = repository::secretVault,
            credentials = auth.store,
            // A fonte antiga pode ser migrada por outro processo enquanto o filtro roda; a leitura do snapshot espera só a cópia síncrona.
            vaultSnapshot = { vault -> CredentialVaultLock.withLock(app) { vault.all.values.toList() } },
        )
        // O DataStore da configuração e o arquivo do registro: do agente, fora das Settings, e nenhum guarda segredo (o registro ainda passa pelo filtro).
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val policy = DataStorePolicyConfigStore(
            PreferenceDataStoreFactory.create(scope = io, produceFile = { app.preferencesDataStoreFile(POLICY_DATASTORE) }),
            io,
        )
        val log = FileActionLog(File(app.filesDir, ACTION_LOG_FILE), sanitize = SecretFilter(secrets)::redact)
        return assemble(
            // Fora da thread principal: o filtro decifra o cofre, o executor roda ferramentas e o motor fala com a rede; a tela só observa o StateFlow.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // As ferramentas de skin (SKL-01..06) sobre o mesmo SkinPackStore do import e da tela, e as duas chaves de skin dos ajustes;
            // e as de app (APP-01..03): o estado, a lista de permitidos de ajustes e a navegação.
            tools = SkinTools(AndroidSkinWorkshop(app, SkinPackStore.of(app), RepositorySkinSelection(repository))).tools +
                AppTools(AndroidAppControl(app, repository, tabNavigator)).tools,
            policy = policy,
            actionLog = log,
            secrets = secrets,
        )
    }

    /** O arquivo do DataStore da configuração das ferramentas (`datastore/keyflow_agent.preferences_pb`). */
    const val POLICY_DATASTORE = "keyflow_agent"

    /** O registro de ações, em `files/`: fora do backup (as chamadas de desfazer apontam para coisas deste aparelho). */
    const val ACTION_LOG_FILE = "agent/actions.jsonl"
}

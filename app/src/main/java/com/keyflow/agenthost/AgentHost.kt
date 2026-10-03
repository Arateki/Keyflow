package com.keyflow.agenthost

import android.content.Context
import com.keyflow.App
import com.keyflow.agent.ActionLog
import com.keyflow.agent.AgentController
import com.keyflow.agent.AgentTool
import com.keyflow.agent.ConfirmationGate
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.PolicyConfigStore
import com.keyflow.agent.SecretFilter
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolPolicy
import com.keyflow.agent.ToolRegistry
import com.keyflow.agent.tools.SkinTools
import com.keyflow.data.SettingsRepository
import com.keyflow.skins.SkinPackStore
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
        val secrets = AgentSecretSource(vaultProvider = repository::secretVault, credentials = auth.store)
        return assemble(
            // Fora da thread principal: o filtro decifra o cofre, o executor roda ferramentas e o motor fala com a rede; a tela só observa o StateFlow.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // As ferramentas de skin (SKL-01..06) sobre o mesmo SkinPackStore do import e da tela, e as duas chaves de skin dos ajustes.
            tools = SkinTools(AndroidSkinWorkshop(app, SkinPackStore.of(app), RepositorySkinSelection(repository))).tools,
            policy = InMemoryPolicyConfigStore(),
            actionLog = InMemoryActionLog(),
            secrets = secrets,
        )
    }
}

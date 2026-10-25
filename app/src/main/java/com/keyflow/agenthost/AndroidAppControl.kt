package com.keyflow.agenthost

import android.content.Context
import com.keyflow.BuildConfig
import com.keyflow.agent.tools.AppControl
import com.keyflow.agent.tools.AppSettingsAllowlist
import com.keyflow.agent.tools.AppStatus
import com.keyflow.agent.tools.AppTab
import com.keyflow.agent.tools.SettingValue
import com.keyflow.data.AgentSettingKeys
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.PermissionsChecker
import com.keyflow.ui.config.ConfigTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A ponte entre as ferramentas de app e a Activity que mostra as abas. A `SetupActivity` se anexa enquanto existe ([attach]) e se solta
 * ao morrer ([detach]); sem ela ([open] devolve `false`) o pedido de abrir uma aba não tem para quem ir. Um por processo (no [AgentHost]).
 */
class AgentTabNavigator {
    @Volatile
    private var handler: ((ConfigTab) -> Unit)? = null

    fun attach(handler: (ConfigTab) -> Unit) {
        this.handler = handler
    }

    /** Solta só se [handler] ainda é o atual (uma Activity nova pode já ter se anexado antes de a velha morrer). */
    fun detach(handler: (ConfigTab) -> Unit) {
        if (this.handler === handler) this.handler = null
    }

    /** O handler decide a thread: a `SetupActivity` posta na principal. */
    fun open(tab: ConfigTab): Boolean {
        val current = handler ?: return false
        current(tab)
        return true
    }
}

/**
 * O adaptador do aparelho das ferramentas de app ([AppControl]): o estado (versão, modos, permissões, skins, origem de raciocínio), os
 * ajustes da lista de permitidos e a navegação.
 *
 * **Nenhum segredo passa por aqui**: os ajustes vêm do DataStore pela tabela [AgentSettingKeys] (que só tem chaves não secretas) e o
 * estado, de [SettingsRepository.agentStatusOnce] (que nunca abre o cofre). Uma chave fora da lista de permitidos é recusada AQUI também, não só
 * no esquema da ferramenta.
 */
internal class AndroidAppControl(
    private val context: Context,
    private val repository: SettingsRepository,
    private val navigator: AgentTabNavigator,
    private val permissions: () -> PermissionStatus = { PermissionsChecker().snapshot(context) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AppControl {

    override val tabs: List<AppTab> get() = ConfigTab.entries.map { AppTab(it.name.lowercase(), it.label) }

    override suspend fun status(): AppStatus = withContext(io) {
        val settings = repository.agentStatusOnce()
        val granted = permissions()
        AppStatus(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            overlayModeOn = settings.overlayModeEnabled,
            keyboardModeOn = settings.keyboardModeEnabled,
            microphoneGranted = granted.microphoneGranted,
            overlayGranted = granted.overlayGranted,
            keyboardEnabled = granted.keyboardEnabled,
            keyboardActive = granted.keyboardActive,
            notificationsGranted = granted.notificationsGranted,
            keyboardSkin = settings.keyboardSkin,
            orbSkin = settings.orbSkin,
            reasoningSource = settings.reasoningSource.name,
            reasoningModel = settings.reasoningModel,
        )
    }

    override suspend fun readSettings(keys: Collection<String>): Map<String, SettingValue> = withContext(io) {
        val allowed = keys.filter { AppSettingsAllowlist[it] != null }
        repository.agentSettingsOnce(allowed).mapNotNull { (key, raw) -> AppSettingsAllowlist[key]?.coerce(raw)?.let { key to it } }.toMap()
    }

    override suspend fun writeSettings(changes: Map<String, SettingValue>) = withContext(io) {
        // Só o que a lista deixa gravar, e só o tipo certo: o repositório confere de novo contra a tabela de chaves.
        val raw = changes.mapValues { (key, value) ->
            val setting = AppSettingsAllowlist[key]?.takeIf { it.writable && key in AgentSettingKeys.writable } ?: throw IllegalArgumentException("ajuste fora da lista do agente: $key")
            setting.json(value)
        }
        repository.updateAgentSettings(raw)
    }

    override fun openTab(id: String): Boolean {
        val tab = ConfigTab.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: return false
        return navigator.open(tab)
    }
}

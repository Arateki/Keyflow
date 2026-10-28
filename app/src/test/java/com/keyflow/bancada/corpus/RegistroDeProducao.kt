package com.keyflow.bancada.corpus

import com.keyflow.agent.ToolRegistry
import com.keyflow.agent.tools.AppControl
import com.keyflow.agent.tools.AppSettingsAllowlist
import com.keyflow.agent.tools.AppStatus
import com.keyflow.agent.tools.AppTab
import com.keyflow.agent.tools.AppTools
import com.keyflow.agent.tools.SettingValue
import com.keyflow.agent.tools.SkinCatalog
import com.keyflow.agent.tools.SkinCheck
import com.keyflow.agent.tools.SkinFileKind
import com.keyflow.agent.tools.SkinInstallResult
import com.keyflow.agent.tools.SkinRemoval
import com.keyflow.agent.tools.SkinRemovalInfo
import com.keyflow.agent.tools.SkinSelectionPlan
import com.keyflow.agent.tools.SkinTools
import com.keyflow.agent.tools.SkinWorkshop
import com.keyflow.ui.config.ConfigTab

/**
 * O `ToolRegistry` do agente como o app o monta (`AgentHost.create`): as 6 ferramentas de skin e as 4 de app, com as MESMAS descrições e
 * esquemas. O que depende do aparelho (disco, DataStore) é falso — construir as ferramentas não chama nada disso — e as abas de `open_tab`
 * saem do `ConfigTab` real, como no `AndroidAppControl`. Serve ao BCP-05: o corpus de `acoes` é conferido contra isto.
 */
object RegistroDeProducao {
    fun criar(): ToolRegistry = ToolRegistry(SkinTools(OficinaInerte).tools + AppTools(ControleInerte).tools)

    private object OficinaInerte : SkinWorkshop {
        override suspend fun formatDoc(): String = ""
        override suspend fun catalog() = SkinCatalog(emptyList(), "default", "default")
        override fun classify(fileName: String) = SkinFileKind.REJECTED
        override suspend fun check(files: Map<String, ByteArray>): SkinCheck = error("inerte")
        override suspend fun install(files: Map<String, ByteArray>): SkinInstallResult = error("inerte")
        override suspend fun select(keyboard: String?, orb: String?, apply: Boolean): SkinSelectionPlan = error("inerte")
        override suspend fun removalInfo(id: String): SkinRemovalInfo? = null
        override suspend fun remove(id: String): SkinRemoval = error("inerte")
    }

    private object ControleInerte : AppControl {
        override val tabs: List<AppTab> get() = ConfigTab.entries.map { AppTab(it.name.lowercase(), it.label) }
        override suspend fun status(): AppStatus = error("inerte")
        override suspend fun readSettings(keys: Collection<String>): Map<String, SettingValue> = emptyMap()
        override suspend fun writeSettings(changes: Map<String, SettingValue>) = Unit
        override fun openTab(id: String): Boolean = false
    }

    /** As chaves de ajuste que `settings_get`/`settings_set` aceitam hoje. */
    val chavesDeAjuste: List<String> get() = AppSettingsAllowlist.all.map { it.key }
}

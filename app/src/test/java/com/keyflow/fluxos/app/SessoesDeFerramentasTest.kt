package com.keyflow.fluxos.app

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ChatItem
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.ToolCardPhase
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.call
import com.keyflow.agent.done
import com.keyflow.agent.tools.AppControl
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
import com.keyflow.agent.tools.SkinReport
import com.keyflow.agent.tools.SkinSelectionPlan
import com.keyflow.agent.tools.SkinTools
import com.keyflow.agent.tools.SkinWorkshop
import com.keyflow.agenthost.AgentHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

/** Chat e fluxo intercalados com as ferramentas reais; cada motor espera o outro chegar ao mesmo ponto. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessoesDeFerramentasTest {
    private class Controle : AppControl {
        override val tabs = listOf(AppTab("chat", "Chat"), AppTab("fluxo", "Fluxo"))
        val abertas = mutableListOf<String>()
        override fun openTab(id: String): Boolean { abertas += id; return true }
        override suspend fun status(): AppStatus = error("não usado")
        override suspend fun readSettings(keys: Collection<String>): Map<String, SettingValue> = error("não usado")
        override suspend fun writeSettings(changes: Map<String, SettingValue>) = error("não usado")
    }

    private class Oficina : SkinWorkshop {
        val conferidos = mutableListOf<Map<String, ByteArray>>()
        override suspend fun formatDoc() = "formato"
        override suspend fun catalog() = SkinCatalog(emptyList(), "system", "atelie")
        override fun classify(fileName: String) = SkinFileKind.ORB_FRAME
        override suspend fun check(files: Map<String, ByteArray>): SkinCheck {
            conferidos += files
            return SkinCheck(SkinReport(emptyList(), emptyList(), emptyList()), "Teste", null)
        }
        override suspend fun install(files: Map<String, ByteArray>): SkinInstallResult = error("não usado")
        override suspend fun select(keyboard: String?, orb: String?, apply: Boolean): SkinSelectionPlan = error("não usado")
        override suspend fun removalInfo(id: String): SkinRemovalInfo? = error("não usado")
        override suspend fun remove(id: String): SkinRemoval = error("não usado")
    }

    @Test
    fun `chat e fluxo preservam navegação e rascunhos ao terminar ou cancelar o outro`() = runTest {
        for (cancelar in listOf(false, true)) {
            val controle = Controle()
            val oficina = Oficina()
            val criar = { SkinTools(oficina).tools + AppTools(controle).tools }
            val chat = AgentHost.assemble(backgroundScope, criar(), InMemoryPolicyConfigStore(), InMemoryActionLog(), { emptyList() }, novasFerramentas = criar)
            val fluxo = chat.novaSessao(backgroundScope)
            assertThat(fluxo.registry.all().map { it.name }).containsExactlyElementsIn(chat.registry.all().map { it.name })
            for (nome in listOf("open_tab", "skin_preview", "skin_install")) {
                assertThat(fluxo.registry[nome]).isNotSameInstanceAs(chat.registry[nome])
            }
            val terminarChat = CompletableDeferred<Unit>()
            val terminarFluxo = CompletableDeferred<Unit>()
            val prontos = mapOf("chat" to CompletableDeferred<Unit>(), "fluxo" to CompletableDeferred<Unit>())
            fun motor(nome: String, fim: CompletableDeferred<Unit>) = object : AgentEngine {
                var volta = 0
                override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int) = flow {
                    when (volta++) {
                        0 -> {
                            emit(call("aba", "open_tab", """{"tab":"$nome"}"""))
                            emit(call("previa", "skin_preview", """{"files":{"orb_idle_01.svg":"$nome"}}"""))
                            emit(done(AgentStopReason.TOOL_USE))
                        }
                        1 -> {
                            prontos.getValue(nome).complete(Unit)
                            fim.await()
                            emit(call("final", "skin_preview", """{"files":{}}"""))
                            emit(done(AgentStopReason.TOOL_USE))
                        }
                        else -> { emit(AgentEvent.TextDelta("feito")); emit(done()) }
                    }
                }
            }
            chat.controller.send("chat", AgentBackend.WithTools(motor("chat", terminarChat)))
            prontos.getValue("chat").await()
            fluxo.controller.send("fluxo", AgentBackend.WithTools(motor("fluxo", terminarFluxo)))
            prontos.getValue("fluxo").await()
            assertThat(chat.controller.isBusy).isTrue()
            assertThat(fluxo.controller.isBusy).isTrue()
            if (cancelar) fluxo.controller.cancel() else terminarFluxo.complete(Unit)
            runCurrent()
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (fluxo.controller.isBusy) kotlinx.coroutines.delay(10)
                }
            }
            terminarChat.complete(Unit)
            runCurrent()
            // O executor usa Default: espera as coroutines do teste sem depender de tempo fixo.
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (chat.controller.isBusy) kotlinx.coroutines.delay(10)
                }
            }
            assertThat(controle.abertas).containsExactlyElementsIn(if (cancelar) listOf("chat") else listOf("fluxo", "chat")).inOrder()
            assertThat(oficina.conferidos.last().getValue("orb_idle_01.svg").decodeToString()).isEqualTo("chat")
            assertThat(chat.controller.state.value.items.filterIsInstance<ChatItem.ToolCard>().last().phase).isEqualTo(ToolCardPhase.DONE)
        }
    }
}

package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * O núcleo do agente conferido no código-fonte, como o `AuthIsolationTest`: lê como texto os .kt de `agent/` e falha se alguém
 *  - puser `android.*` no núcleo (tudo roda na JVM, D-núcleo puro);
 *  - importar a UI ou o resto do app — as ÚNICAS exceções são `com.keyflow.i18n.tr` e `trKey`: o resumo e a legenda que o USUÁRIO lê (o
 *    painel de confirmação, TLS-02) e o nome de cada ajuste da lista de permitidos têm de seguir o idioma da interface (AUI-04), e
 *    `tr`/`trKey` são só consultas de texto (JVM puro, sem Android);
 *  - abrir um atalho até `AgentTool.run`: o opt-in `PolicyGated` de CHAMADA só pode existir no [ToolExecutor] (D2, regra de ouro
 *    "a política é o único caminho").
 */
class AgentIsolationTest {

    private val root = File("src/main/java/com/keyflow")

    private fun sources(): List<Pair<String, List<String>>> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).invariantSeparatorsPath to it.readLines() }
        .toList()

    /** Linha de código (não de comentário/KDoc). */
    private fun isCode(line: String): Boolean {
        val t = line.trimStart()
        return t.isNotEmpty() && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
    }

    @Test
    fun `the source tree is being read`() {
        assertWithMessage("nenhum .kt de agent/ lido — o teste está olhando o diretório certo?").that(sources().count { it.first.startsWith("agent/") }).isAtLeast(10)
    }

    @Test
    fun `the agent core has no android dependency`() {
        val found = sources().filter { (path, _) -> path.startsWith("agent/") }
            .flatMap { (path, lines) -> lines.filter(::isCode).filter { it.contains("android.") || it.contains("androidx.") }.map { "$path → ${it.trim()}" } }
        assertWithMessage("android no núcleo do agente:\n${found.joinToString("\n")}").that(found).isEmpty()
    }

    @Test
    fun `the agent core knows neither the ui nor the screens`() {
        val found = sources().filter { (path, _) -> path.startsWith("agent/") }
            .flatMap { (path, lines) -> lines.filter { it.trimStart().startsWith("import com.keyflow.") }.filter { !it.startsWith("import com.keyflow.agent") && !it.startsWith("import com.keyflow.reasoning.ReasoningError") && it.trim() != "import com.keyflow.i18n.tr" && it.trim() != "import com.keyflow.i18n.trKey" }.map { "$path → ${it.trim()}" } }
        assertWithMessage("o núcleo importa o que não devia:\n${found.joinToString("\n")}").that(found).isEmpty()
    }

    @Test
    fun `only the executor opts in to calling AgentTool run`() {
        val callers = sources().filter { (_, lines) -> lines.filter(::isCode).any { it.contains("OptIn(PolicyGated") } }.map { it.first }
        assertWithMessage("quem chama AgentTool.run por fora do ToolExecutor: $callers").that(callers).containsExactly("agent/ToolExecutor.kt")
    }

    @Test
    fun `the opt-in marker is an error, not a warning`() {
        val tool = File(root, "agent/AgentTool.kt").readText()
        assertThat(tool).contains("@RequiresOptIn(")
        assertThat(tool).contains("level = RequiresOptIn.Level.ERROR")
        assertThat(tool).contains("@PolicyGated\n    suspend fun run(")
    }

    @Test
    fun `no one outside agent calls the tools - the only reference to the executor from the engines is the contract`() {
        // Os motores (reasoning/) conhecem AgentEngine/AgentEvent/AgentMessage, nunca ToolExecutor, ToolRegistry ou AgentTool.
        val found = sources().filter { (path, _) -> path.startsWith("reasoning/") }
            .flatMap { (path, lines) -> lines.filter { it.trimStart().startsWith("import com.keyflow.agent.") }.filter { l -> listOf("ToolExecutor", "ToolRegistry", "AgentTool", "AgentLoop", "ToolPolicy").any { l.endsWith(".$it") } }.map { "$path → ${it.trim()}" } }
        assertWithMessage(found.joinToString("\n")).that(found).isEmpty()
    }
}

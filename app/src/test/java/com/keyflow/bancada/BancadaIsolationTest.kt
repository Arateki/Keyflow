package com.keyflow.bancada

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * O núcleo da bancada conferido no código-fonte, como o `AgentIsolationTest` e o `AuthIsolationTest`: lê como texto os .kt de `bancada/` e
 * falha se alguém
 *  - puser Android, a interface, os `Settings` ou um provedor concreto no núcleo (tudo roda na JVM — só `adaptadores/` toca no aparelho);
 *  - abrir qualquer caminho que EXECUTE a saída de um modelo: `ProcessBuilder`, `Runtime.exec`, `Class.forName`, `GroovyShell`, `ScriptEngine`,
 *    `dalvik.system` (BPT-10 e "Fora de escopo": a bancada pontua texto, nunca roda código nem comando que o modelo escreveu).
 * A proibição de execução vale também para os adaptadores.
 */
class BancadaIsolationTest {

    private val root = File("src/main/java/com/keyflow")
    private val bancada = File(root, "bancada")

    private fun fontes(): List<Pair<String, List<String>>> = bancada.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).invariantSeparatorsPath to it.readLines() }
        .toList()

    private fun ehCodigo(linha: String): Boolean {
        val t = linha.trimStart()
        return t.isNotEmpty() && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
    }

    private fun nucleo() = fontes().filter { !it.first.startsWith("bancada/adaptadores/") }

    @Test
    fun `the source tree is being read`() {
        assertWithMessage("nenhum .kt de bancada/ lido — o teste está olhando o diretório certo?").that(fontes().size).isAtLeast(15)
    }

    @Test
    fun `the core has no android, ui, settings or concrete provider`() {
        val proibidos = listOf(
            "import android.", "import androidx.", "import com.keyflow.ui.", "import com.keyflow.data.", "import com.keyflow.App",
            "import com.keyflow.reasoning.openai", "import com.keyflow.reasoning.anthropic", "import com.keyflow.reasoning.google",
            "import com.keyflow.reasoning.local", "import com.keyflow.auth.", "import com.keyflow.transcription.", "import com.keyflow.onnx.",
            "import com.keyflow.agent.", "import com.keyflow.agenthost.",
        )
        val achados = nucleo().flatMap { (caminho, linhas) ->
            linhas.filter { l -> l.trimStart().startsWith("import ") && proibidos.any { l.trimStart().startsWith(it) } }.map { "$caminho → ${it.trim()}" }
        }
        assertWithMessage("o núcleo da bancada importa o que não devia:\n${achados.joinToString("\n")}").that(achados).isEmpty()
    }

    @Test
    fun `the core only knows the reasoning contract from the rest of the app`() {
        val permitidos = setOf(
            "com.keyflow.reasoning.ReasoningEngine", "com.keyflow.reasoning.ReasoningEvent", "com.keyflow.reasoning.ReasoningRequest",
            "com.keyflow.reasoning.ReasoningError", "com.keyflow.reasoning.GenerationOptions",
        )
        val achados = nucleo().flatMap { (caminho, linhas) ->
            linhas.map { it.trim() }
                .filter { it.startsWith("import com.keyflow.") && !it.startsWith("import com.keyflow.bancada.") && it.removePrefix("import ") !in permitidos }
                .map { "$caminho → $it" }
        }
        assertWithMessage("importações fora do contrato:\n${achados.joinToString("\n")}").that(achados).isEmpty()
    }

    /**
     * O release não leva o agente (V1: "Em breve"; `scripts/check-release-agent.py` recusa `com.keyflow.agent.*` e `agenthost.*` no DEX) e a bancada roda no release:
     * nada em `bancada/` — núcleo ou adaptador — pode importá-lo, senão o R8 o mantém. O filtro de segredos tem cópia própria (`redacao/`), com teste de paridade.
     */
    @Test
    fun `no file of bancada imports the agent, which does not ship in the release`() {
        val achados = fontes().flatMap { (caminho, linhas) ->
            linhas.map { it.trim() }.filter { it.startsWith("import com.keyflow.agent.") || it.startsWith("import com.keyflow.agenthost.") }.map { "$caminho → $it" }
        }
        assertWithMessage("a bancada importa o agente:\n${achados.joinToString("\n")}").that(achados).isEmpty()
        val ui = File(root, "ui/config").listFiles { f -> f.name.startsWith("Bancada") && f.extension == "kt" }.orEmpty()
        val daUi = ui.flatMap { f -> f.readLines().map { it.trim() }.filter { it.startsWith("import com.keyflow.agent") }.map { "${f.name} → $it" } }
        assertWithMessage("a interface da bancada importa o agente:\n${daUi.joinToString("\n")}").that(daUi).isEmpty()
        assertThat(ui).isNotEmpty()
    }

    @Test
    fun `nothing in bancada can run what a model wrote`() {
        val perigosos = listOf("ProcessBuilder", "Runtime.getRuntime", ".exec(", "Class.forName", "GroovyShell", "ScriptEngine", "dalvik.system", "DexClassLoader", "GroovyClassLoader")
        val achados = fontes().flatMap { (caminho, linhas) ->
            linhas.filter(::ehCodigo).filter { l -> perigosos.any { it in l } }.map { "$caminho → ${it.trim()}" }
        }
        assertWithMessage("a bancada abriu um caminho que executa código:\n${achados.joinToString("\n")}").that(achados).isEmpty()
        assertThat(perigosos).isNotEmpty()
    }

    @Test
    fun `the scorers are pure - no clock, no randomness, no files`() {
        val impuros = listOf("System.currentTimeMillis", "System.nanoTime", "Random", "java.io.File", "java.net.", "okhttp3")
        val achados = fontes().filter { it.first.startsWith("bancada/pontuacao/") }.flatMap { (caminho, linhas) ->
            linhas.filter(::ehCodigo).filter { l -> impuros.any { it in l } }.map { "$caminho → ${it.trim()}" }
        }
        assertWithMessage("pontuador com efeito colateral:\n${achados.joinToString("\n")}").that(achados).isEmpty()
    }
}

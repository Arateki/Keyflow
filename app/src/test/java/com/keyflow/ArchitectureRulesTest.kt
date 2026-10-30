package com.keyflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Convenções do projeto que já custaram horas de CI para achar, viradas regra executável: quem mexer no código (pessoa ou
 * agente) e esquecer uma delas vê o teste falhar com o motivo, em vez de descobrir semanas depois numa falha "às vezes".
 *
 * Lê o código-fonte como texto (sem dependência nova): o teste de unidade roda com o diretório do módulo `app/` como
 * diretório de trabalho. Cada exceção a uma regra fica numa lista aqui, com o porquê.
 */
class ArchitectureRulesTest {

    private val main = File("src/main/java")
    private val androidTest = File("src/androidTest/java")

    private fun kotlinFiles(root: File): List<File> {
        assertTrue("rode a partir do módulo app/ (não achei ${root.absolutePath})", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun File.rel(root: File) = relativeTo(root).path

    /**
     * O CÓDIGO de cada linha de [file] (mesma numeração, 0-based na lista): comentários `//` e `/* … */` (KDoc inclusive, que
     * atravessa linhas) ficam de fora — a documentação pode citar o que a regra proíbe.
     */
    private fun codeLines(file: File): List<String> {
        var inBlock = false
        return file.readLines().map { line ->
            val code = StringBuilder()
            var j = 0
            while (j < line.length) {
                when {
                    inBlock && line.startsWith("*/", j) -> { inBlock = false; j += 2 }
                    inBlock -> j++
                    line.startsWith("/*", j) -> { inBlock = true; j += 2 }
                    line.startsWith("//", j) -> j = line.length
                    else -> { code.append(line[j]); j++ }
                }
            }
            code.toString()
        }
    }

    /** Linhas de [file] (1-based) cujo CÓDIGO (sem comentários, ver [codeLines]) casa [predicate]. */
    private fun matches(file: File, predicate: (String) -> Boolean): List<Int> =
        codeLines(file).mapIndexedNotNull { i, code -> if (predicate(code)) i + 1 else null }

    /**
     * Tela coleta fluxo por `collectAsStateOnMain` (ui/common/CollectOnMain.kt), nunca `collectAsState` direto: no
     * `createComposeRule` a composição coleta num dispatcher "unconfined" e um valor vindo de outra thread recompunha ali
     * (CalledFromWrongThreadException, SlotTable corrompida — CI de 2026-10-07..10).
     */
    @Test
    fun screensCollectFlowsOnTheMainThread() {
        val offenders = kotlinFiles(main).filter { it.name != "CollectOnMain.kt" }.flatMap { file ->
            matches(file) { ".collectAsState(" in it }.map { "${file.rel(main)}:$it" }
        }
        assertEquals("use collectAsStateOnMain (ui/common/CollectOnMain.kt) no lugar de collectAsState", emptyList<String>(), offenders)
    }

    /**
     * `runBlocking` no app trava a thread que chama até a outra terminar — no caminho do teclado, isso empurrava o 1º quadro
     * para além do prazo de 1 s do Android (esteira de instabilidade, 2026-10-10). Cada uso que existe está aqui, com o
     * motivo; um novo precisa entrar na lista de propósito.
     */
    @Test
    fun runBlockingOnlyWhereItWasDecided() {
        val allowed = mapOf(
            // Uma leitura do DataStore ao montar o teclado (keyboardSnapshotOnce): fresca, para uma config recém-gravada valer já.
            "com/keyflow/ime/KeyFlowKeyboardService.kt" to 1,
            // Ajustes no onCreate do serviço da orbe, antes de qualquer gesto ser tratado.
            "com/keyflow/overlay/OverlayService.kt" to 1,
            // Seleção do modelo recém-instalado, na thread do download (não na principal).
            "com/keyflow/data/models/ModelDownloader.kt" to 1,
        )
        val found = kotlinFiles(main).associate { file ->
            file.rel(main) to matches(file) { "runBlocking" in it && !it.trimStart().startsWith("import ") }.size
        }.filterValues { it > 0 }
        val unexpected = found.filter { (path, count) -> count > (allowed[path] ?: 0) }
        assertEquals("runBlocking novo (ou a mais) — decida e anote na lista deste teste", emptyMap<String, Int>(), unexpected)
    }

    /**
     * O OverlayService sobe e desce só por `OverlayService.start`/`stop`: `stopService` antes de o serviço chegar ao
     * `startForeground` derrubava o processo inteiro (ForegroundServiceDidNotStartInTimeException, CI de 2026-10-09).
     */
    @Test
    fun overlayServiceStartsAndStopsOnlyThroughItsCompanion() {
        val raw = Regex("""(startForegroundService|stopService)\([^)]*OverlayService""")
        val offenders = (kotlinFiles(main) + kotlinFiles(androidTest))
            .filter { !it.path.endsWith("overlay/OverlayService.kt") }
            .flatMap { file -> matches(file) { raw.containsMatchIn(it) }.map { "${file.path}:$it" } }
        assertEquals("use OverlayService.start(context, intent) / OverlayService.stop(context)", emptyList<String>(), offenders)
    }

    /**
     * Teste instrumentado que inicia uma gravação de verdade para o OverlayService no `@After`: a gravação continuava por
     * baixo e o teste seguinte achava "● 2:20" no lugar do "🎙" (esteira de instabilidade, 134 de 134 voltas).
     */
    @Test
    fun testsThatStartARecordingStopTheService() {
        val startsRecording = Regex("""keyboardMicIntent|ACTION_RECORD\b|ACTION_KEYBOARD_MIC|By\.(desc|text)\("🎙"\)\)\s*(\?\.click|\?:)""")
        val offenders = kotlinFiles(androidTest)
            .filter { file -> startsRecording.containsMatchIn(file.readText()) }
            .filter { file -> "OverlayService.stop(" !in file.readText() }
            .map { it.rel(androidTest) }
        assertEquals("teste que grava de verdade precisa de OverlayService.stop(context) no @After", emptyList<String>(), offenders)
    }

    /**
     * Teste de teclado não espera às cegas pelo "🎙" (o mic é desenhado pela BarView e esse texto nunca aparece): quem
     * espera o teclado assentar é o awaitKeyboardAttached / awaitImeWindowSettled.
     */
    @Test
    fun keyboardTestsDoNotWaitBlindlyForTheMicText() {
        val offenders = kotlinFiles(androidTest).flatMap { file ->
            matches(file) { "Until.hasObject(By.text(\"🎙\"))" in it }.map { "${file.rel(androidTest)}:$it" }
        }
        assertEquals("espere com awaitKeyboardAttached/awaitImeWindowSettled, não pelo \"🎙\"", emptyList<String>(), offenders)
    }

    /**
     * Teste instrumentado que espera um nó com o relógio de composição congelado usa `pumpUntil`/`pumpUntilTag`
     * (FrozenClockPump.kt), que desiste só por prazo em tempo REAL. Laço de "N quadros" (`frames++ < 100`) acaba em
     * milissegundos reais e não espera o DataStore da SetupActivity: no emulador do CI ocupado a divulgação chegava
     * depois do 100º quadro e `OnboardingRoutingTest` caía no `assertExists` (PR #17, 2026-10-24, duas vezes; medido
     * com carga: a tela levou de 3 a 7 s e 135 e 239 quadros).
     */
    @Test
    fun frozenClockWaitsGoThroughPumpUntil() {
        val offenders = kotlinFiles(androidTest).filter { it.name != "FrozenClockPump.kt" }.flatMap { file ->
            matches(file) { Regex("""\bwhile\s*\(.*fetchSemanticsNodes""").containsMatchIn(it) }.map { "${file.rel(androidTest)}:$it" }
        }
        assertEquals("espere o nó com compose.pumpUntilTag(tag) / compose.pumpUntil(...), não com um laço de quadros", emptyList<String>(), offenders)
    }

    /**
     * Regex de teste instrumentado roda no ICU do Android, que recusa um `}` solto (PatternSyntaxException derrubou o
     * diagnóstico do teclado em 2026-10-10). Quantificador `{n,m}` pode; `}` sem `{` aberto, não.
     */
    @Test
    fun instrumentedRegexesHaveNoLoneClosingBrace() {
        val quoted = Regex("""Regex\("((?:[^"\\]|\\.)*)"\)""")
        val raw = Regex("""Regex\("{3}(.*?)"{3}\)""")
        val offenders = kotlinFiles(androidTest).flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val patterns = quoted.findAll(line).map { unescapeKotlin(it.groupValues[1]) } +
                    raw.findAll(line).map { it.groupValues[1] }
                if (patterns.any(::hasLoneClosingBrace)) "${file.rel(androidTest)}:${i + 1}" else null
            }
        }
        assertEquals("escape o } (\\\\}) ou troque a Regex por contains()", emptyList<String>(), offenders)
    }

    /**
     * `com.keyflow.fluxos` (modelo, validação, codec, restaurar, roteamento) e `fluxos/execucao` são JVM puro, para testar na
     * JVM e rodar o executor com portas falsas (FLX-52): nada de `android.*` nem `androidx.*` (Compose, DataStore…), do downloader de modelos nem do cofre. Só
     * `fluxos/app/` enxerga os dois lados. [fluxosViolations] é a regra; o primeiro teste a aplica ao fonte e o segundo prova que
     * ela pega o que deve e deixa passar `fluxos/app/`.
     */
    private val fluxosForbidden = listOf(
        Regex("""^\s*import\s+android\."""),
        Regex("""^\s*import\s+androidx\."""), // Compose, DataStore, lifecycle, core-ktx… tudo o que é Android
        Regex("""^\s*import\s+com\.keyflow\.data\.models\.(ModelDownloader|DownloadGateway|ModelDownloadService)\b"""),
        Regex("""^\s*import\s+com\.keyflow\.auth\."""),
    )

    /** Linhas (1-based) de [code] que a regra de `fluxos/` proíbe, para o arquivo em [relPath] (relativo a `src/main/java`). */
    private fun fluxosViolations(relPath: String, code: List<String>): List<Int> {
        val norm = relPath.replace('\\', '/')
        val inScope = norm.startsWith("com/keyflow/fluxos/") && !norm.startsWith("com/keyflow/fluxos/app/")
        if (!inScope) return emptyList()
        return code.mapIndexedNotNull { i, line -> if (fluxosForbidden.any { it.containsMatchIn(line) }) i + 1 else null }
    }

    @Test
    fun fluxosPackageIsPureJvm() {
        val offenders = kotlinFiles(main).flatMap { file ->
            fluxosViolations(file.rel(main), codeLines(file)).map { "${file.rel(main)}:$it" }
        }
        assertEquals("com.keyflow.fluxos (fora de fluxos/app) é JVM puro: sem android.*, Compose, downloader nem cofre", emptyList<String>(), offenders)
    }

    /**
     * Uma etapa de fluxo nunca baixa um modelo (FLX-26, FLX-52): modelo ausente é falha `INDISPONIVEL` com a frase da aba. Mesmo em
     * `fluxos/app`, que enxerga o Android, nada importa o downloader nem o serviço de download.
     */
    @Test
    fun fluxosAppNeverDownloadsAModel() {
        val downloader = Regex("""^\s*import\s+com\.keyflow\.data\.models\.(ModelDownloader|DownloadGateway|ModelDownloadService|HttpDownloadGateway)\b""")
        val offenders = kotlinFiles(main).filter { it.rel(main).replace('\\', '/').startsWith("com/keyflow/fluxos/") }.flatMap { file ->
            codeLines(file).mapIndexedNotNull { i, line -> if (downloader.containsMatchIn(line)) "${file.rel(main)}:${i + 1}" else null }
        }
        assertEquals("fluxos nunca baixa modelo", emptyList<String>(), offenders)
    }

    @Test
    fun fluxosRuleCatchesAndroidOutsideAppButNotInsideIt() {
        val bad = listOf("package x", "import android.os.Bundle")
        assertEquals(listOf(2), fluxosViolations("com/keyflow/fluxos/Modelo.kt", bad))
        assertEquals(listOf(2), fluxosViolations("com/keyflow/fluxos/execucao/ExecutorFluxo.kt", bad))
        assertEquals(emptyList<Int>(), fluxosViolations("com/keyflow/fluxos/app/FluxoHost.kt", bad))
        assertEquals(emptyList<Int>(), fluxosViolations("com/keyflow/ime/KeyFlowKeyboardService.kt", bad))
        assertEquals(listOf(1), fluxosViolations("com/keyflow/fluxos/X.kt", listOf("import androidx.compose.runtime.Composable")))
        // qualquer androidx (não só Compose): DataStore, core-ktx, lifecycle… são Android
        assertEquals(listOf(1), fluxosViolations("com/keyflow/fluxos/X.kt", listOf("import androidx.datastore.preferences.core.Preferences")))
        assertEquals(listOf(1), fluxosViolations("com/keyflow/fluxos/execucao/X.kt", listOf("import androidx.lifecycle.ViewModel")))
        assertEquals(emptyList<Int>(), fluxosViolations("com/keyflow/fluxos/app/X.kt", listOf("import androidx.datastore.preferences.core.Preferences")))
        assertEquals(listOf(1), fluxosViolations("com/keyflow/fluxos/execucao/X.kt", listOf("import com.keyflow.data.models.ModelDownloader")))
        assertEquals(listOf(1), fluxosViolations("com/keyflow/fluxos/X.kt", listOf("import com.keyflow.auth.SecureCredentialStore")))
        assertEquals(emptyList<Int>(), fluxosViolations("com/keyflow/fluxos/X.kt", listOf("import com.keyflow.data.Settings", "import com.keyflow.i18n.tr")))
    }

    /**
     * `optString` não se usa em `fluxos/`: o do Android devolve "null" para um `null` do JSON e o do Maven (a JVM dos testes)
     * devolve "" — o teste de unidade passa e o aparelho lê outra coisa. O codec lê texto só com `opt(...) as? String`
     * (lição de 2026-10-30, `licoes/2026-10-30-fluxos-fase-a.md`). Vale também para `fluxos/app`.
     */
    private val fluxosOptString = Regex("""\boptString\s*\(""")

    /** Linhas (1-based) de [code] que usam `optString`, para o arquivo em [relPath]; só `com/keyflow/fluxos/` conta. */
    private fun fluxosOptStringViolations(relPath: String, code: List<String>): List<Int> {
        if (!relPath.replace('\\', '/').startsWith("com/keyflow/fluxos/")) return emptyList()
        return code.mapIndexedNotNull { i, line -> if (fluxosOptString.containsMatchIn(line)) i + 1 else null }
    }

    @Test
    fun fluxosNeverUseOptString() {
        val offenders = kotlinFiles(main).flatMap { file ->
            fluxosOptStringViolations(file.rel(main), codeLines(file)).map { "${file.rel(main)}:$it" }
        }
        assertEquals("use opt(nome) as? String em fluxos/ (optString diverge entre Android e JVM)", emptyList<String>(), offenders)
    }

    @Test
    fun fluxosOptStringRuleCatchesTheCallAnywhereUnderFluxos() {
        val chamada = listOf("val x = obj.optString(\"id\")", "val y = obj.opt(\"id\") as? String")
        assertEquals(listOf(1), fluxosOptStringViolations("com/keyflow/fluxos/CodecFluxos.kt", chamada))
        assertEquals(listOf(1), fluxosOptStringViolations("com/keyflow/fluxos/app/Algo.kt", chamada))
        assertEquals(listOf(1), fluxosOptStringViolations("com/keyflow/fluxos/execucao/X.kt", listOf("json.optString ( \"a\", \"b\")")))
        assertEquals(emptyList<Int>(), fluxosOptStringViolations("com/keyflow/core/shortcuts/ShortcutCodec.kt", chamada)) // fora de fluxos/: não é desta regra
        assertEquals(emptyList<Int>(), fluxosOptStringViolations("com/keyflow/fluxos/X.kt", listOf("val optStringy = 1", "fun myoptString() = 2")))
    }

    /** O texto de uma string Kotlin entre aspas como o programa o vê (`\\` vira `\`, `\"` vira `"`). */
    private fun unescapeKotlin(literal: String): String = buildString {
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c == '\\' && i + 1 < literal.length) {
                append(
                    when (val next = literal[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> next
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Na sintaxe de Regex: `}` sem um `{` aberto antes (e sem `\` na frente). */
    private fun hasLoneClosingBrace(pattern: String): Boolean {
        var open = false
        var escaped = false
        for (c in pattern) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '{' -> open = true
                c == '}' -> if (open) open = false else return true
            }
        }
        return false
    }
}

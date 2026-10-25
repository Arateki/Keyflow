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
     * Linhas de [file] (1-based) cujo CÓDIGO casa [predicate]: comentários `//` e `/* … */` (KDoc inclusive, que atravessa
     * linhas) ficam de fora — a documentação pode citar o que a regra proíbe.
     */
    private fun matches(file: File, predicate: (String) -> Boolean): List<Int> {
        var inBlock = false
        return file.readLines().mapIndexedNotNull { i, line ->
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
            if (predicate(code.toString())) i + 1 else null
        }
    }

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

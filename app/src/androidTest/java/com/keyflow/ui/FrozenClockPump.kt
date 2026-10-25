package com.keyflow.ui

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/**
 * Prazo, em tempo REAL, para a tela aparecer (ou o DataStore gravar). Só custa quando o teste já falhou: com a condição
 * satisfeita o laço sai na hora. Medido no emulador carregado: a divulgação levou de 2 a 7 s para existir, e depois de
 * concluir o onboarding o app ocupa a thread principal por vários segundos (início da monetização: Billing, AdMob, leitura
 * de disco na thread principal; `Skipped 568 frames`, `Davey 10 s`), o que atrasa a tela seguinte.
 */
const val PUMP_TIMEOUT_MS = 60_000L

private const val PUMP_FRAME_MS = 100L
private const val PUMP_POLL_MS = 25L

/**
 * Espera uma condição da tela com o relógio de composição congelado (`mainClock.autoAdvance = false`).
 *
 * Com o relógio parado nada recompõe sozinho: `waitUntil` e `waitForIdle` não servem (a orbe anima sem parar), então o
 * teste empurra o relógio à mão, quadro a quadro. O prazo tem de ser em tempo **real**, não em número de quadros: o que
 * a tela espera — a 1ª emissão do `settingsFlow()` da [SetupActivity], que roda fora da thread principal (DataStore,
 * migração dos segredos, cofres cifrados) — não anda com o relógio do teste. Um laço de "100 quadros" acaba em
 * milissegundos reais; no emulador ocupado a emissão chegava depois, a árvore ficava só com o placeholder e o teste
 * caía no `assertExists` seguinte (`OnboardingRoutingTest`, CI de 2026-10-24, duas vezes). Medido com o emulador
 * carregado: a divulgação apareceu depois de 135 e de 239 quadros (6 e 7 s) em 2 de 4 voltas; este laço achou a tela
 * em 4 de 4, entre 1,9 e 3,9 s.
 *
 * Dorme [PUMP_POLL_MS] entre os quadros para dar CPU ao que o app faz em segundo plano, e só desiste no prazo,
 * mostrando a árvore.
 */
fun ComposeTestRule.pumpUntil(what: String, timeoutMs: Long = PUMP_TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition()) {
        if (SystemClock.elapsedRealtime() >= deadline) {
            val tree = runCatching { onRoot().printToString() }.getOrElse { "(árvore indisponível: ${it.message})" }
            throw AssertionError("não apareceu em $timeoutMs ms: $what; árvore:\n$tree")
        }
        mainClock.advanceTimeBy(PUMP_FRAME_MS)
        SystemClock.sleep(PUMP_POLL_MS)
    }
}

/**
 * Existe algum nó com [tag]? Sem janela ainda (`atLeastOneRootRequired = false`) é "não", e o [pumpUntil] segue esperando
 * dentro do prazo, em vez de a biblioteca lançar "No compose hierarchies found" por conta própria.
 */
fun ComposeTestRule.hasNodeWithTag(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/** [pumpUntil] até existir um nó com a tag; depois dele, `onNodeWithTag(tag)` já acha. */
fun ComposeTestRule.pumpUntilTag(tag: String, timeoutMs: Long = PUMP_TIMEOUT_MS) =
    pumpUntil("nó com a tag '$tag'", timeoutMs) { hasNodeWithTag(tag) }

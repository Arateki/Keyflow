package com.keyflow.ui

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.config.TOGGLE_OVERLAY_MODE_TAG
import com.keyflow.ui.config.tabTag
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Fiação real da [SetupActivity] em Compose (T10/T27). O toggle de **Modo Overlay** inicia/encerra o
 * [OverlayService] (FGS). `onboardingDone=true` é semeado **antes** do launch (RuleChain) para a tela
 * inicial já ser a config — assim o padrão `autoAdvance=false` + `performClick` interage sem esperar
 * recomposição (o orbe do header anima infinitamente e quebraria `waitForIdle`).
 */
@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    private val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedOnboardingRule(done = true, acceptedVersion = DISCLOSURE_VERSION))
        .around(
            GrantPermissionRule.grant(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        .around(compose)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        OverlayService.stop(context)
    }

    @After
    fun tearDown() {
        OverlayService.stop(context)
    }

    @Test
    fun overlayModeToggleStartsAndStopsForegroundService() {
        // O orbe do header anima infinitamente; sem isto o link Compose-Espresso nunca fica idle.
        compose.mainClock.autoAdvance = false

        awaitToggle()
        compose.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        waitUntil { OverlayService.isRunningForUi }

        // Deixa o round-trip do DataStore refletir o flag (checked=on) antes de desligar.
        SystemClock.sleep(800)
        compose.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        waitUntil { !OverlayService.isRunningForUi }
    }

    /**
     * Sem a permissão de sobreposição, ligar o toggle não pode nem persistir o flag nem subir o serviço
     * (Yan, 2026-07-04: antes o flag persistia mesmo sem permissão e o toggle ficava "ligado" com a orbe
     * nunca aparecendo, sem nenhum retorno visual do porquê).
     */
    @Test
    fun overlayModeToggleStaysOffWithoutOverlayPermission() {
        compose.mainClock.autoAdvance = false
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        try {
            awaitToggle()
            compose.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
            SystemClock.sleep(500)
            assertFalse(OverlayService.isRunningForUi)
            val enabled = runBlocking {
                SettingsRepository(context).settingsFlow().first().overlayModeEnabled
            }
            assertFalse(enabled)
        } finally {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        }
    }

    // Nota: "o microfone nunca bloqueia o Modo Sobrepor" NÃO é testado aqui via `pm revoke` — revogar uma
    // permissão perigosa com o processo vivo faz o Android MATAR o app na hora (visto no logcat:
    // "ActivityManager: Killing ... permissions revoked"), o que derruba a instrumentação, não é bug do
    // app. Essa regra já é coberta sem matar processo em ModoPanelTest
    // (micChipStaysPendingWithoutBlockingWhenOverlayModeIsOn) e ConfigScreenTest
    // (overlayModeToggleIgnoresMissingMicrophone).

    /**
     * Com `autoAdvance = false` o relógio de composição congela onde estiver: se a primeira composição
     * ainda não terminou, o toggle não existe na árvore e o clique falha com "could not find any node".
     * A `SetupActivity` compõe um placeholder vazio até o DataStore emitir (`settingsLoaded`), então com
     * o relógio parado a recomposição que traria a config nunca gera quadro.
     *
     * `compose.waitUntil` NÃO resolve: com o relógio congelado ele só consulta a condição, sem produzir
     * o quadro que falta — estourava os 15s (medido em 2026-09-09, com a árvore contendo só o nó raiz).
     * Esperar por idle também não serve: a orbe do cabeçalho anima sem parar. Quem destrava é avançar o
     * relógio à mão até a tela existir.
     */
    private fun awaitToggle() {
        var pumps = 0
        var tabOpened = false
        while (compose.onAllNodesWithTag(TOGGLE_OVERLAY_MODE_TAG).fetchSemanticsNodes().isEmpty()) {
            if (pumps++ >= 150) {
                throw AssertionError("toggle não apareceu; árvore:\n" + compose.onRoot().printToString())
            }
            // O interruptor mora no topo da aba Sobrepor desde que a aba Modo saiu (2026-09-28).
            if (!tabOpened && compose.onAllNodesWithTag(tabTag(ConfigTab.OVERLAY)).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
                tabOpened = true
            }
            compose.mainClock.advanceTimeBy(100)
        }
    }

    // 15 s, não 5: no CI (2026-10-09) o serviço levou mais de 5 s para chegar ao `startForeground` sob carga, o
    // teste desistiu e o `stopService` do tearDown, com o primeiro plano ainda pendente, fez o Android derrubar o
    // processo — e os testes que faltavam no shard junto. O próprio sistema dá 10 s ao serviço.
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Condition was not met before timeout")
    }

    /**
     * Roda um comando de shell e ESPERA ele terminar de fato: `executeShellCommand` devolve um pipe que
     * só chega ao fim (EOF) quando o processo do shell encerra — fechar sem ler (como era antes) não dá
     * nenhuma garantia de que o comando já surtiu efeito. Sem isso, `appops set ... deny` podia ainda não
     * ter aplicado no instante do `performClick()` seguinte, e o toggle via com a permissão antiga
     * (Yan, 2026-07-04: falha intermitente em `overlayModeToggleStaysOffWithoutOverlayPermission`).
     */
    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}

/**
 * Semeia `onboardingDone` no DataStore e força `overlayModeEnabled=false` + serviço parado ANTES do
 * launch da Activity (T27). Roda como regra mais externa (antes da `compose` lançar a Activity) — sem
 * isso, uma orbe/serviço ainda vivo do teste anterior pode vazar para o próximo e derrubar suas
 * asserções (Yan, 2026-07-04: falha intermitente em `overlayModeToggleStaysOffWithoutOverlayPermission`
 * quando a suíte roda combinada com outras classes).
 */
class SeedOnboardingRule(
    private val done: Boolean,
    private val acceptedVersion: Int = 0,
) : org.junit.rules.ExternalResource() {
    override fun before() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        OverlayService.stop(ctx)
        runBlocking {
            val repo = SettingsRepository(ctx)
            repo.saveSettings(
                repo.settingsFlow().first().copy(
                    onboardingDone = done,
                    overlayModeEnabled = false,
                    disclosureAcceptedVersion = acceptedVersion,
                    disclosureDeclined = false,
                ),
            )
        }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (OverlayService.isRunningForUi && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
    }
}

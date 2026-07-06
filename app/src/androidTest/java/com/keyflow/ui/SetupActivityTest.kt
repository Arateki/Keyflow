package com.keyflow.ui

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.config.TOGGLE_OVERLAY_MODE_TAG
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
        .outerRule(SeedOnboardingRule(done = true))
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
        context.stopService(OverlayService.intent(context))
    }

    @After
    fun tearDown() {
        context.stopService(OverlayService.intent(context))
    }

    @Test
    fun overlayModeToggleStartsAndStopsForegroundService() {
        // O orbe do header anima infinitamente; sem isto o link Compose-Espresso nunca fica idle.
        compose.mainClock.autoAdvance = false

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

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
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
class SeedOnboardingRule(private val done: Boolean) : org.junit.rules.ExternalResource() {
    override fun before() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.stopService(OverlayService.intent(ctx))
        runBlocking {
            val repo = SettingsRepository(ctx)
            repo.saveSettings(repo.settingsFlow().first().copy(onboardingDone = done, overlayModeEnabled = false))
        }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (OverlayService.isRunningForUi && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
    }
}

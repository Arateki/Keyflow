package com.keyflow.ads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InterstitialPolicyTest {
    @Test
    fun `primeira sessao nunca mostra mesmo com engajamento`() {
        var now = 1_000L
        val policy = InterstitialPolicy(clock = { now })
        repeat(5) { policy.recordInteraction() }

        assertThat(policy.shouldShow(tabSwitch = true)).isFalse()
        assertThat(policy.shouldShow(tabSwitch = false)).isFalse()
    }

    @Test
    fun `engajamento e troca de aba sao obrigatorios`() {
        var now = 1_000L
        val policy = secondSessionPolicy(clock = { now })

        assertThat(policy.shouldShow(tabSwitch = true)).isFalse()
        repeat(4) { policy.recordInteraction() }
        assertThat(policy.shouldShow(tabSwitch = true)).isFalse()
        policy.recordInteraction()
        assertThat(policy.shouldShow(tabSwitch = true)).isTrue()
    }

    @Test
    fun `cooldown dobra a cada exibicao e o estado sobrevive a nova sessao`() {
        var now = 10_000L
        val first = secondSessionPolicy(clock = { now })
        repeat(5) { first.recordInteraction() }
        assertThat(first.shouldShow(true)).isTrue()
        first.markShown()

        now += 239_999L
        assertThat(first.shouldShow(true)).isFalse()
        now += 1L
        repeat(5) { first.recordInteraction() }
        assertThat(first.shouldShow(true)).isTrue()
        first.markShown()

        val restored = InterstitialPolicy(clock = { now }, initialState = first.snapshot())
        repeat(5) { restored.recordInteraction() }
        now += 479_999L
        assertThat(restored.shouldShow(true)).isFalse()
        now += 1L
        assertThat(restored.shouldShow(true)).isTrue()
    }

    @Test
    fun `cap diario reseta no proximo dia`() {
        var now = 86_400_000L
        val policy = secondSessionPolicy(
            clock = { now },
            params = InterstitialPolicyParams(dailyCap = 1, initialCooldownSeconds = 0),
        )
        repeat(5) { policy.recordInteraction() }
        assertThat(policy.shouldShow(true)).isTrue()
        policy.markShown()
        policy.recordInteraction()
        assertThat(policy.shouldShow(true)).isFalse()

        now += 86_400_000L
        repeat(5) { policy.recordInteraction() }
        assertThat(policy.shouldShow(true)).isTrue()
    }

    @Test
    fun `compra inicia janela que suprime interstitial`() {
        var now = 10_000L
        val policy = secondSessionPolicy(clock = { now })
        repeat(5) { policy.recordInteraction() }
        policy.markPurchaseStarted()

        assertThat(policy.shouldShow(true)).isFalse()
        now += 300_000L
        assertThat(policy.shouldShow(true)).isTrue()
    }

    private fun secondSessionPolicy(
        clock: () -> Long,
        params: InterstitialPolicyParams = InterstitialPolicyParams(),
    ): InterstitialPolicy {
        val previous = InterstitialPolicy(clock = clock)
        return InterstitialPolicy(clock = clock, params = params, initialState = previous.snapshot())
    }
}

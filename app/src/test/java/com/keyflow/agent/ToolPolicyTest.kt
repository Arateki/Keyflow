package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** A matriz risco × modo × ferramenta ligada × origem (TLS-02, A2). */
class ToolPolicyTest {

    private fun tool(risk: ToolRisk, name: String = "t_${risk.name.lowercase()}") = FakeTool(name, risk)

    private fun decide(
        risk: ToolRisk,
        mode: ConfirmationMode = ConfirmationMode.ASK_FOR_CHANGES,
        master: Boolean = true,
        toolOn: Boolean = true,
        always: Boolean = false,
        origin: ToolOrigin = ToolOrigin.CHAT,
    ): PolicyDecision {
        val t = tool(risk)
        val config = ToolPolicyConfig(
            toolsEnabled = master,
            mode = mode,
            disabledTools = if (toolOn) emptySet() else setOf(t.name),
            alwaysAllowed = if (always) setOf(t.name) else emptySet(),
        )
        return ToolPolicy({ config }).decide(t, origin)
    }

    @Test
    fun `default - read runs, write and destructive ask (A2)`() {
        assertThat(decide(ToolRisk.READ)).isEqualTo(PolicyDecision.Run)
        assertThat(decide(ToolRisk.WRITE)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = true))
        assertThat(decide(ToolRisk.DESTRUCTIVE)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
    }

    @Test
    fun `the strict mode asks even to read, without always allow`() {
        val strict = ConfirmationMode.ASK_FOR_ALL
        assertThat(decide(ToolRisk.READ, strict)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
        assertThat(decide(ToolRisk.WRITE, strict)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
        assertThat(decide(ToolRisk.DESTRUCTIVE, strict)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
        // "permitir sempre" não vale no modo estrito.
        assertThat(decide(ToolRisk.WRITE, strict, always = true)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
    }

    @Test
    fun `always allow lets a write run directly, from the chat only`() {
        assertThat(decide(ToolRisk.WRITE, always = true)).isEqualTo(PolicyDecision.Run)
        assertThat(decide(ToolRisk.WRITE, always = true, origin = ToolOrigin.EXTERNAL)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
        assertThat(decide(ToolRisk.WRITE, origin = ToolOrigin.EXTERNAL)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
    }

    @Test
    fun `a destructive tool listed as always allowed still asks`() {
        // A lista vem de um ajuste que qualquer coisa pode ter escrito: a regra é do risco, não da lista.
        assertThat(decide(ToolRisk.DESTRUCTIVE, always = true)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
        assertThat(decide(ToolRisk.DESTRUCTIVE, always = true, mode = ConfirmationMode.ASK_FOR_ALL)).isEqualTo(PolicyDecision.Ask(canAlwaysAllow = false))
    }

    @Test
    fun `a disabled tool or the master switch off denies, whatever the risk`() {
        for (risk in ToolRisk.values()) {
            assertThat(decide(risk, master = false)).isEqualTo(PolicyDecision.Deny(DenyReason.TOOLS_OFF))
            assertThat(decide(risk, toolOn = false)).isEqualTo(PolicyDecision.Deny(DenyReason.TOOL_OFF))
            assertThat(decide(risk, toolOn = false, always = true)).isEqualTo(PolicyDecision.Deny(DenyReason.TOOL_OFF))
        }
    }

    @Test
    fun `the invariants hold over the whole matrix`() {
        var cells = 0
        for (risk in ToolRisk.values()) for (mode in ConfirmationMode.values()) for (master in listOf(true, false)) for (toolOn in listOf(true, false))
            for (always in listOf(true, false)) for (origin in ToolOrigin.values()) {
                cells++
                val d = decide(risk, mode, master, toolOn, always, origin)
                val where = "$risk/$mode/master=$master/toolOn=$toolOn/always=$always/$origin → $d"
                // 1. desligada: nunca roda nem pergunta.
                if (!master || !toolOn) assertWithMessage(where).that(d).isInstanceOf(PolicyDecision.Deny::class.java)
                // 2. destrutiva: nunca roda direto e nunca oferece "sempre".
                if (risk == ToolRisk.DESTRUCTIVE && master && toolOn) assertWithMessage(where).that(d).isEqualTo(PolicyDecision.Ask(false))
                // 3. só roda direto: leitura no modo padrão, ou escrita liberada no chat no modo padrão.
                val mayRun = master && toolOn && mode == ConfirmationMode.ASK_FOR_CHANGES &&
                    (risk == ToolRisk.READ || (risk == ToolRisk.WRITE && always && origin == ToolOrigin.CHAT))
                assertWithMessage(where).that(d == PolicyDecision.Run).isEqualTo(mayRun)
                // 4. "permitir sempre" só é oferecido para escrita no chat no modo padrão.
                val offers = master && toolOn && mode == ConfirmationMode.ASK_FOR_CHANGES && risk == ToolRisk.WRITE && origin == ToolOrigin.CHAT && !always
                assertWithMessage(where).that(d == PolicyDecision.Ask(true)).isEqualTo(offers)
            }
        assertThat(cells).isEqualTo(3 * 2 * 2 * 2 * 2 * 2)
    }

    @Test
    fun `the configuration is read at every decision`() {
        var config = ToolPolicyConfig()
        val policy = ToolPolicy({ config })
        val t = tool(ToolRisk.READ)
        assertThat(policy.decide(t, ToolOrigin.CHAT)).isEqualTo(PolicyDecision.Run)
        assertThat(policy.isEnabled(t.name)).isTrue()
        config = config.copy(disabledTools = setOf(t.name))
        assertThat(policy.decide(t, ToolOrigin.CHAT)).isEqualTo(PolicyDecision.Deny(DenyReason.TOOL_OFF))
        assertThat(policy.isEnabled(t.name)).isFalse()
        config = ToolPolicyConfig(toolsEnabled = false)
        assertThat(policy.isEnabled("anything")).isFalse()
    }

    @Test
    fun `grantAlways only records a write asked from the chat`() {
        val granted = ArrayList<String>()
        val policy = ToolPolicy({ ToolPolicyConfig() }, onAlwaysAllow = { granted += it })
        assertThat(policy.grantAlways(tool(ToolRisk.WRITE, "w"), ToolOrigin.CHAT)).isTrue()
        assertThat(policy.grantAlways(tool(ToolRisk.WRITE, "w2"), ToolOrigin.EXTERNAL)).isFalse()
        assertThat(policy.grantAlways(tool(ToolRisk.DESTRUCTIVE, "d"), ToolOrigin.CHAT)).isFalse()
        assertThat(policy.grantAlways(tool(ToolRisk.READ, "r"), ToolOrigin.CHAT)).isFalse()
        assertThat(granted).containsExactly("w")
    }
}

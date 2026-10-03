package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** TLS-03 / D5: o filtro remove segredo sem mutilar texto comum. Os valores são sentinelas inventadas. */
class SecretFilterTest {

    private val filter = SecretFilter()

    @Test
    fun `removes every known secret format and keeps the text around it`() {
        for (secret in Sentinels.ALL) {
            val redacted = filter.redact("antes $secret depois")
            assertWithMessage("o segredo continua no texto").that(redacted).doesNotContain(secret)
            assertThat(redacted).isEqualTo("antes ${SecretFilter.MARKER} depois")
        }
    }

    @Test
    fun `a secret in the middle of json or an error message is removed`() {
        val text = """{"error":"401","header":"x-api-key: ${Sentinels.ANTHROPIC}","also":"${Sentinels.GOOGLE}"}"""
        val redacted = filter.redact(text)
        assertThat(redacted).doesNotContain(Sentinels.ANTHROPIC)
        assertThat(redacted).doesNotContain(Sentinels.GOOGLE)
        assertThat(redacted).startsWith("""{"error":"401","header":"x-api-key: """)
    }

    @Test
    fun `a pem block goes away whole, even when the text was cut before the end line`() {
        val cut = "log:\n-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmU\nQQQQ"
        val redacted = filter.redact(cut)
        assertThat(redacted).isEqualTo("log:\n${SecretFilter.MARKER}")
        assertThat(filter.redact("a ${Sentinels.PEM} b")).isEqualTo("a ${SecretFilter.MARKER} b")
    }

    @Test
    fun `two different pem blocks in one text are both removed`() {
        val one = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
        val two = "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----"
        assertThat(filter.redact("$one\nmeio\n$two")).isEqualTo("${SecretFilter.MARKER}\nmeio\n${SecretFilter.MARKER}")
    }

    @Test
    fun `removes the values registered in the vault, in any shape`() {
        val vault = SecretFilter({ listOf(Sentinels.VAULT) })
        assertThat(filter.redact("chave ${Sentinels.VAULT}")).contains(Sentinels.VAULT) // sem o cofre, nenhum padrão a reconhece
        assertThat(vault.redact("chave ${Sentinels.VAULT} e de novo ${Sentinels.VAULT}.")).isEqualTo("chave ${SecretFilter.MARKER} e de novo ${SecretFilter.MARKER}.")
        assertThat(vault.containsSecret("x ${Sentinels.VAULT}")).isTrue()
    }

    @Test
    fun `the registered secrets are read at every use`() {
        var secrets = listOf("first-secret-value")
        val live = SecretFilter({ secrets })
        assertThat(live.redact("a first-secret-value b second-secret-value")).isEqualTo("a ${SecretFilter.MARKER} b second-secret-value")
        secrets = listOf("second-secret-value")
        assertThat(live.redact("a first-secret-value b second-secret-value")).isEqualTo("a first-secret-value b ${SecretFilter.MARKER}")
    }

    @Test
    fun `a short or empty registered value does not mangle ordinary text`() {
        val live = SecretFilter({ listOf("", "a", "abc", "1234567") }) // todos abaixo do mínimo de 8
        val text = "uma frase com a letra a, abc e 1234567 no meio"
        assertThat(live.redact(text)).isEqualTo(text)
    }

    @Test
    fun `a longer registered secret that contains a shorter one is removed whole`() {
        val live = SecretFilter({ listOf("secretvalue", "secretvalue-with-suffix-0001") })
        val redacted = live.redact("x secretvalue-with-suffix-0001 y")
        assertThat(redacted).isEqualTo("x ${SecretFilter.MARKER} y")
    }

    @Test
    fun `ordinary text is not mangled`() {
        val clean = listOf(
            "A tarefa desk-organizer-with-many-compartments-for-everything é útil.",
            "task-list-management-for-teams-and-families-everywhere",
            "risk-assessment-framework-for-android-applications-2026",
            "Authorization: Bearer <token>",
            "O portador (Bearer) recebeu a encomenda.",
            "Bearer tokens are opaque",
            "AIzaa e AIza curto",
            "sk-learn, sk-pack e sk-",
            "eyJ sozinho, ou eyJhbGci.curto.x",
            "pack:neon-1a2b3c4d",
            """{"orbSizePx":156,"themeDark":true,"keyboardSkin":"pack:neon-1a2b3c4d"}""",
            "https://example.com/path/to/page?x=1&y=2",
            "a1b2c3d4".repeat(8), // um hash hexadecimal de 64 caracteres (um SHA-256) não é segredo
            "Olá! Instalei a skin \"Neon Rain\" (45 frames, 12 fps). Tudo certo, sem avisos.",
            "-----BEGIN-----", // não é um bloco PEM de verdade
            "ghp_curto e AKIAcurto e gsk_x e xai-y",
            "",
        )
        for (text in clean) assertWithMessage("o texto comum foi mudado: $text").that(filter.redact(text)).isEqualTo(text)
    }

    @Test
    fun `redacting twice gives the same as redacting once`() {
        val text = "k ${Sentinels.ANTHROPIC} e ${Sentinels.JWT} e ${Sentinels.PEM}"
        val once = filter.redact(text)
        assertThat(filter.redact(once)).isEqualTo(once)
    }

    @Test
    fun `containsSecret tells a secret from clean text`() {
        assertThat(filter.containsSecret("nada aqui")).isFalse()
        assertThat(filter.containsSecret("x ${Sentinels.OPENAI}")).isTrue()
        assertThat(filter.containsSecret("")).isFalse()
    }

    @Test
    fun `the marker carries no part of any secret`() {
        for (secret in Sentinels.ALL + Sentinels.VAULT) assertThat(SecretFilter.MARKER).doesNotContain(secret.take(6))
    }

    @Test
    fun `when the vault cannot be read nothing passes`() {
        val broken = SecretFilter({ throw IllegalStateException("cofre travado") })
        assertThat(broken.redact("um texto qualquer")).isEqualTo(SecretFilter.WITHHELD)
        assertThat(broken.containsSecret("um texto qualquer")).isTrue()
        assertThat(broken.redact("")).isEmpty()
    }

    @Test
    fun `a very large text is filtered without blowing up`() {
        val big = ("lorem ipsum dolor sit amet ".repeat(2_000) + Sentinels.ANTHROPIC + " ").repeat(40)
        val redacted = filter.redact(big)
        assertThat(redacted).doesNotContain("sk-ant-")
        // Muitos BEGIN sem END também.
        val manyPem = "-----BEGIN PRIVATE KEY-----\nxx\n".repeat(5_000)
        assertThat(filter.redact(manyPem)).isEqualTo(SecretFilter.MARKER)
    }
}

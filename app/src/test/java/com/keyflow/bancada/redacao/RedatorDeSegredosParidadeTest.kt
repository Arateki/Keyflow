package com.keyflow.bancada.redacao

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.agent.SecretFilter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * O `RedatorDeSegredos` é uma cópia deliberada do `SecretFilter` (o agente não vai no release; a bancada sim — ver o KDoc dele). Este teste é o que impede as duas
 * de divergirem: as mesmas entradas, o mesmo resultado, com e sem segredos registrados, e as mesmas constantes. Mexeu num padrão de um? Mexa no outro.
 */
class RedatorDeSegredosParidadeTest {

    // Chaves de mentira, no formato de cada provedor.
    private val amostras = listOf(
        "",
        "texto comum sem nada",
        "desk-organizer-1234567890abcdef e task-aaaaaaaaaaaaaaaaaaaaaaaa não são chaves",
        "use sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 agora",
        "OpenAI sk-proj-abcdefghijklmnopqrstuvwxyz",
        "Google AIzaSyA1234567890abcdefghijklmnopqrstuv e mais texto",
        "Groq gsk_abcdefghijklmnopqrstuvwxyz1234",
        "xAI xai-abcdefghijklmnopqrstuvwxyz123456",
        "ElevenLabs sk_0123456789abcdef0123456789abcdef0123",
        "GitHub ghp_abcdefghijklmnopqrstuvwxyz0123456789",
        "github_pat_abcdefghijklmnopqrstuvwxyz0123456789_abcdefghijklmnopqrstuvwxyz",
        "AWS AKIAABCDEFGHIJKLMNOP fim",
        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789",
        "bearer abcdefghijklmnop",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.assinatura-abc_123",
        "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq\n-----END PRIVATE KEY-----\ndepois",
        "-----BEGIN RSA PRIVATE KEY-----\ncortado sem fim",
        "uma linha com sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 e AKIAABCDEFGHIJKLMNOP juntas",
        "AKIAABCDEFGHIJKLMNOPQRS tem letra a mais no fim",
        "segredo-registrado-12345 aparece aqui",
        "curto abc não entra na troca por valor",
        "o maior vence: segredo-registrado-12345-completo",
    )
    private val registrados = listOf("segredo-registrado-12345", "segredo-registrado-12345-completo", "abc")

    @Test
    fun `same output for the same input, without registered secrets`() {
        val a = SecretFilter()
        val b = RedatorDeSegredos()
        for (texto in amostras) {
            assertWithMessage("«$texto»").that(b.redact(texto)).isEqualTo(a.redact(texto))
            assertThat(b.containsSecret(texto)).isEqualTo(a.containsSecret(texto))
        }
    }

    @Test
    fun `same output for the same input, with registered secrets`() {
        val a = SecretFilter { registrados }
        val b = RedatorDeSegredos { registrados }
        for (texto in amostras) assertWithMessage("«$texto»").that(b.redact(texto)).isEqualTo(a.redact(texto))
        assertThat(b.redact("o maior vence: segredo-registrado-12345-completo")).isEqualTo("o maior vence: ${RedatorDeSegredos.MARKER}")
        assertThat(b.redact("curto abc")).isEqualTo("curto abc") // abaixo de 8 caracteres não entra
    }

    @Test
    fun `an unreadable secret list fails closed in both`() {
        val a = SecretFilter { error("cofre ilegível") }
        val b = RedatorDeSegredos { error("cofre ilegível") }
        assertThat(b.redact("qualquer texto")).isEqualTo(a.redact("qualquer texto"))
        assertThat(b.redact("qualquer texto")).isEqualTo(RedatorDeSegredos.WITHHELD)
        assertThat(b.redact("")).isEqualTo("") // texto vazio nem consulta o cofre
    }

    @Test
    fun `the constants are the same`() {
        assertThat(RedatorDeSegredos.MARKER).isEqualTo(SecretFilter.MARKER)
        assertThat(RedatorDeSegredos.WITHHELD).isEqualTo(SecretFilter.WITHHELD)
        assertThat(RedatorDeSegredos.MIN_SECRET_LENGTH).isEqualTo(SecretFilter.MIN_SECRET_LENGTH)
    }

    @Test
    fun `same output for random mixtures of the samples`() = runBlocking<Unit> {
        val a = SecretFilter { registrados }
        val b = RedatorDeSegredos { registrados }
        checkAll(1_000, Arb.list(Arb.element(amostras), 1..5)) { partes ->
            val texto = partes.joinToString(" ")
            assertThat(b.redact(texto)).isEqualTo(a.redact(texto))
        }
    }
}

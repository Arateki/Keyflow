package com.keyflow.auth

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * AUTH-01: o isolamento conferido no código-fonte, não na confiança. Lê como texto todos os .kt de `app/src/main/java/com/keyflow`
 * e falha se alguém importar o que não devia — é o que impede a autenticação de voltar a se espalhar pelo app.
 *
 *  - só o registro (`AuthProviders.kt`) e o motor de cada provedor (`reasoning/<provedor>/`) importam `auth/providers/<x>`;
 *  - o núcleo de `auth/` não conhece `Settings`, UI nem `reasoning/`;
 *  - um provedor concreto não conhece `Settings` nem UI.
 */
class AuthIsolationTest {

    private val root = File("src/main/java/com/keyflow")

    private fun sources(): List<Pair<String, List<String>>> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).invariantSeparatorsPath to it.readLines().filter { l -> l.trimStart().startsWith("import ") }.map { l -> l.trim() } }
        .toList()

    private fun violations(where: (String) -> Boolean, forbidden: String): List<String> =
        sources().filter { (path, _) -> where(path) }
            .flatMap { (path, imports) -> imports.filter { it.startsWith("import $forbidden") }.map { "$path → $it" } }

    @Test
    fun `the source tree is being read`() {
        assertWithMessage("nenhum .kt lido — o teste está olhando o diretório certo?").that(sources().size).isGreaterThan(50)
    }

    @Test
    fun `only the registry and the provider's own reasoning engine import a provider package`() {
        val allowed = { path: String ->
            path == "auth/AuthProviders.kt" || path.startsWith("auth/providers/") || path.startsWith("reasoning/openai/") || path.startsWith("reasoning/anthropic/") || path.startsWith("reasoning/google/")
        }
        val found = violations({ !allowed(it) }, "com.keyflow.auth.providers.")
        assertWithMessage("importa um provedor concreto fora do registro:\n${found.joinToString("\n")}").that(found).isEmpty()
    }

    @Test
    fun `the ui never imports a provider package`() {
        val found = violations({ it.startsWith("ui/") }, "com.keyflow.auth.providers.")
        assertWithMessage(found.joinToString("\n")).that(found).isEmpty()
    }

    @Test
    fun `the auth core knows neither settings nor the ui nor reasoning`() {
        val core = { path: String -> path.startsWith("auth/") && !path.startsWith("auth/providers/") }
        for (forbidden in listOf("com.keyflow.data.Settings", "com.keyflow.ui.", "com.keyflow.reasoning.", "com.keyflow.overlay.", "com.keyflow.ime.")) {
            val found = violations(core, forbidden)
            assertWithMessage("o núcleo de auth/ importa $forbidden:\n${found.joinToString("\n")}").that(found).isEmpty()
        }
    }

    @Test
    fun `a concrete provider knows neither settings nor the ui`() {
        val providers = { path: String -> path.startsWith("auth/providers/") }
        for (forbidden in listOf("com.keyflow.data.Settings", "com.keyflow.ui.", "com.keyflow.overlay.", "com.keyflow.ime.")) {
            val found = violations(providers, forbidden)
            assertWithMessage("um provedor importa $forbidden:\n${found.joinToString("\n")}").that(found).isEmpty()
        }
    }

    /** O cofre pode usar `SecurePrefs` (é a razão dele existir) — a única dependência de `data/` permitida ao núcleo. */
    @Test
    fun `the only data package dependency the auth core has is the secure prefs`() {
        val core = { path: String -> path.startsWith("auth/") && !path.startsWith("auth/providers/") }
        val found = sources().filter { (path, _) -> core(path) }
            .flatMap { (path, imports) -> imports.filter { it.startsWith("import com.keyflow.data.") && it != "import com.keyflow.data.SecurePrefs" }.map { "$path → $it" } }
        assertWithMessage(found.joinToString("\n")).that(found).isEmpty()
    }
}

package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * BNC-T7: `ModelSpec.contextTokens` é a janela do arquivo e só entra quando se SABE (nome do `.litertlm`, `…_ekvN`, ou o card): a bancada trata
 * um caso maior que ela como "não se aplica", e um número inventado cortaria casos de graça — ou deixaria passar um que não cabe.
 */
class ModelContextTokensTest {

    private val modelos = (ModelCatalog.MODELS + ModelCatalog.TEXT_ALL).distinctBy { it.id }

    @Test
    fun `a registered window always matches the ekv of the file name`() {
        for (m in modelos.filter { it.contextTokens != null }) {
            val arquivo = m.file(FileRole.MODEL)!!.fileName
            assertWithMessage("${m.id}: janela ${m.contextTokens} sem ekv${m.contextTokens} em $arquivo").that(arquivo).contains("ekv${m.contextTokens}")
        }
    }

    @Test
    fun `only text models carry a window and the ones known from the file names have it`() {
        assertThat(modelos.filter { it.contextTokens != null }.all { it.kind == ModelKind.TEXT }).isTrue()
        val conhecidas = modelos.filter { it.contextTokens != null }.associate { it.id to it.contextTokens }
        assertThat(conhecidas).containsAtLeast(
            "qwen2.5-1.5b-litertlm", 4096,
            "deepseek-r1-1.5b-litertlm", 4096,
            "functiongemma-270m-mobile-actions-litertlm", 1024,
            "smollm3-3b-litertlm", 4096,
        )
    }

    @Test
    fun `a text model whose file name carries an ekv has its window registered`() {
        val esquecidos = modelos.filter { it.kind == ModelKind.TEXT }.filter { m ->
            Regex("ekv(\\d+)").containsMatchIn(m.file(FileRole.MODEL)!!.fileName) && m.contextTokens == null
        }.map { it.id }
        assertWithMessage("janela no nome do arquivo e fora do catálogo: $esquecidos").that(esquecidos).isEmpty()
    }

    @Test
    fun `the pins fingerprint ignores the window - it is not a pin`() {
        assertThat(ModelCatalog.pinsFingerprint()).isNotEmpty()
    }
}

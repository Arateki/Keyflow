package com.keyflow.bancada.pontuacao

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class ExtratorJsonTest {
    @Test
    fun `clean json`() {
        val e = ExtratorJson.extrair("""{"a": 1}""")
        assertThat(e.forma).isEqualTo(ExtratorJson.Forma.LIMPO)
        assertThat(e.primeiroObjeto!!.getInt("a")).isEqualTo(1)
    }

    @Test
    fun `fenced json`() {
        for (saida in listOf("```json\n{\"a\": 1}\n```", "```\n{\"a\": 1}\n```", "  ```JSON\n{\"a\": 1}```  ")) {
            assertThat(ExtratorJson.extrair(saida).forma).isEqualTo(ExtratorJson.Forma.CERCA)
        }
    }

    @Test
    fun `json with text around`() {
        val e = ExtratorJson.extrair("Claro! Aqui está o JSON: {\"a\": 1} Espero ter ajudado.")
        assertThat(e.forma).isEqualTo(ExtratorJson.Forma.EM_VOLTA)
        assertThat(e.primeiroObjeto!!.getInt("a")).isEqualTo(1)
    }

    @Test
    fun `braces inside strings do not break the balance`() {
        val e = ExtratorJson.extrair("""{"a": "}{ \" ]", "b": [1, {"c": 2}]}""")
        assertThat(e.forma).isEqualTo(ExtratorJson.Forma.LIMPO)
        assertThat(e.primeiroObjeto!!.getString("a")).isEqualTo("}{ \" ]")
    }

    @Test
    fun `several top-level values are all found in order`() {
        val e = ExtratorJson.extrair("""{"n": 1} {"n": 2}""")
        assertThat(e.valores).hasSize(2)
        assertThat((e.valores[1] as JSONObject).getInt("n")).isEqualTo(2)
        assertThat(e.forma).isEqualTo(ExtratorJson.Forma.EM_VOLTA)
    }

    @Test
    fun `no json, broken json and hostile nesting`() {
        assertThat(ExtratorJson.extrair("").forma).isEqualTo(ExtratorJson.Forma.NENHUM)
        assertThat(ExtratorJson.extrair("só texto, sem nada").forma).isEqualTo(ExtratorJson.Forma.NENHUM)
        assertThat(ExtratorJson.extrair("{nome: Ana").forma).isEqualTo(ExtratorJson.Forma.NENHUM)
        assertThat(ExtratorJson.extrair("[".repeat(10_000)).forma).isEqualTo(ExtratorJson.Forma.NENHUM)
        assertThat(ExtratorJson.extrair("{\"a\":".repeat(5_000)).forma).isEqualTo(ExtratorJson.Forma.NENHUM)
    }

    @Test
    fun `syntax that is not strict json does not count as json`() {
        for (saida in listOf("{nome:'Ana'}", "{'nome': 'Ana'}", """{"a": 1,}""", """{"a": 1; "b": 2}""", "[1, 2,]", """{"a": 01}""")) {
            assertThat(ExtratorJson.extrair(saida).valores).isEmpty()
        }
        assertThat(ExtratorJson.extrair("""{"a": [1, -2.5e3, true, null, "x\u00e9"]}""").valores).hasSize(1)
    }

    @Test
    fun `an output longer than the limit is flagged as truncated`() {
        assertThat(ExtratorJson.extrair("""{"a": 1}""").truncada).isFalse()
        assertThat(ExtratorJson.extrair("""{"a": 1}""" + " ".repeat(20_000) + """{"b": 2}""").truncada).isTrue()
    }
}

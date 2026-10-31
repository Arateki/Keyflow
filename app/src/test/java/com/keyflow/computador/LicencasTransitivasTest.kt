package com.keyflow.computador

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O inventário acompanha o grafo selecionado pelo Gradle, não apenas as dependências diretas. */
class LicencasTransitivasTest {
    private fun grafo() = JSONArray(File("build/reports/computador/dependencias-terminal.json").readText())
    private fun inventario() = JSONObject(File("licenses/computador-dependencias.json").readText())
    private val permitidas = setOf("Apache-2.0", "BSD-3-Clause", "BSL-1.0", "ISC", "MIT")

    private fun validar(real: JSONArray, auditado: JSONObject): List<String> {
        val erros = mutableListOf<String>()
        val embutidos = auditado.getJSONArray("embedded_notices")
        repeat(embutidos.length()) {
            val item = embutidos.getJSONObject(it)
            if (item.getString("id") !in permitidas) erros += "Licença embutida proibida"
            if (!File("licenses", item.getString("notice")).isFile) erros += "Aviso embutido ausente"
        }
        val registros = auditado.getJSONArray("dependencies")
        val esperados = (0 until registros.length()).map { registros.getJSONObject(it) }.associateBy { it.getString("coordinate") }
        if (esperados.size != registros.length()) erros += "Auditoria com coordenada repetida"
        val recebidos = (0 until real.length()).map { real.getJSONObject(it).getString("coordinate") }.toSet()
        if (recebidos != esperados.keys) erros += "Componentes sem auditoria ou removidos"
        for ((coordenada, registro) in esperados) {
            val licencas = registro.getJSONArray("licenses")
            if (licencas.length() == 0) erros += "Licença ausente: $coordenada"
            repeat(licencas.length()) {
                if (licencas.getJSONObject(it).getString("id") !in permitidas) erros += "Licença proibida: $coordenada"
            }
        }
        repeat(real.length()) { indice ->
            val recebido = real.getJSONObject(indice)
            val esperado = esperados[recebido.getString("coordinate")] ?: return@repeat
            fun hashes(objeto: JSONObject): Map<String, String> {
                val arquivos = objeto.getJSONArray("artifacts")
                return (0 until arquivos.length()).associate { arquivos.getJSONObject(it).getString("name") to arquivos.getJSONObject(it).getString("sha256") }
            }
            if (hashes(recebido) != hashes(esperado)) erros += "Hash divergente: ${recebido.getString("coordinate")}"
        }
        return erros
    }

    @Test fun grafoRealTemTodasAsLicencasEHashesAuditados() {
        assertTrue("O grafo deve incluir as transitivas", grafo().length() > 20)
        assertEquals(emptyList<String>(), validar(grafo(), inventario()))
    }

    @Test fun gplLgplAgplENaoComercialSaoRecusadas() {
        for (id in listOf("GPL-3.0-only", "LGPL-3.0-only", "AGPL-3.0-only", "CC-BY-NC-4.0")) {
            val alterado = inventario()
            alterado.getJSONArray("dependencies").getJSONObject(0).getJSONArray("licenses").getJSONObject(0).put("id", id)
            assertTrue(id, validar(grafo(), alterado).any { it.startsWith("Licença proibida") })
        }
    }

    @Test fun dependenciaNovaExigeAuditoria() {
        val alterado = grafo().put(JSONObject().put("coordinate", "teste:sem-auditoria:1.0").put("artifacts", JSONArray()))
        assertTrue(validar(alterado, inventario()).contains("Componentes sem auditoria ou removidos"))
    }

    @Test fun mudancaDeBinarioSemTrocarVersaoReprova() {
        val alterado = grafo()
        val item = (0 until alterado.length()).map { alterado.getJSONObject(it) }.first { it.getJSONArray("artifacts").length() > 0 }
        item.getJSONArray("artifacts").getJSONObject(0).put("sha256", "0".repeat(64))
        assertTrue(validar(alterado, inventario()).any { it.startsWith("Hash divergente") })
    }
}

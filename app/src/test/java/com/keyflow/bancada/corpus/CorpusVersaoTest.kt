package com.keyflow.bancada.corpus

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * BCP-06: mudar um caso, o prompt ou a lista de ferramentas SOBE a versão do teste — resultados de versões diferentes não se comparam. O
 * arquivo `versoes-fixadas.json` guarda, para cada (teste, versão) que já existiu, o SHA-256 do arquivo daquela versão. Se o arquivo do
 * corpus muda e a versão não, este teste falha; quem sobe a versão acrescenta a linha nova (e deixa as antigas, que são o histórico).
 *
 * O pontuador é a outra metade da versão: uma mudança que muda a nota quebra `PontuadoresTest`, com vetores escritos à mão — quem o altera de
 * propósito reescreve o vetor E sobe a versão dos testes que o usam.
 */
class CorpusVersaoTest {

    private val fixadas = JSONObject(File("src/test/resources/bancada/versoes-fixadas.json").readText())

    @Test
    fun `every file of the real corpus is pinned for its version`() {
        val fonte = DiretorioFonte(File("src/main/assets/bancada/v1"))
        val corpus = CorpusLoader.carregar(fonte)
        val daVersao = fixadas.getJSONObject("v${corpus.versao}")
        for (arquivo in corpus.manifesto.arquivos.filter { it.caminho.endsWith(".json") }) {
            val teste = corpus.testes.values.first { "${it.id}.json" == arquivo.caminho }
            val fixados = daVersao.optJSONObject(teste.id)
            assertWithMessage("${teste.id}: nenhuma versão fixada — acrescente a linha em versoes-fixadas.json").that(fixados).isNotNull()
            val sha = fixados!!.optString(teste.versao.toString())
            assertWithMessage(
                "${arquivo.caminho} mudou sem subir a versão do teste (${teste.versao}). Suba `versao` em scripts/bancada-corpus.py, " +
                    "regere e acrescente a linha nova em versoes-fixadas.json (BCP-06).",
            ).that(arquivo.sha256).isEqualTo(sha)
        }
    }
}

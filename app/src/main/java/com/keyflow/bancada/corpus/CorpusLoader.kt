package com.keyflow.bancada.corpus

import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/** O corpus não pôde ser lido ou não bate com o manifesto. A mensagem diz qual arquivo e por quê. */
class CorpusInvalidoException(message: String) : Exception(message)

/** SHA-256 em hexadecimal minúsculo. */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Lê o corpus de uma [FonteDoCorpus] (BCP-01). Por padrão confere o SHA-256 de cada arquivo contra o manifesto: um corpus que não bate
 * não vira nota, vira erro. A conferência completa (licença, ids, orçamento, segredos) é do [CorpusValidador].
 */
object CorpusLoader {
    const val MANIFESTO = "manifesto.json"

    fun carregar(fonte: FonteDoCorpus, verificarHash: Boolean = true): Corpus {
        val manifesto = lerManifesto(fonte)
        val testes = LinkedHashMap<String, TesteDef>()
        for (arquivo in manifesto.arquivos) {
            val bytes = fonte.ler(arquivo.caminho) ?: throw CorpusInvalidoException("arquivo do manifesto ausente: ${arquivo.caminho}")
            if (verificarHash && sha256Hex(bytes) != arquivo.sha256) {
                throw CorpusInvalidoException("o SHA-256 de ${arquivo.caminho} não bate com o manifesto")
            }
            if (arquivo.caminho.endsWith(".json")) {
                val teste = lerTeste(arquivo.caminho, bytes)
                if (testes.put(teste.id, teste) != null) throw CorpusInvalidoException("teste repetido: ${teste.id}")
            }
        }
        return Corpus(manifesto.corpus, manifesto, testes)
    }

    fun lerManifesto(fonte: FonteDoCorpus): Manifesto {
        val bytes = fonte.ler(MANIFESTO) ?: throw CorpusInvalidoException("sem $MANIFESTO")
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val lista = json.getJSONArray("arquivos")
            Manifesto(
                corpus = json.getInt("corpus"),
                arquivos = (0 until lista.length()).map { i ->
                    val a = lista.getJSONObject(i)
                    ArquivoManifesto(a.getString("caminho"), a.getString("sha256"), a.getString("origem"), a.getString("autor"), a.getString("licenca"))
                },
            )
        } catch (e: JSONException) {
            throw CorpusInvalidoException("$MANIFESTO ilegível: ${e.message}")
        }
    }

    fun lerTeste(caminho: String, bytes: ByteArray): TesteDef = try {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val prompt = json.getJSONObject("prompt")
        val casos = json.getJSONArray("casos")
        TesteDef(
            id = json.getString("teste"),
            versao = json.getInt("versao"),
            capacidade = Capacidade.valueOf(json.getString("capacidade")),
            descricao = json.getString("descricao"),
            prompt = Prompt(system = if (prompt.has("system") && !prompt.isNull("system")) prompt.getString("system") else null, user = prompt.getString("user")),
            casos = (0 until casos.length()).map { lerCaso(casos.getJSONObject(it)) },
            ferramentas = if (json.has("ferramentas")) json.getString("ferramentas") else null,
        )
    } catch (e: JSONException) {
        throw CorpusInvalidoException("$caminho ilegível: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw CorpusInvalidoException("$caminho: capacidade desconhecida (${e.message})")
    }

    private fun lerCaso(json: JSONObject): Caso = Caso(
        id = json.getString("id"),
        idioma = json.getString("idioma"),
        entrada = json.getString("entrada"),
        maxTokensSaida = json.getInt("maxTokensSaida"),
        tokensEntrada = json.getInt("tokensEntrada"),
        bruto = json,
    )
}

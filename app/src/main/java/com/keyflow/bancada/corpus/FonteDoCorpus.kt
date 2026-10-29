package com.keyflow.bancada.corpus

import java.io.File

/**
 * De onde vêm os bytes do corpus: os assets do APK no app, uma pasta nos testes. Os caminhos são relativos à pasta da versão
 * (`bancada/v1/`), com `/` como separador.
 */
interface FonteDoCorpus {
    /** Os bytes de [caminho], ou `null` se o arquivo não existe. */
    fun ler(caminho: String): ByteArray?

    /** Todos os arquivos da versão (recursivo), incluindo o `manifesto.json`. */
    fun listar(): List<String>
}

/** O corpus numa pasta do disco. */
class DiretorioFonte(private val raiz: File) : FonteDoCorpus {
    override fun ler(caminho: String): ByteArray? = File(raiz, caminho).takeIf { it.isFile }?.readBytes()

    override fun listar(): List<String> = raiz.walkTopDown().filter { it.isFile }.map { it.relativeTo(raiz).invariantSeparatorsPath }.sorted().toList()
}

/** O corpus na memória (testes que montam um corpus pequeno ou adulteram um arquivo). */
class MapaFonte(private val arquivos: Map<String, ByteArray>) : FonteDoCorpus {
    override fun ler(caminho: String): ByteArray? = arquivos[caminho]

    override fun listar(): List<String> = arquivos.keys.sorted()
}

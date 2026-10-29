package com.keyflow.bancada.adaptadores

import android.content.res.AssetManager
import com.keyflow.bancada.corpus.FonteDoCorpus
import java.io.IOException

/** O corpus nos assets do APK (`bancada/v1/`): [base] é a pasta da versão. */
class AssetsFonte(private val assets: AssetManager, private val base: String) : FonteDoCorpus {
    override fun ler(caminho: String): ByteArray? = try {
        assets.open("$base/$caminho").use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    override fun listar(): List<String> = listar(base, "").sorted()

    private fun listar(pasta: String, prefixo: String): List<String> {
        val nomes = try {
            assets.list(pasta).orEmpty()
        } catch (e: IOException) {
            emptyArray()
        }
        return nomes.flatMap { nome ->
            val filhos = try {
                assets.list("$pasta/$nome").orEmpty()
            } catch (e: IOException) {
                emptyArray()
            }
            if (filhos.isEmpty()) listOf("$prefixo$nome") else listar("$pasta/$nome", "$prefixo$nome/")
        }
    }
}

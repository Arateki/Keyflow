package com.keyflow.computador

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confere o artefato real e prova que ABI faltante, corrupção e licença proibida são recusadas. */
class TermlibNativoTest {
    private val modulo = File("../third_party/termlib")
    private val aprovado get() = JSONObject(File(modulo, "native-manifest.json").readText())
    private val arquivo get() = File("libs/termlib-jni-${aprovado.getString("version")}.zip")
    private val proibida = Regex("GNU (?:GENERAL|LESSER GENERAL|AFFERO GENERAL) PUBLIC LICENSE|SPDX-License-Identifier:\\s*(?:A?GPL|LGPL)-", RegexOption.IGNORE_CASE)
    private val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64").map { "jni/$it/libtermlib.so" }.toSet()

    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    private fun shaFixado(): String = Regex("val termlibNativoSha256 = \"([a-f0-9]{64})\"")
        .find(File("build.gradle.kts").readText())?.groupValues?.get(1)
        ?: error("O build precisa fixar o SHA-256 real do JNI")

    private fun alinharam16kb(data: ByteArray): Boolean {
        if (data.size < 64 || !data.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70))) return false
        val b = ByteBuffer.wrap(data).order(if (data[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val bits64 = data[4].toInt() == 2
        val inicio = if (bits64) b.getLong(0x20) else b.getInt(0x1c).toLong() and 0xffffffffL
        val tamanho = b.getShort(if (bits64) 0x36 else 0x2a).toInt() and 0xffff
        val numero = b.getShort(if (bits64) 0x38 else 0x2c).toInt() and 0xffff
        if (numero == 0 || tamanho < 32 || inicio < 0 || inicio + numero.toLong() * tamanho > data.size) return false
        var segmentos = 0
        repeat(numero) { indice ->
            val posicao = (inicio + indice.toLong() * tamanho).toInt()
            if (b.getInt(posicao) == 1) {
                segmentos++
                val a = if (bits64) b.getLong(posicao + tamanho - 8) else b.getInt(posicao + tamanho - 4).toLong() and 0xffffffffL
                if (a < 16384) return false
            }
        }
        return segmentos > 0
    }

    private fun validar(zipado: File, shaEsperado: String = shaFixado()): List<String> {
        val erros = mutableListOf<String>()
        if (hash(zipado.readBytes()) != shaEsperado) erros += "SHA do pacote divergente"
        ZipFile(zipado).use { zip ->
            val entradas = zip.entries().asSequence().toList()
            val nativos = entradas.filter { it.name.endsWith(".so") }
            if (nativos.map { it.name }.toSet() != abis) erros += "ABIs diferentes do contrato"
            val hashes = aprovado.getJSONObject("libraries")
            for (e in entradas.filter { it.name.endsWith(".so") || it.name.endsWith(".txt") }) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                if (proibida.containsMatchIn(bytes.toString(Charsets.ISO_8859_1))) erros += "licença proibida: ${e.name}"
                if (e.name.endsWith(".so")) {
                    if (hash(bytes) != hashes.optString(e.name)) erros += "SHA nativo divergente: ${e.name}"
                    if (!alinharam16kb(bytes)) erros += "ELF sem 16 KB: ${e.name}"
                    val texto = bytes.toString(Charsets.ISO_8859_1)
                    if (!texto.contains("nativeMouseMove") || !texto.contains("nativeMouseButton")) erros += "JNI de mouse ausente: ${e.name}"
                }
            }
            val manifest = zip.getEntry("manifest.json")
            if (manifest == null) erros += "manifesto ausente" else {
                val recebido = JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
                if (recebido.getString("source_sha256") != aprovado.getString("source_sha256")) erros += "fonte nativo divergente"
                val licencas = recebido.getJSONObject("licenses")
                val permitidas = setOf("Apache-2.0", "MIT", "permissiva-Markus-Kuhn", "Apache-2.0 WITH LLVM-exception")
                for (parte in licencas.keys()) if (licencas.getString(parte) !in permitidas) erros += "licença não auditada: $parte"
            }
        }
        return erros
    }

    private fun alterado(transformar: (String, ByteArray) -> ByteArray?, conferir: (File) -> Unit) {
        val alvo = File.createTempFile("termlib-mutilado-", ".zip")
        try {
            ZipFile(arquivo).use { fonte ->
                ZipOutputStream(alvo.outputStream()).use { destino ->
                    fonte.entries().asSequence().forEach { e ->
                        val bytes = transformar(e.name, fonte.getInputStream(e).use { it.readBytes() })
                        if (bytes != null) {
                            destino.putNextEntry(ZipEntry(e.name))
                            destino.write(bytes)
                            destino.closeEntry()
                        }
                    }
                }
            }
            conferir(alvo)
        } finally { alvo.delete() }
    }

    @Test fun pacoteRealTemHashesAbisLicencasEMouseConferidos() {
        assertTrue("O build deve preparar o pacote real do CI", arquivo.isFile)
        assertEquals(emptyList<String>(), validar(arquivo))
    }

    @Test fun abiFaltanteReprovaMesmoComHashDoPacoteRecalculado() {
        alterado({ nome, data -> if (nome == "jni/armeabi-v7a/libtermlib.so") null else data }) {
            assertTrue(validar(it, hash(it.readBytes())).any { erro -> erro.contains("ABIs") })
        }
    }

    @Test fun bitAlteradoReprovaHashDoNativo() {
        alterado({ nome, data -> if (nome == "jni/arm64-v8a/libtermlib.so") data.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } else data }) {
            assertTrue(validar(it, hash(it.readBytes())).any { erro -> erro.startsWith("SHA nativo") })
        }
    }

    @Test fun pacoteAlteradoReprovaHashFixadoNoBuild() {
        alterado({ _, data -> data }) {
            assertTrue(validar(it).contains("SHA do pacote divergente"))
        }
    }

    @Test fun textoDeLicencaProibidaReprovaONativo() {
        for (texto in listOf("GNU GENERAL PUBLIC LICENSE", "GNU LESSER GENERAL PUBLIC LICENSE", "GNU AFFERO GENERAL PUBLIC LICENSE")) {
            alterado({ nome, data -> if (nome.endsWith(".so")) data + texto.toByteArray() else data }) {
                assertTrue(texto, validar(it, hash(it.readBytes())).any { erro -> erro.startsWith("licença proibida") })
            }
        }
    }

    @Test fun manifestoCorrespondeAosFontesNativosDaWorktree() {
        val digest = MessageDigest.getInstance("SHA-256")
        File(modulo, "src/main/cpp").walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(modulo).invariantSeparatorsPath.replace('/', '\u0000') }.forEach {
            digest.update(it.relativeTo(modulo).invariantSeparatorsPath.toByteArray())
            digest.update(0.toByte())
            digest.update(it.readBytes())
        }
        assertEquals(aprovado.getString("source_sha256"), digest.digest().joinToString("") { "%02x".format(it) })
    }
}

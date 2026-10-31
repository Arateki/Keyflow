package com.keyflow.skins

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Material dos testes do pacote de skin: SVGs mínimos e `.zip` montados em memória. */
internal object SkinPackTestFixtures {

    const val MANIFEST = """{"schemaVersion":1,"name":"Teste"}"""

    fun svg(extra: String = "", viewBox: String = "0 0 100 100"): String =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="$viewBox">$extra<rect width="100" height="100" fill="#FFFFFF"/></svg>"""

    /** Um `.zip` com as entradas dadas (nome → conteúdo), na ordem. Nomes terminados em `/` são pastas. */
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Como [zip], mais [zeros]: arquivos de `megabytes` MB de zeros, escritos em pedaços (a bomba clássica — 100 MB de zeros cabem
     * em menos de 1 MB comprimidos — sem segurar 100 MB na memória do teste).
     */
    fun zipWithZeros(entries: List<Pair<String, ByteArray>>, zeros: List<Pair<String, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1024 * 1024)
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, megabytes) in zeros) {
                zip.putNextEntry(ZipEntry(name))
                repeat(megabytes) { zip.write(chunk) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun zipOf(vararg entries: Pair<String, String>): ByteArray = zip(entries.map { (n, c) -> n to c.toByteArray(Charsets.UTF_8) })

    /** Um pacote só de orbe, válido: `idle` com [idleFrames] frames. */
    fun orbOnly(idleFrames: Int = 1, manifest: String = MANIFEST): List<Pair<String, ByteArray>> =
        listOf("manifest.json" to manifest.toByteArray()) +
            (1..idleFrames).map { "orb_idle_${it.toString().padStart(2, '0')}.svg" to svg().toByteArray() }

    /** Um pacote só de orbe com os quatro estados (sem nenhum aviso de estado ausente). */
    fun fullOrb(manifest: String = MANIFEST): List<Pair<String, ByteArray>> =
        listOf("manifest.json" to manifest.toByteArray()) +
            OrbPackState.entries.map { "orb_${it.key}_01.svg" to svg().toByteArray() }

    /** Um pacote só de teclado, válido. */
    fun keyboardOnly(manifest: String = MANIFEST): List<Pair<String, ByteArray>> =
        listOf("manifest.json" to manifest.toByteArray(), "keyboard_key_normal.svg" to svg().toByteArray())

    /** Um pacote só de notificação, válido: os [estados] pedidos (o `idle` é obrigatório). */
    fun notificationOnly(manifest: String = MANIFEST, estados: List<String> = listOf("idle")): List<Pair<String, ByteArray>> =
        listOf("manifest.json" to manifest.toByteArray()) + estados.map { "notification_$it.svg" to svg().toByteArray() }

    /**
     * A parte dos cabeçalhos locais de um `.zip` (tudo antes do diretório central): o `ZipInputStream` lê só isso, então juntar
     * duas destas fabrica um zip com a mesma entrada duas vezes — coisa que o `ZipOutputStream` se recusa a escrever.
     */
    fun localPart(zip: ByteArray): ByteArray {
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        for (i in 0..zip.size - 4) {
            if (zip[i] == signature[0] && zip[i + 1] == signature[1] && zip[i + 2] == signature[2] && zip[i + 3] == signature[3]) {
                return zip.copyOf(i)
            }
        }
        error("sem diretório central")
    }

    /** O registro de fim de um `.zip` vazio: com ele, as partes locais coladas viram um arquivo "completo" para o leitor. */
    val EMPTY_END = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)

    fun read(zip: ByteArray): SkinPackReadResult = SkinPackReader.read(zip.inputStream())

    /** Quantos bytes do `.zip` o leitor puxou? Prova que uma bomba foi abortada cedo, sem ler o arquivo inteiro. */
    class CountingStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) count += it }
    }
}

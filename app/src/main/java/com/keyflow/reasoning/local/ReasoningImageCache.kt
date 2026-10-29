package com.keyflow.reasoning.local

import com.keyflow.reasoning.ReasoningImage
import java.io.ByteArrayOutputStream
import java.io.File

/** PNG privado e efêmero: o binder só recebe o caminho. O cliente apaga no sucesso, falha, morte ou cancelamento. */
internal object ReasoningImageCache {
    fun write(directory: File, image: ReasoningImage): File {
        directory.mkdirs()
        val file = File.createTempFile("pedido-", ".png", directory)
        try { file.writeBytes(image.bytes); return file }
        catch (t: Throwable) { file.delete(); throw t }
    }

    /** Só aceita arquivos desta pasta; teto conferido antes e durante a leitura. Chamada na fila, depois da guarda de RAM. */
    fun read(directory: File, path: String): ReasoningImage {
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.isFile) { "imagem fora do cache privado" }
        require(file.length() in 1..ReasoningImage.MAX_BYTES.toLong()) { "imagem fora do limite" }
        val bytes = file.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ReasoningImage.MAX_BYTES) { "imagem fora do limite" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        return ReasoningImage(bytes)
    }
}

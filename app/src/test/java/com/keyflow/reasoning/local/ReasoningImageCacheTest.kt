package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.ReasoningImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReasoningImageCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `imagem atravessa cache privado sem bytes no diagnóstico`() {
        val directory = temp.newFolder("imagens")
        val original = ReasoningImage(byteArrayOf(1, 2, 3))
        val file = ReasoningImageCache.write(directory, original)
        try {
            assertThat(ReasoningImageCache.read(directory, file.path).bytes).isEqualTo(original.bytes)
            assertThat(original.toString()).doesNotContain(original.base64())
        } finally { file.delete() }
        assertThat(directory.listFiles()).isEmpty()
    }
    @Test fun `rejeita caminho fora da pasta e arquivo maior que o teto`() {
        val directory = temp.newFolder("imagens")
        val outside = temp.newFile("fora.png").also { it.writeBytes(byteArrayOf(1)) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ReasoningImageCache.read(directory, outside.path) }
        val large = File(directory, "grande.png")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(ReasoningImage.MAX_BYTES.toLong() + 1) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ReasoningImageCache.read(directory, large.path) }
    }
}

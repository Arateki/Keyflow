package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** NM-T7: vetores do FIPS 180-2 e um arquivo maior que o bloco de leitura. */
class Sha256Test {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `known vectors`() {
        val empty = tmp.newFile().apply { writeBytes(ByteArray(0)) }
        val abc = tmp.newFile().apply { writeText("abc") }

        assertThat(Sha256.hexOf(empty)).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(Sha256.hexOf(abc)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `a file spanning several read blocks hashes like the whole content`() {
        val bytes = ByteArray(3 * (1 shl 20) + 123) { (it * 31).toByte() }
        val file = tmp.newFile().apply { writeBytes(bytes) }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertThat(Sha256.hexOf(file)).isEqualTo(expected)
    }
}

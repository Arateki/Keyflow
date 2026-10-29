package com.keyflow.speech.frontend

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A ICU do Android não aceita todos os flags de regex da JVM: o frontend roda também no gate do aparelho. */
@RunWith(AndroidJUnit4::class)
class SupertonicTextDeviceTest {
    @Test fun unicodePtEWhitespaceFuncionamNaIcuDoAndroid() {
        val frontend = SupertonicText(IntArray(65536) { it })
        assertArrayEquals("<pt>Ola\u0301, voz!</pt>".map { it.code.toLong() }.toLongArray(), frontend.ids("Olá,  voz! 😀"))
        assertEquals("<pt>voz nova.</pt>", SupertonicText.normalize("voz\u0085nova"))
        assertEquals(listOf("Olá.", "Sr. Silva!"), SupertonicText.chunks("Olá. Sr. Silva!", 10))
    }
}

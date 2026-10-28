package com.keyflow.reasoning.local

import com.keyflow.core.ComputeBackend
import java.io.File

/**
 * Onde o aparelho consegue rodar um modelo de texto local: a CPU sempre; a GPU quando existe a `libOpenCL.so` do fabricante.
 *
 * **Como detecta a GPU** — só a existência do arquivo, nos lugares onde o Android a instala em 64 bits (`/vendor/lib64`,
 * `/system/vendor/lib64`, `/system/lib64`; o LiteRT-LM só publica arm64-v8a e x86_64, então não há `lib/` de 32 bits). O LiteRT-LM
 * abre a biblioteca por esse nome (`dlopen("libOpenCL.so")`, visto nas strings da `liblitertlm_jni.so` 0.16.1) e o manifesto declara
 * `uses-native-library libOpenCL.so` para o app enxergá-la no Android 12+.
 *
 * Por que não tentar carregar a biblioteca para ter certeza: `System.load` roda código do fabricante no processo do teclado (um
 * driver ruim derrubaria o IME e a orbe), e o `:reasoning` só responde pelo AIDL, depois da escolha. A existência do arquivo não
 * executa nada, custa um `stat`, e o seu erro (o aparelho tem o arquivo mas o driver não serve) já tem rede: a carga na GPU que
 * falha volta sozinha para a CPU e avisa no card (`ReasoningServiceCore`). O contrário (aparelho com GPU boa e sem o arquivo nesses
 * caminhos) só deixa de oferecer a GPU, sem prejuízo para a CPU.
 *
 * O resultado não muda enquanto o aparelho está ligado: é medido uma vez por instância.
 */
class ComputeSupport(private val fileExists: (String) -> Boolean = { path -> runCatching { File(path).exists() }.getOrDefault(false) }) {

    /** Há `libOpenCL.so` visível ao app neste aparelho. */
    val gpuAvailable: Boolean by lazy { OPENCL_LIBRARIES.any(fileExists) }

    /** Os backends que o seletor pode oferecer, na ordem do seletor: sempre a CPU, e a GPU quando [gpuAvailable]. */
    val backends: List<ComputeBackend> get() = backendsFor(gpuAvailable)

    companion object {
        val OPENCL_LIBRARIES = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
        )

        /** Uma por processo: o arquivo não aparece nem some com o app aberto. */
        val device: ComputeSupport by lazy { ComputeSupport() }

        fun backendsFor(gpuAvailable: Boolean): List<ComputeBackend> =
            if (gpuAvailable) listOf(ComputeBackend.CPU, ComputeBackend.GPU) else listOf(ComputeBackend.CPU)
    }
}

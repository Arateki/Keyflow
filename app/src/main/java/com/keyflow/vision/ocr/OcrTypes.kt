package com.keyflow.vision.ocr

import java.io.File

/**
 * Imagem em bytes RGBA, linha a linha, 4 bytes por pixel (R, G, B, A) — a memória de um `Bitmap` ARGB_8888 do Android e o formato do
 * arquivo cru que vai do processo principal ao `:vision` (design §6.1). Sem `android.graphics`: as etapas do OCR rodam na JVM.
 */
class RgbaImage(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0) { "imagem vazia: ${width}x$height" }
        require(rgba.size.toLong() == width.toLong() * height * 4) {
            "${rgba.size} bytes não são uma imagem RGBA ${width}x$height (${width.toLong() * height * 4})"
        }
    }

    fun writeRaw(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(rgba)
    }

    companion object {
        /**
         * Teto de pixels de um recorte (12 MP = 48 MB de RGBA: cobre uma tela 4K, 8,3 MP). O arquivo cru vem de fora do processo
         * (`:vision` recebe só o caminho e o tamanho): sem teto, um tamanho declarado absurdo faria o `readBytes` alocar tudo antes de
         * qualquer guarda de RAM.
         */
        const val MAX_PIXELS = 12_000_000L

        /**
         * Confere o arquivo cru SEM lê-lo: tamanho declarado dentro do teto e `file.length()` igual a largura x altura x 4. Devolve o
         * número de bytes esperado. Entrada ruim é `IllegalArgumentException` (o serviço a devolve como `BAD_INPUT`).
         */
        fun checkRaw(file: File, width: Int, height: Int): Long {
            require(width > 0 && height > 0) { "imagem vazia: ${width}x$height" }
            val pixels = width.toLong() * height
            require(pixels <= MAX_PIXELS) { "recorte de ${width}x$height (${pixels / 1_000_000} MP) passa do teto de ${MAX_PIXELS / 1_000_000} MP" }
            val expected = pixels * 4
            require(file.length() == expected) { "${file.name}: ${file.length()} bytes, esperava $expected para ${width}x$height RGBA" }
            return expected
        }

        /** Lê o arquivo cru que o processo principal escreveu, depois de [checkRaw] (teto e tamanho exato). */
        fun readRaw(file: File, width: Int, height: Int): RgbaImage {
            checkRaw(file, width, height)
            return RgbaImage(width, height, file.readBytes())
        }
    }
}

/** Caixa de texto, em pixels da imagem, com [x1]/[y1] exclusivos. [score] = confiança do detector. */
data class TextBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float = 1f) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val area: Long get() = width.toLong() * height

    /** Área da interseção com [other] (0 se disjuntas). */
    fun intersection(other: TextBox): Long {
        val w = minOf(x1, other.x1) - maxOf(x0, other.x0)
        val h = minOf(y1, other.y1) - maxOf(y0, other.y0)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }
}

/** Um trecho reconhecido: a caixa e o texto lido (com a confiança do reconhecedor). */
data class RecognizedBox(val box: TextBox, val text: String, val score: Float)

/** Por que um OCR falhou — o aviso da UI escolhe o texto por aqui. Códigos estáveis (atravessam o AIDL). */
enum class OcrFailure(val code: Int) {
    /** Sem memória: guarda de RAM antes de carregar ou `OutOfMemoryError` no caminho (MDL-04). */
    OOM(1),

    /** O runtime ou os modelos falharam (arquivo corrompido, forma que o modelo recusa, ONNX Runtime com erro). */
    ENGINE(2),

    /** O recorte não é uma imagem RGBA do tamanho declarado. */
    BAD_INPUT(3),

    /** O processo `:vision` morreu no meio (crash nativo, morto pelo sistema). */
    PROCESS_DIED(4),

    /** Cancelado por quem pediu. */
    CANCELLED(5),

    /** Não ligou ao `:vision` ou não respondeu a tempo. */
    TIMEOUT(6),
    ;

    companion object {
        fun fromCode(code: Int): OcrFailure = entries.firstOrNull { it.code == code } ?: ENGINE
    }
}

/** O resultado do OCR (design §6): texto lido, nada de texto, motor/modelo indisponível, ou falha com causa. */
sealed interface OcrResult {
    data class Text(val text: String) : OcrResult

    /** A imagem não tem texto legível (OCR-02). */
    data object Empty : OcrResult

    /** Sem ONNX Runtime ou sem os arquivos do modelo: nunca é crash — a UI avisa e aponta para a aba Visão (OCR-04). */
    data object Unavailable : OcrResult

    data class Failed(val kind: OcrFailure, val message: String = "") : OcrResult
}

/** Medidas de uma execução (para o log e para a medição da VC-T6; o produto não depende delas). */
data class OcrStats(
    val loadMs: Long = 0,
    val detectMs: Long = 0,
    val recognizeMs: Long = 0,
    val totalMs: Long = 0,
    val strips: Int = 0,
    val boxes: Int = 0,
    val lines: Int = 0,
    /** Pico de RSS do processo (VmHWM, kB) lido pelo serviço depois da execução; 0 quando não medido. */
    val peakRssKb: Long = 0,
)

/**
 * O motor de OCR dentro do processo que o hospeda (`:vision`). Bloqueante: o chamador não é a thread principal.
 *
 * Cancelamento POR PEDIDO: [cancelled] é o token deste [recognize] (quem chama passa uma função que olha a ficha dele), e o motor o
 * consulta entre as etapas — antes de carregar, entre as faixas do detector e entre as caixas do reconhecedor — e devolve
 * `Failed(CANCELLED)` assim que ele vale. Um flag do motor zerado no início de cada chamada apagava o cancelamento que chegou entre a
 * conferência de quem chama e o início do trabalho (revisão do PR #48). Uma inferência já em andamento termina (a ORT não é
 * interrompível); o que o token evita é a próxima.
 */
interface OcrEngine : AutoCloseable {
    fun recognize(image: RgbaImage, cancelled: () -> Boolean = { false }): OcrResult

    /** Medidas da última execução de [recognize]. */
    val lastStats: OcrStats get() = OcrStats()
}

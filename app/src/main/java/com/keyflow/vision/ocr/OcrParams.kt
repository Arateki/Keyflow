package com.keyflow.vision.ocr

/**
 * Normalização de entrada de um modelo: ordem dos canais e, por canal de saída, `(v/255 - mean) / std`.
 *
 * Duas convenções disputam o PaddleOCR (design §6.2, "não presumir"): o código de referência lê a imagem com o OpenCV (BGR) e aplica
 * a média/desvio do ImageNet (que são de RGB) no detector e `(v/255 - 0,5)/0,5` no reconhecedor; o `config.json` do repositório
 * `monkt/paddleocr-onnx` descreve "RGB, 0–1". A escolha de cada modelo sai da medição de CER (STATE.md › VC-T6), por isso é parâmetro.
 */
class Normalization(val name: String, val bgr: Boolean, val mean: FloatArray, val std: FloatArray) {
    init {
        require(mean.size == 3 && std.size == 3) { "média e desvio têm 3 canais" }
    }

    override fun toString(): String = name

    companion object {
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val HALF = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val ZERO = floatArrayOf(0f, 0f, 0f)
        private val ONE = floatArrayOf(1f, 1f, 1f)

        /** Referência do PaddleOCR para o detector: imagem BGR + média/desvio do ImageNet. */
        val IMAGENET_BGR = Normalization("imagenet-bgr", true, IMAGENET_MEAN, IMAGENET_STD)
        val IMAGENET_RGB = Normalization("imagenet-rgb", false, IMAGENET_MEAN, IMAGENET_STD)

        /** Referência do PaddleOCR para o reconhecedor: imagem BGR, `(v/255 - 0,5) / 0,5` (faixa −1..1). */
        val HALF_BGR = Normalization("half-bgr", true, HALF, HALF)
        val HALF_RGB = Normalization("half-rgb", false, HALF, HALF)

        /** "RGB, 0–1" do `config.json`. */
        val UNIT_RGB = Normalization("unit-rgb", false, ZERO, ONE)

        val DETECTOR_CANDIDATES = listOf(IMAGENET_BGR, IMAGENET_RGB, UNIT_RGB)
        val RECOGNIZER_CANDIDATES = listOf(HALF_BGR, HALF_RGB, UNIT_RGB)

        /** Aplica a normalização aos três planos (CHW, valores 0..255, na ordem de canais que o `Resampler` já entregou). */
        fun apply(planes: FloatArray, plane: Int, norm: Normalization) {
            for (c in 0 until 3) {
                val mean = norm.mean[c]
                val inv = 1f / (255f * norm.std[c])
                val shift = mean / norm.std[c]
                val base = c * plane
                for (i in base until base + plane) planes[i] = planes[i] * inv - shift
            }
        }
    }
}

/**
 * Tudo que se pode ajustar no pipeline do PaddleOCR. Os padrões saem da medição da VC-T6 (STATE: `.specs/project/licoes/2026-10-28-ocr-paddle-medicao.md`) sobre o código de referência do
 * PaddleOCR (detector DB: limiar 0,3, caixa 0,6; reconhecedor: altura 48, descarte abaixo de 0,5): `unclip` 2,0, lote 1, arena ligada.
 */
data class OcrParams(
    // Faixas do detector (design §6.2): a tela alta vira faixas, não é encolhida.
    /** Comprimento de uma faixa no eixo maior da imagem, em px da imagem já redimensionada. */
    val stripLength: Int = 960,
    /** Sobreposição entre faixas vizinhas, em px da imagem redimensionada. */
    val stripOverlap: Int = 64,
    /** Lado menor máximo sem reduzir (1080 de largura passa inteiro, 1088 com o arredondamento para 32); acima disso a imagem é reduzida. */
    val crossLimit: Int = 1088,
    // Detector.
    val detNormalization: Normalization = Normalization.IMAGENET_BGR,
    val binaryThreshold: Float = 0.3f,
    val boxThreshold: Float = 0.6f,
    /** 2,0: o `unclip` do DBPostProcess do PaddleOCR; menor CER que o 1,5 do config do PP-OCRv3 no corpus (0,0020 contra 0,0028) e caixa mais alta, logo menos ampliação (e menos largura) no reconhecedor. */
    val unclipRatio: Float = 2.0f,
    /** Menor lado de caixa aceito no mapa de probabilidade (px do mapa); depois do `unclip` vale este + 2, como no PaddleOCR. */
    val minBoxSide: Int = 3,
    val maxBoxes: Int = 1000,
    // Reconhecedor.
    val recHeight: Int = 48,
    val recMaxWidth: Int = 3200,
    val recMinWidth: Int = 32,
    /** Caixas por chamada do reconhecedor. 1: o lote não ganha tempo por pixel (medido: 0,27–0,33 ms/px em lote ou não) e o preenchimento à direita e as ativações x6 custam RAM. */
    val recBatch: Int = 1,
    val recNormalization: Normalization = Normalization.HALF_BGR,
    val minRecScore: Float = 0.5f,
    /** Threads de cálculo da ORT por sessão. */
    val threads: Int = 2,
    /** Alocador em arena da ORT: 2 a 3 vezes mais rápido no reconhecedor (medido) e ~+160 MB de RSS; descarregar o motor devolve tudo. */
    val cpuArena: Boolean = true,
) {
    val onnxOptions: com.keyflow.onnx.OnnxOptions get() = com.keyflow.onnx.OnnxOptions(threads = threads, cpuArena = cpuArena)

    init {
        require(stripLength >= 64 && stripOverlap in 0 until stripLength / 2) { "faixa inválida: $stripLength/$stripOverlap" }
        require(crossLimit >= 64 && recHeight >= 8 && recBatch >= 1 && threads >= 1)
    }
}

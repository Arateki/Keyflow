package com.keyflow.vision.client

import android.content.Context
import com.keyflow.vision.ocr.RgbaImage
import java.io.File

/**
 * `cacheDir/vision/`: onde o recorte vai do processo principal ao `:vision` (arquivo RGBA cru, não o binder). Some no epílogo do
 * fluxo (CAP-04, STO-V01) — [clear] é o que o epílogo chama; nada de captura fica em disco depois do fluxo.
 */
object VisionCache {
    private const val DIR = "vision"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** Grava o recorte e devolve o arquivo (o chamador passa o caminho e o tamanho ao AIDL). */
    fun writeCrop(context: Context, image: RgbaImage): File {
        val dir = dir(context).apply { mkdirs() }
        val file = File.createTempFile("crop-", ".rgba", dir)
        image.writeRaw(file)
        return file
    }

    /** Apaga tudo (epílogo do fluxo). Devolve quantos arquivos saíram. */
    fun clear(context: Context): Int {
        val files = dir(context).listFiles().orEmpty()
        var removed = 0
        for (f in files) if (f.deleteRecursively()) removed++
        return removed
    }
}

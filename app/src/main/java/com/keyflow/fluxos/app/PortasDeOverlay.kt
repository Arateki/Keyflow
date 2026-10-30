package com.keyflow.fluxos.app

import android.graphics.Bitmap
import com.keyflow.data.Config
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloAplicado
import com.keyflow.fluxos.ModeloPorEtapa
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.DestinoFluxo
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.fluxos.execucao.ErrosDeFluxo
import com.keyflow.fluxos.execucao.ImagemHandle
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.ResultadoDitado
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A imagem que o fluxo carrega: o `Bitmap` da captura. [liberar] recicla (e pode ser chamado mais de uma vez). */
class ImagemBitmap(val bitmap: Bitmap) : ImagemHandle {
    override fun liberar() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * Ditar (FLX-18): pede ao `OverlayCoordinator` (via [iniciar]) uma gravação cujo texto volta a esta etapa em vez de ir ao campo.
 * O modelo da etapa vira a [Config] da transcrição só quando a etapa escolheu um (`null` = a aba, pelo `configProvider` do
 * coordinator). [abandonar] descarta a gravação se a etapa for cancelada por fora (cancelar o fluxo, o serviço indo embora).
 */
class PortaDitarOverlay(
    private val settings: suspend () -> Settings,
    private val configDe: suspend (Settings) -> Config,
    private val iniciar: (DestinoFluxo) -> Boolean,
    private val abandonar: () -> Unit,
) : PortaEtapa {
    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val config: (suspend () -> Config)? = if (etapa.modelo == null) {
            null
        } else {
            when (val a = ModeloPorEtapa.aplicar(settings(), etapa)) {
                is ModeloAplicado.Invalido -> return ResultadoEtapa.Falha(ErrosDeFluxo.modeloInvalido(DestinoAba.TRANSCRICAO))
                is ModeloAplicado.Ok -> ({ configDe(a.settings) })
            }
        }
        val resultado = CompletableDeferred<ResultadoDitado>()
        if (!iniciar(DestinoFluxo(config) { resultado.complete(it) })) {
            return ResultadoEtapa.Falha(ErroEtapa(CausaErro.MOTOR, tr("Termine a gravação atual antes de rodar o fluxo.")))
        }
        val r = try {
            resultado.await()
        } catch (e: CancellationException) {
            abandonar()
            throw e
        }
        return when (r) {
            is ResultadoDitado.Texto -> if (r.texto.isBlank()) ResultadoEtapa.Falha(ErrosDeFluxo.deTranscricao(com.keyflow.core.ErrorKind.EMPTY_AUDIO, ""))
                else ResultadoEtapa.Ok(Valor.Texto(r.texto))
            is ResultadoDitado.Falha -> ResultadoEtapa.Falha(r.erro)
            ResultadoDitado.Cancelado -> ResultadoEtapa.Cancelada
        }
    }
}

/**
 * Capturar (FLX-19): abre a captura de região do `VisionFlow` com este fluxo como dono do resultado. [iniciar] recebe a entrega e
 * devolve `false` se o `VisionFlow` recusou (já há captura, gravação em curso…); a entrega traz o valor capturado — que passa a ser
 * desta porta e vira [Valor.Imagem] — ou `null` (negou o consentimento, ✕ do seletor, falha): a etapa termina `Cancelada`, sem aviso.
 * Cancelar a coroutine fecha a captura ([cancelar]); uma imagem que chegar depois do cancelamento é liberada aqui mesmo.
 */
class PortaCapturarOverlay<T : Any>(
    private val iniciar: ((T?) -> Unit) -> Boolean,
    private val cancelar: () -> Unit,
    private val embrulhar: (T) -> ImagemHandle,
) : PortaEtapa {
    private sealed interface Saida<out T> {
        class Entregue<T>(val valor: T?) : Saida<T>

        data object Recusada : Saida<Nothing>
    }

    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val saida = suspendCancellableCoroutine<Saida<T>> { cont ->
            val aceitou = iniciar { valor ->
                if (cont.isActive) cont.resume(Saida.Entregue(valor))
                else if (valor != null) embrulhar(valor).liberar() // chegou depois do cancelamento: ninguém vai recebê-la
            }
            if (!aceitou) {
                if (cont.isActive) cont.resume(Saida.Recusada)
            } else {
                cont.invokeOnCancellation { cancelar() }
            }
        }
        return when (saida) {
            Saida.Recusada -> ResultadoEtapa.Falha(ErroEtapa(CausaErro.MOTOR, tr("Não foi possível iniciar a captura agora.")))
            is Saida.Entregue -> saida.valor?.let { ResultadoEtapa.Ok(Valor.Imagem(embrulhar(it))) } ?: ResultadoEtapa.Cancelada
        }
    }
}

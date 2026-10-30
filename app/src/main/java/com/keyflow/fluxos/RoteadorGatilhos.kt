package com.keyflow.fluxos

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import com.keyflow.orbe.LugarDaOrbe

/**
 * O que um lugar da orbe sabe dizer ao roteador sobre si (FLX-34, FLX-58..60). Um só tipo para os três lugares (A14).
 *
 * @property overflow sempre o `Settings.shortcutOverflow` da orbe principal (mais de 3 toques).
 * @property copiaDaPrincipal "copiar a configuração da orbe principal" ligado: os gatilhos lidos são os da flutuante. Sempre
 *   `false` na própria flutuante. Os gatilhos próprios do lugar não são lidos nem apagados enquanto se copia (A16).
 * @property temAcaoPropria a ação do lugar para `n` toques é diferente de "Nenhuma" (conta como gesto ativo no overflow).
 * @property rotuloDaAcaoPropria o nome da ação do lugar para `n` toques, ou `null` se não há (para o aviso de substituição).
 *   Quem monta a política passa o rótulo da tela (`shortcutActionLabel`); o pacote de fluxos não conhece a interface.
 */
data class PoliticaDoLugar(
    val overflow: ShortcutOverflow,
    val copiaDaPrincipal: Boolean,
    val temAcaoPropria: (Int) -> Boolean,
    val rotuloDaAcaoPropria: (Int) -> String? = { null },
) {
    companion object {
        /** A orbe flutuante: a ação própria é a do `Settings` (`shortcut2Tap`/`shortcut3Tap`). */
        fun daFlutuante(settings: Settings, rotuloDaAcao: (OrbShortcutAction) -> String = { it.name }): PoliticaDoLugar =
            PoliticaDoLugar(
                overflow = settings.shortcutOverflow,
                copiaDaPrincipal = false,
                temAcaoPropria = { settings.shortcutForTaps(it) != OrbShortcutAction.NONE },
                rotuloDaAcaoPropria = { n -> settings.shortcutForTaps(n).takeIf { it != OrbShortcutAction.NONE }?.let(rotuloDaAcao) },
            )
    }
}

/** O que fazer com uma rajada de toques da orbe (o `when` do `OverlayService` e da notificação decide a partir daqui). */
sealed interface Resolucao {
    /** O gesto está ligado a um fluxo válido: roda o fluxo e **não** a ação por baixo. [toques] é o gesto efetivo (2 ou 3). */
    data class Rodar(val fluxo: Fluxo, val toques: Int) : Resolucao

    /** O gesto está ligado a um fluxo inválido ou que sumiu: avisa "Fluxo incompleto" e a ação por baixo **não** roda (FLX-38). */
    data class Incompleto(val toques: Int) : Resolucao

    /** Sem gatilho na orbe flutuante: executa esta ação do `Settings` (pode ser [OrbShortcutAction.NONE]). */
    data class AcaoDoSettings(val acao: OrbShortcutAction) : Resolucao

    /** Sem gatilho na notificação ou no teclado: o lugar executa a ação própria dele para [toques] toques. */
    data class AcaoDoLugar(val toques: Int) : Resolucao

    /** Nada a fazer (mais toques do que o suportado com `CANCEL`, ou nenhum gesto ativo para cair). */
    data object Nada : Resolucao
}

/** O que o gesto faz agora, para as telas que o descrevem (Sobrepor, Tour). */
sealed interface AcaoEfetiva {
    /** O gesto faz a ação configurada no `Settings`. */
    data class DaConfig(val acao: OrbShortcutAction) : AcaoEfetiva

    /** O gesto está ligado a um fluxo; [nome] é nulo se o fluxo sumiu. A ação do `Settings` está sombreada. */
    data class DeFluxo(val fluxoId: String, val nome: String?, val valido: Boolean) : AcaoEfetiva
}

/**
 * Roteamento gatilho → fluxo (FLX-33..35, FLX-57..60), puro: o mesmo para a orbe flutuante, a do teclado e a da notificação.
 * O gatilho **sombreia** a ação (D12): nada aqui lê a ação para trocá-la, só se há gatilho antes dela.
 */
object RoteadorGatilhos {

    /**
     * Resolve uma rajada de [toques] no [lugar].
     * 1. Lugar dos gatilhos: o próprio, ou a flutuante se o lugar copia a principal (FLX-60).
     * 2. 1 toque (ou menos) **nunca** é gatilho: vai à ação própria. Mais de 3 toques: `CANCEL` → [Resolucao.Nada];
     *    `NEAREST_DOWN` → o maior de 3 e 2 que tenha gatilho (válido ou não) ou ação própria; nenhum → [Resolucao.Nada].
     * 3. Gatilho em `Orbe(lugarDosGatilhos, n)`: fluxo válido → [Resolucao.Rodar]; inválido ou sumido → [Resolucao.Incompleto].
     * 4. Senão a ação do lugar: [Resolucao.AcaoDoSettings] na flutuante, [Resolucao.AcaoDoLugar] nos outros.
     */
    fun resolverToques(
        lugar: LugarDaOrbe,
        toques: Int,
        config: ConfigFluxos,
        politica: PoliticaDoLugar,
        settings: Settings,
    ): Resolucao {
        val lugarDosGatilhos = if (lugar != LugarDaOrbe.FLUTUANTE && politica.copiaDaPrincipal) LugarDaOrbe.FLUTUANTE else lugar
        fun gatilho(n: Int) = config.gatilho(Superficie.Orbe(lugarDosGatilhos, n))

        val n = when {
            toques < 2 -> return acaoDoLugar(lugar, toques, settings)
            toques in Superficie.Orbe.TOQUES -> toques
            else -> when (politica.overflow) {
                ShortcutOverflow.CANCEL -> return Resolucao.Nada
                ShortcutOverflow.NEAREST_DOWN ->
                    Superficie.Orbe.TOQUES.reversed().firstOrNull { gatilho(it) != null || politica.temAcaoPropria(it) }
                        ?: return Resolucao.Nada
            }
        }

        val ligado = gatilho(n) ?: return acaoDoLugar(lugar, n, settings)
        val fluxo = config.fluxo(ligado.fluxoId)
        return if (fluxo != null && ValidadorFluxo.validar(fluxo).valido) Resolucao.Rodar(fluxo, n) else Resolucao.Incompleto(n)
    }

    /**
     * A ação efetiva de um gesto da orbe **flutuante** para as telas que a descrevem (aba Sobrepor, cartões de modo, tour): o
     * fluxo ligado, se há gatilho; senão a ação do `Settings`. Só 2 e 3 toques podem ter gatilho.
     */
    fun acaoEfetivaDoGesto(toques: Int, settings: Settings, config: ConfigFluxos): AcaoEfetiva {
        val ligado = (if (toques in Superficie.Orbe.TOQUES) config.gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, toques)) else null)
            ?: return AcaoEfetiva.DaConfig(settings.shortcutForTaps(toques))
        val fluxo = config.fluxo(ligado.fluxoId)
        return AcaoEfetiva.DeFluxo(ligado.fluxoId, fluxo?.nome, valido = fluxo != null && ValidadorFluxo.validar(fluxo).valido)
    }

    private fun acaoDoLugar(lugar: LugarDaOrbe, toques: Int, settings: Settings): Resolucao = when (lugar) {
        // Na flutuante a ação é a do `Settings`; 0 ou menos toques não têm ação (`shortcutForTaps` devolve NONE).
        LugarDaOrbe.FLUTUANTE -> Resolucao.AcaoDoSettings(settings.shortcutForTaps(toques))
        LugarDaOrbe.TECLADO, LugarDaOrbe.NOTIFICACAO -> Resolucao.AcaoDoLugar(toques)
    }
}

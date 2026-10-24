package com.keyflow.ui.config

import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons

/**
 * O grupo de uma aba no menu lateral (Yan, 2026-09-29): as **interfaces** são por onde se usa o KeyFlow (o teclado, a orbe
 * sobreposta, a notificação); as **capacidades** são o que ele sabe fazer (transcrever, ver, raciocinar, falar). A Docs
 * não é de nenhum dos dois.
 */
enum class TabGroup(private val titlePt: String?) {
    PRINCIPAL(null),
    INTERFACES(trKey("Interfaces")),
    CAPACIDADES(trKey("Capacidades")),
    OUTROS(null),
    ;

    /** O título do grupo no idioma da interface (só aparece com o menu aberto); `null` = grupo sem título. */
    val title: String? get() = titlePt?.let { tr(it) }
}

/**
 * As abas da configuração (CFG-01), na ordem do menu lateral: primeiro as **interfaces** (Teclado, Sobrepor,
 * Notificação), depois as **capacidades** (Transcrição, Visão, Raciocínio, Voz) e a Docs. Os ícones são do conjunto
 * próprio ([KfIcons]) — o mesmo traço dos botões da barra do teclado. Visão, Voz, Fluxos, Computador e Notificação
 * ficam explícitas como "Em breve" até seus motores existirem. A aba Modo saiu (Yan, 2026-09-28): o cartão de cada
 * modo abre a aba da própria interface.
 */
enum class ConfigTab(private val labelPt: String, private val icon: KfIconSpec, val group: TabGroup) {
    // ── Agente Principal
    KEYFLOW("KeyFlow", KfIcons.TabKeyflow, TabGroup.PRINCIPAL),

    // ── Interfaces
    TECLADO(trKey("Teclado"), KfIcons.TabTeclado, TabGroup.INTERFACES),
    OVERLAY(trKey("Sobrepor"), KfIcons.TabSobrepor, TabGroup.INTERFACES),
    NOTIFICACAO(trKey("Notificação"), KfIcons.TabNotificacao, TabGroup.INTERFACES),

    // ── Capacidades. Fluxos é a central de orquestração e conexões, vindo em primeiro lugar.
    FLUXOS(trKey("Fluxos"), KfIcons.TabFluxos, TabGroup.CAPACIDADES),
    VOZ(trKey("Transcrição"), KfIcons.TabVoz, TabGroup.CAPACIDADES),
    VISAO(trKey("Visão"), KfIcons.TabVisao, TabGroup.CAPACIDADES),
    RACIOCINIO(trKey("Raciocínio"), KfIcons.TabRaciocinio, TabGroup.CAPACIDADES),
    FALA(trKey("Voz"), KfIcons.TabBoca, TabGroup.CAPACIDADES),
    COMPUTADOR(trKey("Computador"), KfIcons.TabComputador, TabGroup.CAPACIDADES),

    // ── Outros
    PRIVACIDADE(trKey("Privacidade"), KfIcons.Lock, TabGroup.OUTROS),
    DOCS("Docs", KfIcons.TabDocs, TabGroup.OUTROS),
    ;

    /** Nome da aba no idioma da interface. */
    val label: String get() = tr(labelPt)

    @Composable
    fun Icon(tint: Color, modifier: Modifier = Modifier) {
        KfIcon(icon, tint, modifier)
    }
}

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
 * As abas da configuração (CFG-01). Os ícones são do conjunto próprio ([KfIcons]) — o mesmo traço dos
 * botões da barra do teclado. Visão, Raciocínio e Voz ainda não têm conteúdo. A aba Modo saiu (Yan, 2026-09-28): o cartão de cada modo abre a aba da
 * própria interface (Teclado, Sobrepor).
 */
enum class ConfigTab(private val labelPt: String, private val icon: KfIconSpec) {
    VOZ(trKey("Transcrição"), KfIcons.TabVoz),
    // Abas novas, ao lado da Transcrição e ainda sem conteúdo (Yan, 2026-09-29).
    VISAO(trKey("Visão"), KfIcons.TabVisao),
    RACIOCINIO(trKey("Raciocínio"), KfIcons.TabRaciocinio),
    // A aba "Voz" (boca), depois de Raciocínio, também vazia. O nome interno é FALA porque [VOZ] é, desde o
    // começo, a aba Transcrição — renomear essa mexeria em intents, tags e testes sem ganho.
    FALA(trKey("Voz"), KfIcons.TabBoca),
    TECLADO(trKey("Teclado"), KfIcons.TabTeclado),
    OVERLAY(trKey("Sobrepor"), KfIcons.TabSobrepor),
    DOCS("Docs", KfIcons.TabDocs);

    /** Nome da aba no idioma da interface. */
    val label: String get() = tr(labelPt)

    @Composable
    fun Icon(tint: Color, modifier: Modifier = Modifier) {
        KfIcon(icon, tint, modifier)
    }
}

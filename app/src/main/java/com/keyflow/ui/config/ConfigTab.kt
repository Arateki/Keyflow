package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons

/**
 * As 5 abas da configuração (CFG-01). Os ícones são do conjunto próprio ([KfIcons]) — o mesmo traço dos
 * botões da barra do teclado.
 */
enum class ConfigTab(val label: String, private val icon: KfIconSpec) {
    MODO("Modo", KfIcons.TabModo),
    VOZ("Voz", KfIcons.TabVoz),
    TECLADO("Teclado", KfIcons.TabTeclado),
    OVERLAY("Sobrepor", KfIcons.TabSobrepor),
    DOCS("Docs", KfIcons.TabDocs);

    @Composable
    fun Icon(tint: Color, modifier: Modifier = Modifier) {
        KfIcon(icon, tint, modifier)
    }
}

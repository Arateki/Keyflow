package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.keyflow.skins.PackKeyboard
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore

/**
 * O teclado do pacote que [skinId] (`pack:<id>`) escolhe, para as views de teclado da config (a prévia de tamanho da aba Teclado mostra a skin escolhida).
 * `null` se a skin é uma das de hoje ou o pacote sumiu/quebrou/não tem teclado — a prévia cai no Sistema. Reabre quando um pacote é instalado,
 * reimportado ou removido ([SkinPackStore.changes]).
 */
@Composable
fun rememberPackKeyboard(skinId: String): PackKeyboard? {
    if (!SkinIds.isPack(skinId)) return null
    val context = LocalContext.current.applicationContext
    val changes by SkinPackStore.changes.collectAsState()
    return remember(skinId, changes) { SkinPackStore.of(context).loadBySkinId(skinId)?.let(PackKeyboard::load) }
}

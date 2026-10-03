package com.keyflow.ui.orb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.keyflow.skins.PackOrb
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore

/**
 * A orbe do pacote de skin que [orbSkinId] (`pack:<id>`) escolhe, para a árvore Compose da config (as prévias das abas seguem a skin da
 * orbe, como a orbe de verdade). `null` se a skin é uma das de hoje ou o pacote sumiu/quebrou/não tem orbe — a prévia cai no Ateliê.
 * Reabre quando o pacote é instalado, reimportado ou removido ([SkinPackStore.changes]) e fecha a anterior ao sair da composição.
 */
@Composable
fun rememberOrbPack(orbSkinId: String): PackOrb? {
    if (!SkinIds.isPack(orbSkinId)) return null
    val context = LocalContext.current.applicationContext
    val changes by SkinPackStore.changes.collectAsState()
    val pack = remember(orbSkinId, changes) { PackOrb.open(SkinPackStore.of(context), orbSkinId) }
    DisposableEffect(pack) { onDispose { pack?.close() } }
    return pack
}

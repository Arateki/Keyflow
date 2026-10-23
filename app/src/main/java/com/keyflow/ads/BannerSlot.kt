package com.keyflow.ads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.ui.common.collectAsStateOnMain

const val BANNER_SLOT_TAG = "admob-banner-slot"
val BANNER_RESERVED_HEIGHT = 50.dp

/** Banner adaptativo com espaço estável; falha de carregamento deixa o slot vazio e silencioso. */
@Composable
fun BannerSlot(
    controller: AdsController,
    adsOff: Boolean,
    entitlementsLoaded: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!entitlementsLoaded || adsOff) return
    val context = LocalContext.current
    val ready by controller.ready.collectAsStateOnMain()
    val view = remember(controller, ready) {
        if (ready) controller.bannerView(context, adsOff = false, entitlementsLoaded = true) else null
    }
    DisposableEffect(view) {
        onDispose { view?.let(controller::destroyBanner) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(BANNER_RESERVED_HEIGHT)
            .testTag(BANNER_SLOT_TAG),
    ) {
        view?.let { banner ->
            AndroidView(
                factory = { banner },
                modifier = Modifier.fillMaxWidth().height(BANNER_RESERVED_HEIGHT),
            )
        }
    }
}

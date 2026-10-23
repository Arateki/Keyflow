package com.keyflow.ads

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette

const val ADS_PRIVACY_OPTIONS_TAG = "ads-privacy-options"

@Composable
fun AdsPrivacyOptionsEntry(controller: AdsController) {
    val required by controller.privacyOptionsRequired.collectAsStateOnMain()
    val activity = LocalContext.current as? Activity
    if (!required || activity == null) return
    val palette = LocalKeyFlowPalette.current
    Text(
        tr("Privacidade dos anúncios"),
        color = palette.teal,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { controller.showPrivacyOptionsForm(activity) }
            .testTag(ADS_PRIVACY_OPTIONS_TAG)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

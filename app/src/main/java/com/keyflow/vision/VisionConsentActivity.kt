package com.keyflow.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import java.lang.ref.WeakReference

/** Consentimento efêmero: cada solicitação abre o diálogo do sistema e usa seu resultado uma vez. */
class VisionConsentActivity : Activity() {
    private var launched = false
    private val requestId get() = intent.getLongExtra(EXTRA_REQUEST, -1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requests.isPending(requestId)) { finish(); return }
        active = WeakReference(this)
        launched = savedInstanceState?.getBoolean("launched") ?: false
        if (!launched) {
            launched = true
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // O seletor usa coordenadas da tela viva; uma janela isolada teria outra geometria.
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else manager.createScreenCaptureIntent()
                @Suppress("DEPRECATION")
                startActivityForResult(request, REQUEST_CAPTURE)
            } catch (_: RuntimeException) {
                finishWith(null)
            }
        }
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result legado para manter minSdk 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) finishWith(if (resultCode == RESULT_OK) data else null)
    }

    private fun finishWith(data: Intent?) {
        try { requests.complete(requestId, data) } finally { finish() }
    }

    companion object {
        private const val REQUEST_CAPTURE = 718
        private const val EXTRA_REQUEST = "vision_request"
        private val requests = VisionConsentRequest<Intent>()
        private var active: WeakReference<VisionConsentActivity>? = null

        /** Uma solicitação por vez, sempre na thread principal. O callback recebe null se negado. */
        fun request(context: Context, result: (Intent?) -> Unit): Boolean {
            val id = requests.begin(result) ?: return false
            return try {
                context.startActivity(Intent(context, VisionConsentActivity::class.java).putExtra(EXTRA_REQUEST, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: RuntimeException) {
                requests.cancel()
                false
            }
        }

        fun cancel() {
            requests.cancel()
            active?.get()?.let { activity ->
                activity.finishActivity(REQUEST_CAPTURE)
                activity.finish()
            }
            active = null
        }
    }
}

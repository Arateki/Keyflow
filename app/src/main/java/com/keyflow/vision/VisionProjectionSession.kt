package com.keyflow.vision

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Projeção de uma única autorização; nunca guarda o token para outra sessão. */
class VisionProjectionSession(
    private val service: Service,
    private val notificationId: Int,
    private val notification: () -> Notification,
    private val onRevoked: () -> Unit,
) {
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var promoted = false

    fun start(result: Intent): MediaProjection {
        check(projection == null && !promoted)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                promoted = true
            }
            val manager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val instance = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, result))
            projection = instance
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === instance) {
                        projection = null
                        callback = null
                        onRevoked()
                    }
                }
            }
            callback = cb
            instance.registerCallback(cb, Handler(Looper.getMainLooper()))
            return instance
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Idempotente; a revogação também é encerrada pelo mesmo caminho. */
    fun close() {
        val current = projection
        projection = null
        callback?.let { current?.unregisterCallback(it) }
        callback = null
        current?.stop()
        if (promoted) {
            promoted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
        }
    }
}

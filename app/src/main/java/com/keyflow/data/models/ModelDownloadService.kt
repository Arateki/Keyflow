package com.keyflow.data.models

import com.keyflow.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * A fila de downloads de modelos do processo ([HttpDownloadGateway]), com rede do sistema e avisos: o
 * [ModelDownloadService] mantém o processo vivo com a notificação de progresso enquanto há download, e
 * cada arquivo que termina dispara a reconciliação que confere e instala (no lugar do broadcast de
 * conclusão do DownloadManager).
 */
object AppDownloads {

    @Volatile
    private var instance: HttpDownloadGateway? = null

    fun gateway(context: Context): HttpDownloadGateway = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): HttpDownloadGateway {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        return HttpDownloadGateway(
            stateFile = File(app.filesDir, "model-downloads.json"),
            network = { networkState(connectivity) },
            listener = object : DownloadListener {
                override fun onProgress(title: String, modelId: String, done: Long, total: Long, waiting: PauseReason?) =
                    ModelDownloadService.show(app, title, modelId, done, total, waiting)

                override fun onIdle() = ModelDownloadService.stop(app)

                override fun onFileFinished(modelId: String) {
                    val results = ModelDownloader.get(app).reconcile()
                    Log.i(TAG, "arquivo de $modelId terminou; reconciliação: $results")
                }
            },
        )
    }

    private fun networkState(connectivity: ConnectivityManager): NetState {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return NetState.NONE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetState.NONE
        return if (connectivity.isActiveNetworkMetered) NetState.METERED else NetState.OK
    }

    private const val TAG = "KeyFlow.Downloads"
}

/**
 * Serviço em primeiro plano (`dataSync`) enquanto um modelo baixa: segura o processo com o app fechado e
 * mostra o progresso, com "Cancelar". Quem baixa é o [HttpDownloadGateway]; isto só exibe.
 *
 * Começar um serviço em primeiro plano com o app em segundo plano é proibido desde o Android 12: se o
 * `startForegroundService` falhar, a fila segue enquanto o processo viver e é retomada quando o
 * gerenciador de modelos abrir (a consulta de progresso acorda a fila).
 */
class ModelDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val modelId = intent.getStringExtra(EXTRA_MODEL).orEmpty()
            thread(name = "model-download-cancel") { ModelDownloader.get(applicationContext).cancel(modelId) }
            return START_NOT_STICKY
        }
        // O Android exige o startForeground logo depois do startForegroundService.
        val notification = latest ?: build(this, "KeyFlow", "", 0, -1, null)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground falhou: ${it.message}") }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "KeyFlow.Downloads"
        private const val CHANNEL_ID = "keyflow_model_downloads"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_CANCEL = "com.keyflow.action.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_MODEL = "model"
        private const val MIN_UPDATE_MS = 500L

        @Volatile private var running = false
        @Volatile private var latest: Notification? = null
        @Volatile private var lastUpdate = 0L

        /** Mostra (ou atualiza) a notificação de progresso, subindo o serviço se preciso. */
        fun show(context: Context, title: String, modelId: String, done: Long, total: Long, waiting: PauseReason?) {
            val now = System.currentTimeMillis()
            if (running && waiting == null && now - lastUpdate < MIN_UPDATE_MS) return
            lastUpdate = now
            val notification = build(context, title, modelId, done, total, waiting).also { latest = it }
            if (running) {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                return
            }
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
                running = true
            }.onFailure { Log.w(TAG, "não deu para subir o serviço de download: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            latest = null
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }

        private fun build(
            context: Context,
            title: String,
            modelId: String,
            done: Long,
            total: Long,
            waiting: PauseReason?,
        ): Notification {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, tr("Download de modelos de voz"), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val text = when (waiting) {
                PauseReason.WAITING_FOR_NETWORK -> tr("Aguardando rede")
                PauseReason.WAITING_FOR_WIFI -> tr("Aguardando Wi-Fi")
                PauseReason.WAITING_TO_RETRY -> tr("Tentando de novo")
                else -> if (total > 0) "${done * 100 / total}%" else tr("Conectando…")
            }
            val cancel = PendingIntent.getService(
                context,
                0,
                Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_MODEL, modelId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(if (total > 0) 100 else 0, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0 && waiting == null)
                .apply { if (modelId.isNotEmpty()) addAction(0, tr("Cancelar"), cancel) }
                .build()
        }
    }
}

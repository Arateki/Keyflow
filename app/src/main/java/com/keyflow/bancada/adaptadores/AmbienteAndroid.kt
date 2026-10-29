package com.keyflow.bancada.adaptadores

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.keyflow.BuildConfig
import com.keyflow.bancada.executor.AmbienteDoAparelho
import com.keyflow.bancada.executor.LeituraBateria
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.MotorInfo

/**
 * O aparelho como o executor o enxerga (BEX-07): estado térmico (`PowerManager.getCurrentThermalStatus`, API 29+; abaixo disso `null`, e só a
 * bateria vale) e bateria (`ACTION_BATTERY_CHANGED`, que é "pegajoso": a leitura não registra ouvinte nenhum).
 */
fun ambienteDoAparelho(context: Context): AmbienteDoAparelho {
    val app = context.applicationContext
    return AmbienteDoAparelho(
        termico = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
            } else {
                null
            }
        },
        bateria = {
            val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val nivel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val escala = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (intent == null || nivel < 0 || escala <= 0) {
                null
            } else {
                val plugado = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val carregando = plugado || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                LeituraBateria(pct = nivel * 100 / escala, carregando = carregando)
            }
        },
    )
}

/** Fabricante, modelo, SoC (API 31+), ABI, RAM total e SDK — nada que identifique o aparelho ou a pessoa (BHS-02). */
fun aparelhoAtual(context: Context): Aparelho {
    val memoria = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoria)
    return Aparelho(
        fabricante = Build.MANUFACTURER.orEmpty().lowercase(),
        modelo = Build.MODEL.orEmpty(),
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        ramTotalMb = (memoria.totalMem / (1024 * 1024)).toInt(),
        sdk = Build.VERSION.SDK_INT,
    )
}

/** A versão do app que está rodando. O commit não entra nesta etapa (o build não o grava). */
fun appAtual() = AppInfo(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    build = if (BuildConfig.DEBUG) "debug" else "release",
    commit = null,
)

/** O motor do modelo: LiteRT-LM (com a versão do build) no local; o nome do provedor na nuvem. */
fun motorDe(origem: String): MotorInfo =
    if (origem == "local") MotorInfo("litert-lm", BuildConfig.LITERT_LM_VERSION) else MotorInfo(origem, null)

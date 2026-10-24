package com.keyflow

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.keyflow.data.monetization.DebugSimulatedPurchaseClient
import com.keyflow.i18n.tr
import java.util.UUID
import kotlinx.coroutines.launch

/** Tela operacional de compra fake, presente apenas no APK debug. */
class DebugSimulatedPurchaseActivity : ComponentActivity() {
    private lateinit var client: DebugSimulatedPurchaseClient
    private lateinit var status: TextView
    private var receipt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = DebugSimulatedPurchaseClient(this, intent.getStringExtra(EXTRA_DEVICE_KEY) ?: UUID.randomUUID().toString())
        status = TextView(this).apply { text = tr("Pronto para testar a compra no staging.") }
        val buy = Button(this).apply { text = tr("Simular compra") }
        val refund = Button(this).apply { text = tr("Reembolsar compra simulada") }
        buy.setOnClickListener { run(tr("Compra simulada concluída.")) { receipt = client.buy(); client.account() } }
        refund.setOnClickListener {
            val current = receipt
            if (current == null) status.text = tr("Nenhuma compra simulada nesta sessão.")
            else run(tr("Reembolso simulado concluído.")) { client.refund(current); client.account() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(buy)
            addView(refund)
        })
    }

    private fun run(success: String, operation: suspend () -> Any) {
        status.text = tr("Conectando ao staging…")
        lifecycleScope.launch {
            status.text = runCatching { operation(); success }
                .getOrElse { tr("Falha na simulação: %s", it.message ?: "erro") }
        }
    }

    companion object {
        const val EXTRA_DEVICE_KEY = "deviceKey"
    }
}

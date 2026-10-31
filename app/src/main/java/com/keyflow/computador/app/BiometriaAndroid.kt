package com.keyflow.computador.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Quem autentica o usuário para liberar as chaves com biometria. Fake nos testes; [BiometriaAndroid] no aparelho. */
interface AutenticadorDeUsuario {
    /** Há biometria **forte** cadastrada? Sem isso a opção "exigir biometria" fica desligada com o motivo. */
    fun disponivel(): Disponibilidade

    /** Abre o prompt e devolve `true` se o usuário se autenticou; `false` se cancelou ou falhou. Nunca lança. */
    suspend fun autenticar(titulo: String, descricao: String): Boolean
}

enum class Disponibilidade(val frase: String) {
    SIM(""),
    SEM_HARDWARE("Este aparelho não tem leitor de biometria."),
    NADA_CADASTRADO("Cadastre uma digital ou o rosto nas configurações do aparelho para usar esta opção."),
    INDISPONIVEL("A biometria não está disponível agora."),
}

/**
 * `androidx.biometric` com classe forte. O prompt **autentica o usuário**; durante os 300 s seguintes o Keystore assina sem novo prompt
 * (duração > 0 não usa `CryptoObject`, D14). O `BiometricPrompt` precisa de uma [FragmentActivity] visível: [atividade] devolve a
 * atual, ou `null` em segundo plano — então o prompt **não abre sozinho** e a autenticação conta como não feita (CMP-22).
 */
class BiometriaAndroid(
    private val contexto: Context,
    private val atividade: () -> FragmentActivity?,
) : AutenticadorDeUsuario {

    override fun disponivel(): Disponibilidade = when (BiometricManager.from(contexto).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> Disponibilidade.SIM
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Disponibilidade.SEM_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Disponibilidade.NADA_CADASTRADO
        else -> Disponibilidade.INDISPONIVEL
    }

    override suspend fun autenticar(titulo: String, descricao: String): Boolean {
        val ativa = atividade() ?: return false
        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(contexto)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
                // onAuthenticationFailed (digital não reconhecida) deixa o prompt aberto para outra tentativa: nada a fazer.
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(titulo)
                .setDescription(descricao)
                .setNegativeButtonText(ativa.getString(android.R.string.cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            executor.execute {
                val prompt = BiometricPrompt(ativa, executor, callback)
                cont.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
                prompt.authenticate(info)
            }
        }
    }
}

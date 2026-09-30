package com.keyflow.auth.fake

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderAuth
import com.keyflow.auth.ProviderId
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Um provedor INVENTADO que passa por todo o caminho de autenticação sem tocar em nenhum provedor real (AUTH-06). Se um provedor
 * novo exigir mudar o núcleo ou este arquivo, o isolamento quebrou.
 */
object FakeProvider {
    val id = ProviderId("fake")

    /** O formato dos cabeçalhos é do provedor — este usa outro nome de cabeçalho, de propósito. */
    fun headers(credential: Credential): Map<String, String> = when (credential) {
        is Credential.ApiKey -> mapOf("X-Fake-Key" to credential.value)
        is Credential.OAuth -> mapOf("X-Fake-Token" to credential.accessToken)
    }

    fun auth(vararg flows: AuthFlow, planUsage: (Credential.OAuth) -> Boolean = { true }) =
        ProviderAuth(id, "Fake", flows.toList(), planUsage, headersFor = ::headers)
}

/** O motor "de eco" do provedor fake: pede os cabeçalhos ao [AuthorizationProvider] e devolve o que recebeu junto com o pedido. */
class FakeReasoningEngine(
    private val authorization: AuthorizationProvider,
    private val provider: ProviderId = FakeProvider.id,
) : ReasoningEngine {
    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flow {
        try {
            val headers = authorization.headers(provider)
            emit(ReasoningEvent.Delta("${headers.entries.single().value}|${request.user}"))
            emit(ReasoningEvent.Done)
        } catch (e: AuthException) {
            emit(ReasoningEvent.Error(if (e.kind == AuthErrorKind.NETWORK) ReasoningError.NETWORK else ReasoningError.UNAUTHORIZED, e.kind.name))
        }
    }
}

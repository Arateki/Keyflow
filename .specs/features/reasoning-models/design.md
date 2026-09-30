# Reasoning Models — Design

Arquitetura da aba Raciocínio. Decisões em `context.md` (R1–R9); requisitos em `spec.md`.

## Princípio condutor

**Três costuras, cada uma trocável sem tocar nas outras.**

1. **`ReasoningEngine`** — quem responde (Local ou OpenAI hoje; qualquer outro amanhã). A UI e os futuros
   consumidores só conhecem este contrato.
2. **`auth/`** — como o app **prova quem é** para um provedor. Módulo sem UI, sem `Settings` e sem provedor
   concreto; cada provedor é uma pasta com uma config sem lógica (AUTH-01/05).
3. **`LlmBackend`** (dentro do `:reasoning`) — qual biblioteca roda o modelo local (LiteRT-LM hoje; llama.cpp
   se precisar). Nem o AIDL nem o resto do app sabem o nome dela (RRUN-08).

```
ReasoningPanel (UI)                     Futuros consumidores (fora de escopo)
   │  Settings.reasoningSource               │
   ▼                                         ▼
ReasoningEngines.resolve(settings) ───────► ReasoningEngine  (generate(request): Flow<ReasoningEvent>)
   ├─ LOCAL ──► LocalReasoningEngine ── bind + AIDL (oneway + callback, deltas) ──┐
   └─ OPENAI ─► OpenAiReasoningEngine ── OkHttp (Responses API, SSE) ─┐           │
                        │ pede cabeçalhos                             │           │
                        ▼                                             │  ═════ fronteira de processo ═════
                 AuthorizationProvider ─► CredentialStore (SecurePrefs)           ▼
                        │ renova via AuthFlow.refresh                  ReasoningService (:reasoning — NOVO)
                        ▼                                                ├─ RamGuard + idle unload
                 auth/providers/openai/ (config + fluxo)                 └─ LlmBackend ◄── LiteRtBackend
```

## Dados — catálogo e Settings

### 1. `ModelCatalog` (RCAT-01/02/05)
```kotlin
enum class ModelKind { SPEECH, TEXT }
enum class EngineType { WHISPER, TRANSDUCER, LITERT_LM }     // + LITERT_LM
enum class FileRole  { ENCODER, DECODER, JOINER, TOKENS, MODEL }  // + MODEL (um .litertlm)
data class ModelSpec(/* … como hoje … */ val kind: ModelKind = ModelKind.SPEECH)
object ModelCatalog {
    val SPEECH = listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3)
    val TEXT   = listOf(GEMMA_4_E2B, /* leve: Gemma 3 1B ou substituto — RCAT-03 */)
    val MODELS = SPEECH + TEXT                       // byId continua resolvendo tudo
}
```
- `kind` tem **default `SPEECH`**: nenhum construtor existente muda; `LocalTranscriber` e o painel da
  Transcrição passam a ler `ModelCatalog.SPEECH` (mesma lista de antes, então bit-idêntico).
- `LocalModelsUi.compose(catalog = …)` já recebe o catálogo por parâmetro — o painel Raciocínio passa
  `ModelCatalog.TEXT`. `ModelDownloader`/`ModelStore` são por `id` e não sabem de tipo (RCAT-04).
- **URLs/tamanhos/SHA-256 reais** vêm da API do Hugging Face na implementação (mesma rotina da NM-T2),
  **só de repositório baixável sem login** (RCAT-03). Se o repo Gemma for gated, o modelo sai ou é trocado.
- `recommendedRamMb` é estimativa até a medição no A32 (mesma ressalva do catálogo de fala).

### 2. `Settings` (RSC-01/02)
`Settings` += `reasoningSource: ReasoningSource = LOCAL` (`LOCAL | OPENAI`), `reasoningLocalModelId: String?`,
`reasoningOpenAiModel: String`. **Nenhuma credencial aqui** — ficam no `CredentialStore` (AUTH-02).

## Contrato de geração — pacote `reasoning/`

```kotlin
data class ReasoningRequest(val system: String? = null, val user: String)   // texto → texto; YAGNI: sem histórico
sealed interface ReasoningEvent {
    data class Delta(val text: String) : ReasoningEvent
    data object Done : ReasoningEvent
    data class Error(val kind: ReasoningError, val message: String?) : ReasoningEvent
}
enum class ReasoningError { OOM, MODEL_MISSING, LOCAL, NETWORK, UNAUTHORIZED, RATE_LIMIT, ENGINE }
interface ReasoningEngine { fun generate(request: ReasoningRequest): Flow<ReasoningEvent> }
```
- Cancelar o coletor do `Flow` cancela a geração (RRUN-06): o local manda `cancel` ao serviço; o da OpenAI
  cancela o `okhttp3.Call`.
- `ReasoningEngines.resolve(settings)` devolve o engine da origem ativa; sem modelo/credencial devolve um
  `Unavailable(reason)` que o Testar usa para o texto de RTS-02.

## Execução local — pacote `reasoning/local/` + processo `:reasoning`

### 3. Fronteira IPC (RRUN-02)
```
// app/src/main/aidl/com/keyflow/reasoning/local/
IReasoningService.aidl:
  oneway void generate(in LocalLlmConfig config, String system, String user, IReasoningCallback cb);   // system pode ser vazio
  oneway void cancel();
IReasoningCallback.aidl:
  oneway void onDelta(String text);
  oneway void onDone();
  oneway void onError(int kind, String message);       // MODEL_MISSING, OOM, ENGINE
LocalLlmConfig (Parcelable): modelId, modelPath, engineType, idleUnloadMs, ramEstimateMb
```
- Tudo chega por parâmetro; o `:reasoning` **não lê** DataStore nem Settings (mesma regra do `:inference`).
- `ReasoningService` (`android:process=":reasoning"`, `exported=false`): executor único (1 geração por vez),
  guarda o backend carregado, agenda o **descarregamento por ociosidade** (`idleUnloadMs`, R8) e trata troca
  de modelo (descarrega o anterior).
- **Guarda de RAM** (RRUN-04): antes do load, `ActivityManager.MemoryInfo.availMem` × estimativa do modelo;
  insuficiente ou `OutOfMemoryError` → `onError(OOM)`, backend descarregado.

### 4. `LlmBackend` e `LiteRtBackend` (RRUN-08)
```kotlin
internal interface LlmBackend {
    fun load(config: LocalLlmConfig)
    fun generate(request: ReasoningRequest, onDelta: (String) -> Unit)   // bloqueia até acabar; cancelável
    fun cancel()
    fun unload()
}
```
- `LiteRtBackend` embrulha o **LiteRT-LM** (Kotlin, corrotinas). **Os nomes exatos da API são conferidos na
  documentação oficial durante a implementação** (Knowledge Verification Chain) — a interface acima isola
  qualquer diferença. Também se confere a versão mínima de Android e as ABIs que a biblioteca publica.
- Backend fake em teste: o serviço, o AIDL e o cliente se testam sem modelo real.

### 5. `LocalReasoningEngine` (client, processo principal)
`bind` sob demanda → chama AIDL → converte callback em `Flow`; `DeathRecipient` → `Error(LOCAL)` (RRUN-05);
religa no próximo uso. Sem preload (R8) — diferente do `LocalTranscriber`.

## Autenticação — pacote `auth/` (AUTH-01..11) ⭐

### 6. Estrutura
```
auth/
  Credential.kt            sealed: ApiKey(value) | OAuth(access, refresh?, expiresAtMs, earliestRefreshAtMs?, scopes,
                           idToken?, clientId?, account?)   ← clientId = o EMITIDO no registro dinâmico (AUTH-10)
  CredentialStore.kt       interface + SecureCredentialStore (SecurePrefs, alias próprio)
  ConnectionState.kt       Disconnected | Connecting | Connected(account?, method) | NeedsReconnect | Error(kind)
  AuthFlow.kt              interface por método
  AuthorizationProvider.kt cabeçalhos válidos, com renovação
  AuthProviders.kt         registro: lista de ProviderAuth (id, nome, métodos)
  oauth/                   Pkce.kt, PendingAuth.kt, RedirectReceiver.kt (interface),
                           LoopbackRedirectReceiver.kt   (genérico, sem provedor)
  providers/
    openai/                OpenAiAuthConfig.kt  ← SÓ dados; OpenAiChatGptFlow.kt; OpenAiApiKeyFlow.kt;
                           OpenAiHostIdentity.kt (ext_agent_host_id)
    fake/ (só em teste)    FakeProvider
```
**Regra de dependência:** `auth/` importa só Android/OkHttp/`SecurePrefs`. Ninguém em `auth/` importa
`ui/`, `Settings` ou outro provedor. `auth/providers/<x>/` só é importado pelo **registro**.

### 7. Contratos
```kotlin
sealed interface Credential { /* ver acima */ }

interface CredentialStore {
    fun get(provider: ProviderId): Credential?
    fun put(provider: ProviderId, credential: Credential)
    fun clear(provider: ProviderId)
    fun changes(provider: ProviderId): Flow<Credential?>
}

interface RedirectReceiver {                     // implementações: LoopbackRedirectReceiver (OpenAI); esquema/app link no futuro
    val redirectUri: String
    suspend fun await(timeoutMs: Long): java.net.URI
    fun close()
}

/** Um jeito de se conectar a um provedor. Duas famílias — chave e OAuth — sob o mesmo contrato mínimo. */
interface AuthFlow {
    val method: AuthMethod                       // API_KEY | OAUTH — só para a UI escolher o rótulo/controle
    val availability: Availability               // Available | Unavailable(reason) — ex.: provedor desligado (`enabled`, AUTH-09)
    suspend fun revoke(current: Credential)      // best-effort; no-op se o provedor não suporta
}
interface ApiKeyFlow : AuthFlow                  // chave: nenhum passo extra; a UI entrega a chave ao AuthController
interface OAuthFlow : AuthFlow {
    suspend fun begin(): AuthStart               // sobe o receptor, gera PKCE/state/nonce e monta a URL de autorização
    suspend fun complete(redirect: java.net.URI): Credential.OAuth   // valida state/nonce, troca code, guarda o client_id emitido
    suspend fun refresh(current: Credential.OAuth): Credential.OAuth
}
data class AuthStart(val url: String, val redirect: RedirectReceiver)

data class ProviderAuth(val id: ProviderId, val displayName: String, val flows: List<AuthFlow>,
                        val headersFor: (Credential) -> Map<String, String>)   // formato dos cabeçalhos é do PROVEDOR
object AuthProviders { val all: List<ProviderAuth> }         // única lista que conhece provedores concretos

interface AuthorizationProvider {
    /** Cabeçalhos prontos para a chamada; renova antes de expirar (margem), 1 renovação concorrente (Mutex). */
    suspend fun headers(provider: ProviderId): Map<String, String>
}
```
- **UI**: `AuthController` (`state`, `connect`, `saveApiKey`, `cancel`, `disconnect`) e `ConnectionState` por provedor. Os botões saem de
  `AuthProviders.all[..].flows` — nenhum `if (provedor == openai)` na UI (AUTH-03).
- **Clientes de API** (`OpenAiReasoningEngine`) pedem `headers(provider)` e nunca veem token cru na UI (AUTH-04).
- Como o **formato dos cabeçalhos** pode variar por tipo de credencial (`Authorization: Bearer …` hoje), ele é
  montado por uma função do **provedor** (`ProviderAuth.headersFor(credential)`), não pelo módulo genérico.

### 8. Fluxo OAuth (AUTH-08..11) — o caso da OpenAI
Valores conferidos em 2026-09-29 na documentação oficial (fontes em §10); os marcados ⚠ ainda precisam ser
descobertos/reconferidos na task de verificação.
```
UI "Entrar com ChatGPT"
  → AuthFlow.begin()
      • sobe o LoopbackRedirectReceiver em 127.0.0.1:<porta livre> (aceita 1 requisição em /callback)
      • gera code_verifier/challenge (S256), state e nonce; guarda PendingAuth (1 por vez, expira)
      • monta a URL de autorização: client_id (provisório `dynamic_agent_client` no 1º login; o EMITIDO nos
        seguintes), response_type=code, redirect_uri=http://127.0.0.1:<porta>/callback, scope, resource,
        state, nonce, code_challenge + code_challenge_method=S256, ext_agent_host_id e, só no registro
        novo, agent_name_hint
  → abre o navegador do sistema na URL (`ACTION_VIEW`; nunca WebView)
  → usuário autentica na OpenAI; o navegador é redirecionado a
        http://127.0.0.1:<porta>/callback?code=…&state=…[&client_id=oaiapp_…]
  → o receptor entrega a Uri, responde uma página curta ("Pronto — volte ao KeyFlow") e fecha o socket
  → AuthFlow.complete(uri): confere o state; guarda o client_id emitido (registro novo); troca code+verifier
        no token endpoint (sem client_secret); valida o ID token (iss, aud, nonce, exp); lê os escopos
        concedidos → sem `chatgpt.tokens.use.direct` ⇒ conectado "sem uso do plano" (RCL-07)
  → CredentialStore.put(...) ⇒ ConnectionState = Connected(conta, método)
Renovação (AUTH-11): AuthorizationProvider.headers() vê expiresAt − margem (sem passar de
  earliest_refresh_at) → AuthFlow.refresh() sob Mutex → substitui access, expiração, escopos e refresh
  rotativo JUNTOS e grava no cofre ANTES de devolver o cabeçalho; falhou (invalid_grant, token_expired,
  refresh_token_invalidated) ⇒ apaga os tokens, MANTÉM o client_id emitido e vira NeedsReconnect (repetir o
  OAuth com o mesmo client_id). ⚠ parâmetros exatos do refresh: seção "Refreshing tokens" da página sign-in.
Sair: revoga o refresh token (refresh_token + token_type_hint=refresh_token + client_id; HTTP 200 vazio =
  sucesso) e limpa tudo. ⚠ URL do endpoint de revogação a descobrir.
```
- **Access token** vale 1 h; **refresh token** vale 30 dias e **rota a cada uso** — por isso a gravação atômica
  e o `Mutex` (uma renovação concorrente perderia o refresh e derrubaria a conexão).
- **Loopback no Android:** `ServerSocket` ligado só a `127.0.0.1` (porta 0 → livre), uma conexão, caminho e
  método exatos, fecha ao receber ou por tempo. O navegador é outro processo **do mesmo aparelho**, então
  alcança o socket. Outro app local poderia tentar conectar-se à porta, mas sem o `code_verifier` não troca o
  `code` — `state` + PKCE cobrem. O redirect é `http://127.0.0.1` (nunca `localhost`), porta variável.
- **O app precisa estar vivo enquanto o navegador está aberto.** Se o SO matar o processo, o navegador mostra
  "conexão recusada" e o usuário toca "Entrar" de novo. Risco medido no A32; se for frequente, o receptor sobe
  num serviço em primeiro plano (a task decide com a medição).
- **Biblioteca OAuth: fluxo próprio mínimo** (PKCE + `ACTION_VIEW` + OkHttp + `LoopbackRedirectReceiver`). Bibliotecas
  como o AppAuth-Android miram redirect por esquema/app link (a confirmar) e não cobrem o registro dinâmico nem
  o `ext_agent_host_id`; o fluxo cabe atrás do `AuthFlow` e a **PKCE é testada contra o vetor do RFC 7636 (Apêndice B)**.
- `PendingAuth` vive só em memória: se o app morrer durante o login, o redirect chega sem tentativa pendente,
  é descartado e o usuário toca "Entrar" de novo (o `state` órfão nunca é aceito).
- **Registros separados (AUTH-10):** o `client_id` emitido é guardado **com a identidade do ID token**; duas contas
  = dois registros, nunca misturados. O `ext_agent_host_id` é derivado de uma chave EC gerada no Android
  Keystore desta instalação (impressão digital JWK no formato do RFC 9278 — ⚠ formato exato a conferir), opaco e
  estável entre logins.

### 9. `CredentialStore` seguro (AUTH-02)
- `SecurePrefs.create(context, name = "keyflow_auth", keyAlias = "keyflow_auth_master")` — **alias próprio**:
  a recuperação do `SecurePrefs` apaga o keyset **e a chave**; se dividisse a chave-mestra com o
  `keyflow_secure`, a recuperação de um invalidaria o outro.
- **Backup:** as regras excluem por **nome de arquivo** (`keyflow_secure.xml`). O arquivo novo
  `keyflow_auth.xml` **tem de entrar nas três listas** (`backup_rules.xml`, `cloud-backup`, `device-transfer`)
  — senão restaurar sem a chave do Keystore reabre o crash em loop do A32 (Q8). **Teste unitário** lê os XMLs
  e falha se algum arquivo cifrado conhecido não estiver excluído — vale para provedores futuros.
- Nunca em `Settings`, log, estado de UI ou mensagem de erro; erros carregam só o **tipo**.

### 10. Config por provedor (AUTH-05)
```kotlin
// auth/providers/openai/OpenAiAuthConfig.kt — SÓ dados. É o único arquivo a editar quando a OpenAI mudar algo.
internal object OpenAiAuthConfig {
    val enabled = BuildConfig.DEBUG                          // RPRV-04: só builds de dev até o Yan decidir a elegibilidade
    const val authorizationUrl = "https://auth.openai.com/api/accounts/authorize"
    const val tokenUrl = "https://auth.openai.com/api/accounts/oauth/token"
    const val provisionalClientId = "dynamic_agent_client"   // só no 1º login; depois vale o `oaiapp_…` emitido
    val scopes = listOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
    const val resource = "https://api.openai.com/v1"
    const val redirectPath = "/callback"                     // host 127.0.0.1 fixo, porta variável
    const val agentNameHint = "KeyFlow"
    const val apiBaseUrl = "https://api.openai.com/v1"       // mesma base para chave e para o token de plano
    // ⚠ A descobrir/reconferir na task de verificação (RCL-04): parâmetros da renovação, URL de revogação,
    //   formato do ext_agent_host_id. Não entram por suposição.
    const val docsSource = "https://developers.openai.com/siwc/token-sharing-open-source"
}
```
Fontes de cada valor (`developers.openai.com/siwc/token-sharing-open-source/…`): `sign-in` (endpoints, escopos,
`resource`, loopback, `client_id` provisório), `token-reference` (validade dos tokens, resposta),
`models-and-inference` (endpoint e corpo), `errors-and-recovery` (códigos), `profiles-and-sessions` (registro,
host id, revogação). **As páginas foram lidas por um resumidor automático; a 1ª task relê o texto literal.**

### 11. Provedor fake e a prova de isolamento (AUTH-06)
`FakeProvider` (só no source set de teste) implementa `AuthFlow` + um `ReasoningEngine` de eco e entra no
registro **do teste**. O teste percorre: registro → lista de botões → conectar → guardar → expirar → renovar →
desconectar → estado de UI, **sem** importar nada de `providers/openai`. Se esse teste precisar de mudança
para acomodar um provedor novo, o isolamento quebrou.

## Provedor OpenAI — `reasoning/openai/`

### 12. `OpenAiReasoningEngine` (RCL-02/03/05/06)
- `POST {apiBaseUrl}/responses`, cabeçalhos de `AuthorizationProvider.headers(OPENAI)` (`Authorization: Bearer …`,
  `Content-Type: application/json`), corpo **enxuto** que serve a chave e o login:
  `{"model": …, "input": [{"role": …, "content": …}], "store": false, "stream": true}` — **sem**
  `max_output_tokens`, `temperature`, `top_p`, `user`, `metadata` e demais parâmetros que o fluxo de plano rejeita.
- Lê o corpo SSE **linha a linha** direto do `BufferedSource` do OkHttp (**sem nova dependência**):
  `response.output_text.delta` → `Delta`; **`response.completed`** → `Done` (só aqui é sucesso);
  `response.failed` (com código, ex. `subscription_sharing_usage_limit_exceeded`) e `response.incomplete` → `Error`.
- Erros preservam **status e código** (RCL-03): 401 `subscription_sharing_invalid_user` → `UNAUTHORIZED` (a UI:
  login → `NeedsReconnect`; chave → "chave inválida"); 403 `…_user_not_eligible` → `UNAUTHORIZED` sem repetir;
  429 `…_usage_limit_exceeded` → `RATE_LIMIT`; 400 `…_unsupported_capability` → `ENGINE` sem repetir o corpo;
  503 → repetição com espera limitada; `IOException` → `NETWORK`. Nunca cai em outra forma de cobrança sozinho.
- `OpenAiModels.list(headers)` faz `GET {apiBaseUrl}/models` (RCL-06); falhou → mantém o modelo já escolhido e a UI oferece "Atualizar" (nunca inventa ids).
- Um único engine atende chave e login (RCL-05); qualquer diferença por tipo de credencial fica em
  `OpenAiAuthConfig`/`headersFor`.
- **Fixtures do `MockWebServer`** reproduzem os eventos e os códigos **da documentação**; a aceitação real do corpo é
  conferida com uma chave de API de verdade no A32 (a chave é o único caminho testável sem conta de plano).

## UI — `ui/config/`

### 13. `ReasoningPanel` (RSC-01..06, RTS-01..04)
- Branch da aba `RACIOCINIO` no `ConfigScreen` (hoje `Unit`).
- `SettingsGroup("Origem do raciocínio")` + `Segmented(ReasoningSource.entries)`; abaixo, o painel da origem.
- **Local**: lista de modelos (`LocalModelsUi.compose(catalog = ModelCatalog.TEXT, …)` + `LocalModelsController`
  já existente, que é por `id`); nota de privacidade.
- **OpenAI**: cabeçalho de conexão (`ConnectionState`), controles montados de `AuthProviders` (botão "Entrar
  com ChatGPT" desabilitado com motivo enquanto `Unavailable`; campo de chave com `AutoSaveField` +
  máscara), dropdown Modelo, nota de privacidade.
- **Testar** (`TestArea`): botão Testar/Parar + área de resposta em fluxo + linha de tempos (1º trecho e
  total). Desabilitado com o motivo ao lado quando o engine é `Unavailable`.
- **Componente compartilhado (RSC-06):** o cartão de modelo hoje é `private` em `LocalPanel.kt`. Extrair
  `ModelCard`/`CardAction`/`ConfirmRow` para `ModelList.kt` (`internal`), com o **prefixo de tag por parâmetro**
  (default = o atual `voz-local-*`) — refatoração enxuta, testes e goldens da Transcrição intocados.
- Docs: seção "Raciocínio" + `docsSectionFor(RACIOCINIO)` + "?" (RSC-05).
- i18n: todo texto novo por `tr("português")`, com `TranslationsEn/Es` e o `TranslationsTest` cobrindo.

### 14. Galeria/goldens
Rotas `config-raciocinio-local` (vazio), `-baixando`, `-pronto`, `-openai-desconectado`, `-openai-conectado`,
`-testando` (resposta congelada), claro/escuro, com fakes determinísticos (padrão `RouteCatalog`). A rota
`config-raciocinio` atual (aba vazia) muda; **só** ela e o ícone da barra lateral mudam nos demais goldens.

## Build & loja (RPRV-01..03)
- **LiteRT-LM** no `app/build.gradle.kts` (versão fixada; confirmar `minSdk` ≤ 24 ou registrar a exceção).
  ABIs publicadas podem excluir `x86_64` (o emulador): nesse caso o teste do motor real roda só no A32 e o
  serviço/AIDL/cliente se testam com `LlmBackend` fake — o `LiteRtBackend` é carregado só dentro do `:reasoning`.
- **16 KB pages:** conferir o alinhamento ELF dos `.so` (mesmo script da STO-02). **Tamanho do APK:** medir antes
  e depois e anotar.
- **Backup:** ver §9 (arquivo de credenciais); `models/` já excluído cobre os de texto.
- **Licenças:** LiteRT-LM + cada modelo em `app/licenses/` + Docs. **Privacidade/Data Safety:** ver RPRV-01.
- Manifest: `ReasoningService` (`:reasoning`). Sem `AuthRedirectActivity` — o redirect da OpenAI é loopback, então
  não há intent-filter novo; o receptor é um `ServerSocket` em `127.0.0.1` (só `INTERNET`, que o app já tem).

## O que NÃO muda
`InferenceService`/`SherpaEngine`/`LocalTranscriber` (só passam a ler `ModelCatalog.SPEECH`), `ModelDownloader`/
`ModelStore`/`ModelDownloadService`, `Settings` de fala, `SecurePrefs` (só ganha um segundo uso), gestos/orbe/
teclado, abas Transcrição/Visão/Teclado/Sobrepor.

## Plano de testes (TESTING.md)
- **Unit (JVM):** catálogo por tipo (integridade, `byId`, fala inalterada); PKCE (S256 conhecido) e `state`;
  `AuthorizationProvider` (renovação com relógio falso, **uma renovação concorrente**, falha → `NeedsReconnect`);
  registro e **provedor fake** (AUTH-06); `ConnectionState`; regras de backup (XML × arquivos cifrados);
  `OpenAiReasoningEngine` com `MockWebServer` (SSE, 401, 429, cancelamento); mapeamento de erros IPC→
  `ReasoningError`; `LocalModelsUi.compose` com catálogo `TEXT`; `Unavailable(reason)` → texto do RTS-02.
- **Instrumented:** `SecureCredentialStore` real (put/get/clear, recuperação de keyset quebrado);
  `LoopbackRedirectReceiver` (aceita 1 requisição no caminho exato, recusa outro caminho/método, fecha depois, expira);
  **IPC roundtrip real** com o `:reasoning` e backend fake/modelo ausente (`MODEL_MISSING`, `DeathRecipient`);
  `ReasoningPanel` Compose com fakes; smoke opt-in do `LiteRtBackend` real (`assume()` se houver modelo em
  `/sdcard/keyflow-test-models/`, só arm64).
- **Manual (A32):** baixar o modelo leve → Testar → tempos; modo avião; `kill` do `:reasoning`; chave OpenAI;
  login (loopback + registro dinâmico) e o app vivo com o navegador aberto; medir e ajustar `idleUnloadMs` e `recommendedRamMb`.
- **Screenshot:** goldens das rotas novas (claro/escuro); os demais bit-idênticos.

## Riscos / mitigação
- **Modelo Google gated no Hugging Face** → RCAT-03 (só entra baixável sem login); substituto decidido na verificação.
- **Elegibilidade (RPRV-04):** a OpenAI limita o uso do plano a apps open-source/locais e manda apps **pagos** para um
  formulário de interesse; o KeyFlow planeja créditos, skins pagos e anúncios → `enabled` só em dev até o Yan decidir; a
  chave de API segue funcionando; o resto da feature não depende do login.
- **Recurso em prévia:** a OpenAI documenta "preview limitations" (parâmetros e ferramentas rejeitados, sem
  multimodal). Tudo isso está isolado em `auth/providers/openai/` + `OpenAiReasoningEngine` — mudar ali não toca o resto.
- **Loopback com o app morto em segundo plano** → medir no A32; plano B: receptor em serviço em primeiro plano.
- **Endpoint do token de plano diferente do da chave** → isolado em `OpenAiAuthConfig`/`headersFor` (RCL-05).
- **API do LiteRT-LM difere do esperado / sem `x86_64`** → `LlmBackend` isola; testes com backend fake.
- **A32 lento ou sem RAM para o Gemma 4** → aviso de peso + guarda de RAM + modelo leve; medir (R8).
- **Backup restaurando cofre sem chave** → §9 (alias próprio + exclusão + teste de regras).
- **Login muda de novo** → é exatamente o cenário do desenho: editar `OpenAiAuthConfig` ou trocar o `AuthFlow`.

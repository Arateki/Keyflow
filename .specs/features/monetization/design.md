# Monetization — Design

Arquitetura do lado do app. Decisões em `context.md` (M7–M10, M1–M6 na keyflow-api); requisitos
em `spec.md`. **Depende da KeyFlow API em staging** (`.specs/features/keyflow-api/`).
**Status**: Draft (aguardando aprovação do Yan).

## Princípio condutor

**A monetização é uma camada aditiva sobre pontos de extensão que já existem.** O modo Créditos é
só mais uma `EffectiveConfig.Ready` apontando pro proxy (o `TranscriptionClient` não muda de
contrato); a `UnlockSheet` inerte ganha fiação; skins bloqueadas ganham a chave. Nada de anúncio
ou chamada de rede nova no IME/overlay — todo o código novo de ads vive no app de config
(`SetupActivity`), e o app inteiro funciona com a API fora do ar (IDN-03).

```
data/monetization/  (pacote novo — processo principal, sem UI)
  AnonymousIdentity ── UUID + Auto Backup + Block Store (IDN-01)
  KeyFlowApiClient ─── register (lazy, Play Integrity) · account · redeem   (OkHttp existente)
  BillingManager ───── Play Billing: produtos, fluxo de compra c/ obfuscatedAccountId,
  │                    queryPurchases (restore), pending, consume-após-redeem
  EntitlementsRepository ── união (Play local ∪ conta API) + cache DataStore (UNL-03)
  PendingRedemptions ────── purchaseTokens persistidos até resgatar (CRD-03)
  RemoteConfigRepository ── /v1/config + /v1/models com ETag/cache + defaults (RMT-01/02)

ui/ (config)                          ads/ (só app de config)
  VozPanel: Créditos real + recarga     AdsController (init gateado por adsOff)
  UnlockSheet: compra real              BannerSlot (Docs/Modo/Teclado)
  "Ganhar minutos" (rewarded)           InterstitialPolicy (pura) + fiação na troca de aba
```

## Identidade anônima (IDN-01..03)

- **`AnonymousIdentity`**: UUID v4 gerado no primeiro acesso, persistido em SharedPreferences
  **claro** próprio (`identity.xml`) — precisa estar no **Auto Backup** (ao contrário dos tokens).
  Redundância via **Block Store** (`play-services-auth-blockstore`): grava no primeiro uso, lê na
  recuperação (reinstalação na mesma conta Google). Ordem de resolução: arquivo → Block Store →
  gerar novo.
  - **Atenção às regras de backup existentes**: a native-voice-models (NM-T12) escreve
    `dataExtractionRules`/`fullBackupContent` excluindo `models/` — a identidade deve constar como
    **incluída** nas mesmas regras (as duas features tocam o mesmo arquivo; quem chegar segundo
    reconcilia).
- **Registro lazy** (IDN-01): `KeyFlowApiClient.ensureRegistered()` é chamado apenas pelo primeiro
  fluxo que precisa da API (abrir Créditos, comprar, rewarded) — nunca no onboarding. Pede o token
  do **Play Integrity** (classic request, `IntegrityManager`) e faz `POST /v1/register`; o
  `accessToken` vai pro **EncryptedSharedPreferences existente** (mesmo storage dos tokens de
  provedor). 401 em qualquer chamada → re-registro automático (a API rotaciona o token e preserva
  a conta).
- **`obfuscatedAccountId`** (IDN-02): `sha256hex(uuid)[..64]` — mesma função documentada no design
  da API (os dois lados precisam bater). Vai em `BillingFlowParams.setObfuscatedAccountId`.

## Billing (CRD-02/03, UNL-01/02)

- **`BillingManager`** (Play Billing Library atual — versão conferida na doc na task, não de
  memória): conexão resiliente, `queryProductDetails` dos 4 SKUs (`credits_10/20/50`,
  `unlock_forever`), fluxo de compra com `obfuscatedAccountId`, `PurchasesUpdatedListener` +
  `queryPurchases` na abertura (restore UNL-02 + retomada de PENDING).
- **Regra de ouro do consumível** (CRD-03): comprou → persiste o `purchaseToken` em
  **`PendingRedemptions`** (DataStore) → `POST /v1/purchases/redeem` → só com resposta de sucesso
  da API faz `consumeAsync`. Falha de rede → o token fica na fila e re-tenta na próxima abertura/
  retry manual. A compra nunca se perde: enquanto não consumida, `queryPurchases` a devolve.
- **Não-consumível**: redeem não é pré-condição do entitlement local (`unlock_forever` vale pela
  união UNL-03) — mas o app também informa a API (melhor esforço) p/ o ledger conhecer a compra.
- **Sem Play Services** (edge): `BillingClient` falha → UI de créditos/unlock degrada com aviso;
  resto do app intacto.

## Entitlements (UNL-03/04)

- **`EntitlementsRepository`**: `entitlements: Flow<Entitlements(adsOff, skinsUnlocked)>` =
  união de (a) compras locais do Play (`queryPurchases` → `unlock_forever`) e (b) resposta de
  `GET /v1/account` (ganhos por recarga ≥ R$20) — **qualquer um liga**; cache DataStore persiste
  offline; API é fonte de verdade do lado dela (reembolso derruba (b) no próximo sync).
- Consumidores: `AdsController` (adsOff), seção Skins/`UnlockSheet` (skinsUnlocked) e, no futuro,
  o gating de temas do theme-system (THM-04 — expor como interface simples
  `EntitlementsProvider` p/ a outra feature não acoplar no pacote inteiro).

## Créditos na aba Voz (CRD-01..05)

- **`effectiveConfigFor(CREDITS)`** deixa de devolver `Unavailable`: com identidade registrada +
  saldo conhecido → `Ready(Config(endpoint = <staging>/v1/transcribe, authHeader =
  "Authorization", authValue = "Bearer <accessToken>", model = "", language = settings))` — o
  `TranscriptionClient`/`WhisperApi` atuais fazem o resto (M3). Sem registro ainda →
  `Unavailable` (a UI da Voz oferece ativar). Headers extras (`X-Request-Id` p/ idempotência,
  `X-Audio-Seconds` opcional) entram no `WhisperApi.post` de forma genérica (campo novo no
  `Config` ou header fixo quando a origem é CREDITS — decidir na task pela menor cirurgia).
- **Saldo**: `GET /v1/account` ao abrir a aba Voz; após cada transcrição por créditos, ler o
  header **`X-KeyFlow-Balance-Seconds`** da resposta do proxy (o `TranscriptionClient` passa a
  expor headers de resposta — mudança pequena e retrocompatível) → atualiza o cache/UI sem
  chamada extra (CRD-05).
- **UI**: o card stub do `VozPanel` vira real — saldo em minutos (m:ss quando < 1 min), botão
  **Recarregar** → sheet com os 3 SKUs (preço do Play + minutos da config remota + selo
  "remove anúncios + libera skins para sempre" nos ≥ R$20 — CRD-02), estados de compra
  (aguardando pagamento p/ PENDING), aviso honesto de perda de saldo sem backup (edge M9) e
  **"Ganhar minutos"** (rewarded, RWD). 402 na transcrição → fluxo de erro atual no orbe/teclado
  (✖) e CTA de recarga com saldo zerado ao abrir a Voz (CRD-04).

## Anúncios (ADS-01..06, RWD-01..04)

- **`AdsController`** (só referenciado pela `SetupActivity`/config — nunca IME/overlay/onboarding):
  - `adsOff == true` → **não inicializa** o SDK (`MobileAds.initialize` nem roda) — ADS-05.
  - **Consentimento**: UMP (User Messaging Platform) na primeira sessão com ads — exigência de
    política do AdMob (LGPD/GDPR); sem consentimento → só ads não-personalizados conforme
    resposta do form.
  - **Banner adaptativo** (ADS-02): composable `BannerSlot` com **altura reservada fixa** (sem
    empurrão ao carregar) no rodapé de Docs/Modo/Teclado; nunca em Voz/Sistema/onboarding; falha
    de load → slot vazio silencioso (ADS-06).
  - **`InterstitialPolicy`** (ADS-03/04): **classe pura** (clock/params injetáveis, unit-testável)
    que decide `shouldShow(tabSwitch)` com: engajamento mínimo na sessão (contador de interações),
    cooldown vigente vencido (backoff inicial ~2 min de uso ativo, ×2 por exibição, **persistido**
    em DataStore), cap diário, não-primeira-sessão, janela pós-compra. Parâmetros da config remota
    com defaults embutidos. Fiação: `ConfigScreen` notifica troca de aba → policy decide → mostra
    o interstitial pré-carregado.
  - **Rewarded** (RWD): botão "Ganhar minutos" na tela de créditos (existe mesmo com `adsOff` —
    M8/RWD-04); `custom_data = accountId`; recompensa só via **SSV** — a UI mostra "creditando…"
    e re-consulta o saldo com polling curto (nunca credita otimista, RWD-03); cap diário da config
    → botão mostra quando volta, sem ad request (RWD-02).

## Config remota & manifest (RMT-01/02)

- **`RemoteConfigRepository`**: busca `/v1/config` na abertura do app de config (não do IME) com
  ETag/If-None-Match + cache em disco + defaults embutidos (operação offline). Consumidores:
  minutos/SKU na sheet de recarga, parâmetros do `InterstitialPolicy`/rewarded, aviso de versão
  mínima.
- **Manifest de modelos**: `/v1/models` alimenta o `ModelCatalog` da native-voice-models —
  o catálogo embutido vira **fallback** (o design da NM já previu isso: catálogo serializável,
  G4). Cache local + checksum continua o mesmo (o downloader já valida sha256).

## O que NÃO muda

IME/overlay/notificação (zero ads, zero chamadas novas), `ButtonStateMachine`/gestos, fluxo de
inserção, origens OpenAI/Groq/Custom/Local, onboarding (nenhum registro/ads lá), Docs (ganha só o
banner no rodapé).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: `InterstitialPolicy` completa (engajamento/backoff/caps/primeira sessão/janela
  pós-compra — clock fake); `effectiveConfigFor(CREDITS)` (registrado/não/sem saldo); união de
  entitlements; hash do `obfuscatedAccountId` (vetor fixo igual ao da API); fila
  `PendingRedemptions` (retry/remoção pós-sucesso); parse do header de saldo.
- **Instrumented**: identidade persiste e sobrevive a recriação (arquivo); repos DataStore novos
  (padrão `SettingsRepositoryTest`); `VozPanel` Créditos com fakes (saldo/recarga/402→CTA/estados
  PENDING); `UnlockSheet` real com `BillingManager` fake (comprado → skins destravam, banner
  some); `BannerSlot` reserva espaço com ads desligado.
- **Manual (Yan, device físico)**: compras de teste (license testing) fim-a-fim contra staging —
  recarga credita 1×, unlock restaura ao reinstalar, R$20 remove ads, rewarded credita via SSV,
  cadência do interstitial (1 por sessão típica, dobrando o intervalo).
- **Screenshot**: rotas novas (créditos com saldo/sem saldo, sheet de recarga, unlock, banner
  placeholder) claro/escuro.

## Riscos / mitigação

- **Play Console/AdMob/credenciais são do Yan** (produtos, license testers, unidades de ad,
  SSV config) — tasks marcam explicitamente o que precisa dele; código usa test ads/fakes até lá.
- **Billing Library evolui rápido** → versão + APIs conferidas na doc oficial na task (chain de
  verificação), nunca de memória.
- **Política AdMob** (ads em IME proibidos; UMP obrigatório) — o design já confina ads ao app de
  config e inclui UMP; revisar compliance na validação final.
- **Reembolso × cache local**: entitlement (b) some no sync; o (a) o Play revoga sozinho em
  `queryPurchases`. Cache nunca é fonte de verdade — só exibição offline.
- **Coordenação com theme-system**: skins/temas usam o mesmo `skinsUnlocked` — a interface
  `EntitlementsProvider` é o ponto de contato (sem dependência circular entre as features).


## Exclusão pelo aparelho e retenção (v1-apagar-dados)

`DELETE /v1/account` usa o mesmo extractor Bearer do GET. A migration 0004 muda os quatro FKs para
`ON DELETE CASCADE`, preservando atomicidade e isolamento por conta. Sem tombstone: 204 no sucesso,
401 com o token já revogado. Repetição após resposta perdida usa registro da mesma identidade e novo
DELETE; somente depois remove credencial e zera cache remoto. O mutex do repositório serializa com
refresh para impedir retorno do cache antigo. O marcador de exclusão persiste no armazenamento seguro;
leitura de conta e restauração automática não recriam o cadastro. Nova compra explícita libera registro.
A identidade usada no obfuscatedAccountId do Play e o desbloqueio local reconhecido são preservados.

Logs não têm tabela de IP/eventos: tracing → journald; Nginx em arquivos próprios; PostgreSQL em log
local. Configuração em `api/deploy/logs/`, implantada pelo provisionamento sem dependência de checkout
remoto. Timer horário, histórico de 29 dias e margem para arquivo ativo/atraso da limpeza mantêm limite
de 30 dias. Detalhes e ativação pendente com Yan: `api/deploy/LEIA-ME.md`.

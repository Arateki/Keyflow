# KeyFlow API — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (M1–M6)
**Status**: Em execução (Yan autorizou o início pela Frente B em 2026-07-02, via
`DEVELOPMENT-ORDER.md`)

> **Monorepo** — o código Rust vive em **`api/`** na raiz deste repositório (API-T1 cria a
> pasta; esta spec fica onde está). Não tocar em nada do Android (app/, goldens, STATE.md).
> Gate `rust` (rodar dentro de `api/`): `cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`.
> Integração exige Postgres local (`api/docker-compose.yml`) + `wiremock` p/ Google/provedor.
> **Nunca fabricar formato de API do Google** (Integrity/Developer API/SSV) — conferir a doc
> oficial na task e fixar o formato num teste wiremock.
> Credenciais reais (service account, chaves de provedor) são do Yan — tasks que dependem delas
> têm a parte "com credencial" marcada como **manual/Yan**.

## Execution Plan

```
Fase A (fundação):   API-T1 → API-T2 → API-T3
Fase B (identidade): API-T3 → API-T4 → API-T5 → API-T6 [P] · API-T7 [P]
Fase C (compras):    (T4,T5) → API-T8 → API-T9
Fase D (conteúdo):   T3 → API-T10 [P]
Fase E (proxy):      (T5,T10) → API-T11
Fase F (rewarded):   (T5,T10) → API-T12
Fase G (deploy/e2e): (T6..T12) → API-T13 → API-T14
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14.
**`[P]`**: T6/T7/T10 são independentes entre si após suas deps; tudo aqui é JVM-free (sem device),
então `[P]` real se houver dois agentes — senão seguir a ordem sequencial.

---

## Fase A — Fundação

### API-T1 — Pasta `api/` no monorepo + esqueleto axum + CI — ✅ Concluída (2026-07-02)
- **What**: criar `api/` na raiz (`cargo init` bin, edição estável atual); axum + tokio +
  `GET /health` (stub sem DB); `rustfmt.toml`/clippy config; CI (GitHub Actions,
  `.github/workflows/api.yml`) com **path filter `api/**`** rodando o gate `rust`;
  `api/docker-compose.yml` com Postgres de dev/teste; `api/README.md` com setup + convenções de
  teste (design §Plano de testes); `.gitignore` da raiz ganha `api/target/`. A spec permanece em
  `.specs/features/keyflow-api/`.
- **Where**: `api/Cargo.toml`, `api/src/main.rs`, `.github/workflows/api.yml`,
  `api/docker-compose.yml`, `api/README.md`, `.gitignore`.
- **Depends on**: —. **Covers**: OPS-04 (parcial).
- **Done when**: `cargo run` (em `api/`) responde `/health` 200; CI verde no gate `rust`; build
  Android intacto (`assembleDebug` verde — Gradle não enxerga `api/`).
- **Tests**: 1 teste de integração do `/health`. **Gate**: `rust`.
- **Nota de execução**: versões conferidas na sparse index de crates.io em 2026-07-02 (não
  fabricadas) — axum 0.8.9, tokio 1.52.3, tower 0.5.3 (dev-dep, feature `util`, p/ `oneshot` no
  teste). Edição 2024 (default do `cargo init` nesta toolchain, rustc 1.96). `docker-compose.yml`
  usa `postgres:18-alpine` (tag mais recente confirmada no Docker Hub). CI usa
  `dtolnay/rust-toolchain@stable` + `Swatinem/rust-cache@v2`; roda `fmt --check` → `clippy -D
  warnings` → `test` (Postgres só entra no workflow quando a API-T3 tiver testes de integração).
  `assembleDebug` **não** foi executado (evitar contenção de `GRADLE_USER_HOME` com a Frente A) —
  confirmado estaticamente que `settings.gradle.kts` só inclui `:app`, então `api/` é invisível ao
  Gradle. Trabalho feito em worktree própria (`../keyflow-api-wt`, branch `feature/keyflow-api`).

### API-T2 — Config por ambiente + tracing JSON — ✅ Concluída (2026-07-02)
- **What**: módulo `config` (envs tipadas: `DATABASE_URL`, paths de secrets, bind addr, flags de
  teste) com erro claro de env faltante; `tracing` + subscriber JSON + `TraceLayer` (latência por
  rota). Nenhum secret no repo (OPS-03).
- **Where**: `src/config.rs`, `src/main.rs`.
- **Depends on**: API-T1. **Covers**: OPS-02, OPS-03 (parcial).
- **Done when**: unit — env faltante falha com mensagem específica; logs saem em JSON com rota e
  latência (verificado em teste capturando o subscriber ou por inspeção documentada).
- **Tests**: unit. **Gate**: `rust`.
- **Nota de execução**: `Config::from_env()` tipa `DATABASE_URL` (obrigatória) e `BIND_ADDR`
  (opcional, default `0.0.0.0:8080`) — 4 unit tests cobrindo falta/valor inválido/default.
  `GOOGLE_SERVICE_ACCOUNT_JSON_PATH`/flags de relax (Integrity/AdMob SSV) ficaram para T4/T12,
  quando o código que efetivamente os consome existir (evita superfície de config morta hoje).
  Tracing: `tower_http::trace::TraceLayer` aplicado via **`route_layer`** (não `layer` — gotcha do
  axum: `layer` envolve o roteamento inteiro e roda antes da extensão `MatchedPath` ser inserida,
  então o span sairia sem rota). `RUST_LOG` default `info,tower_http=debug` deixa latência visível
  sem configuração extra. **Rota + latência em JSON verificados por inspeção documentada** (não
  por unit test): um teste que instala subscriber via `tracing::subscriber::set_default` ficou
  flaky quando rodado em paralelo com as outras `#[tokio::test]` do binário — causa raiz é o cache
  global de `Interest` por callsite do crate `tracing`, compartilhado entre threads de teste
  (arquitetura do crate, não bug do código); alternativa prevista no próprio design. Evidência real
  (via `cargo run`) documentada no README e no comentário de `src/main.rs`.

### API-T3 — Postgres + migrations do schema núcleo + helpers de tx — ✅ Concluída (2026-07-02)
- **What**: sqlx com pool + migrations do design §Schema (accounts, ledger, purchases, rewards,
  transcribe_requests, documents); helpers de mutação de saldo (crédito/débito com guarda e sem
  guarda) **sempre em tx** (ledger + update juntos); `/health` passa a fazer `SELECT 1`.
- **Where**: `migrations/`, `src/db/`.
- **Depends on**: API-T2. **Covers**: OPS-01, ID-03 (schema sem PII — verificável), OPS-04.
- **Done when**: integração — migrations aplicam do zero; débito concorrente do último saldo: só
  um passa (teste com 2 tx disputando); crédito+ledger atômicos (falha no meio → rollback).
- **Tests**: integração (Postgres docker). **Gate**: `rust`.
- **Nota de execução**: migration única `migrations/0001_core_schema.sql` com as 6 tabelas do
  design (sem PII/áudio/texto — conferível por inspeção). `src/db/ledger.rs`: `credit`/
  `debit_guarded`/`revoke`, genéricas sobre `sqlx::Acquire<'a, Database = Postgres>` (aceitam
  `&PgPool` OU `&mut Transaction` — nesse caso abrem um **savepoint** aninhado, então compõem
  naturalmente com uma tx maior do chamador, ex.: T8 vai inserir a purchase + creditar na mesma
  tx). `debit_guarded` usa `UPDATE ... WHERE balance_seconds >= $delta RETURNING` — o lock de
  linha do Postgres serializa débitos concorrentes na mesma conta (testado com `tokio::join!` de
  2 chamadas reais, 7 testes de integração em `tests/db_ledger.rs` + helpers em
  `tests/common/mod.rs`). **Refatoração necessária**: virou **lib+bin** (`src/lib.rs` expõe
  `app`/`config`/`db`/`init_tracing`; `src/main.rs` ficou fino) — os testes de integração em
  `tests/` só enxergam a API pública do crate, e a spec já previa "exercitar rotas reais" nesses
  testes. **Achado corrigido antes de virar flake em CI**: os testes de `config.rs` mutavam
  `std::env::set_var`/`remove_var` (estado global do processo) e corriam risco de colidir com
  outros `#[test]`s tocando `DATABASE_URL` em paralelo no mesmo binário; `Config::from_env()`
  virou wrapper fino sobre `Config::from_map(&HashMap)`, testado sem tocar env real. CI
  (`.github/workflows/api.yml`) ganhou serviço Postgres efêmero com as credenciais do
  `docker-compose.yml`. `postgres:18-alpine` real usado nos testes locais deste ambiente (Docker
  funcionando no sandbox).

## Fase B — Identidade

### API-T4 — Google auth + Play Integrity decode client — ✅ Concluída (2026-07-02)
- **What**: módulo `google`: OAuth de service account (JWT assinado → access token, cache até
  expirar) + client do `decodeIntegrityToken`; validação de verdicts do design (§Identidade) com
  flag de config p/ relaxar em build de teste. **Formato conferido na doc oficial** e fixado em
  fixtures wiremock.
- **Where**: `src/google/{auth,integrity}.rs`.
- **Depends on**: API-T3 (só pelo esqueleto; lógica é pura+HTTP). **Covers**: ID-02 (parcial).
- **Done when**: unit — verdicts aceitos/rejeitados por caso (pacote errado, device fora,
  UNRECOGNIZED com/sem flag); integração wiremock — token OAuth cacheado, decode ok/erro.
- **Tests**: unit + integração (wiremock). **Gate**: `rust`.
- **Nota de execução**: formato conferido nas fontes oficiais do Google (não fabricado) — fluxo
  JWT Bearer de service account em
  `developers.google.com/identity/protocols/oauth2/service-account` (header RS256, claim set
  iss/scope/aud/exp/iat, POST `https://oauth2.googleapis.com/token` form-encoded,
  `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`) e o contrato exato do
  `decodeIntegrityToken` (`POST /v1/{packageName}:decodeIntegrityToken`, schemas
  `DecodeIntegrityTokenRequest`/`Response`/`TokenPayloadExternal`) direto da discovery doc
  (`playintegrity.googleapis.com/$discovery/rest?version=v1`) + verdicts em
  `developer.android.com/google/play/integrity/verdicts`. `google::auth::TokenCache` cacheia o
  access token com margem de 30s antes de expirar (testado: reuso, renovação, erro do endpoint).
  `google::integrity::verify_verdicts` é função pura (pacote/`appRecognitionVerdict`/
  `deviceRecognitionVerdict`, com `relax_app_recognition` só bypassando o app-recognition — nunca
  pacote ou device) — 6 unit tests cobrindo os casos do "Done when". `IntegrityClient` recebe
  `base_url` explícito (não hardcoded) para apontar pro wiremock em teste e para produção via
  config na T5. Chave RSA de teste descartável gerada com `openssl` em
  `tests/fixtures/test_service_account_key.pem` (nunca uma credencial real — só para assinar JWT
  contra o wiremock). `jsonwebtoken` precisou da feature `aws_lc_rs` explícita (crate não escolhe
  provider de crypto sozinho desde a 10.x) — mesmo backend que o `reqwest`/`rustls` já usavam,
  evita duas libs de crypto concorrentes no binário. Nada foi wireado em `Config`/rotas ainda
  (fica pra API-T5, quando `POST /v1/register` de fato constrói o `IntegrityClient` a partir do
  ambiente — evita campos de config nunca lidos até lá).

### API-T5 — `POST /v1/register` + middleware Bearer — ✅ Concluída (2026-07-02)
- **What**: registro do design (Integrity → conta por `deviceKey` novo/existente → token novo
  hasheado) + middleware que resolve `Bearer` → `Account` (401 sem/inválido). Re-registro
  rotaciona token e preserva saldo (fluxo de recuperação).
- **Where**: `src/routes/register.rs`, `src/auth.rs`.
- **Depends on**: API-T3, API-T4. **Covers**: ID-01, ID-02, ID-03.
- **Done when**: integração — registro com Integrity válido → token funciona em rota protegida;
  inválido → 403; re-registro do mesmo `deviceKey` → mesma conta, token antigo morto; schema não
  guarda nada além do design (assert nas colunas).
- **Nota de execução**: `AppState` (novo, `Arc` por dentro) substitui `PgPool` cru como estado do
  axum — carrega `pool` + `IntegrityClient` + `package_name` + `integrity_relaxed`; `Config`
  ganhou os 3 campos que isso consome de verdade agora (`GOOGLE_SERVICE_ACCOUNT_JSON_PATH`,
  `PACKAGE_NAME`, `INTEGRITY_RELAXED`, deferidos da T4 até terem consumidor real). Middleware
  Bearer→Account implementado como **extractor** (`impl FromRequestParts<AppState> for Account`,
  não `tower::Layer`) — equivalente funcionalmente (401 sem/inválido) e mais idiomático em axum
  0.8; qualquer handler que pedir `Account` como parâmetro já fica protegido. `access_token`: 32
  bytes de `rand::fill` em base64url (43 chars), hash SHA-256 é o único valor persistido
  (`db::accounts.token_hash`); vetor de teste SHA-256("") conferido contra valor bem conhecido
  (não fabricado). Re-registro (`db::accounts::rotate_token`) preserva `id`/saldo/entitlements —
  só troca `token_hash`, então o token antigo para de resolver e o saldo não se move. `GET
  /v1/account` real (T7) ainda não existe, então o "rota protegida" do done-when foi provado
  batendo o token novo direto em `db::accounts::find_by_token_hash` (equivalente ao que o
  extractor faz) + um teste dedicado (`missing_bearer_is_rejected_with_401`) que monta um router
  efêmero só com o extractor sobre o mesmo `AppState` real — nenhuma rota de produção nova além de
  `/v1/register` foi criada aqui (T7 é dona da primeira rota de negócio protegida). **Achado
  corrigido antes de virar flake em CI**: os testes de `db/accounts.rs` usavam `token_hash`
  literais fixos (`vec![1u8;32]` etc.) — coerente na primeira execução, mas como o Postgres de
  dev/CI acumula linhas entre rodadas (nada dá `TRUNCATE`) e `token_hash` não é `UNIQUE` no
  schema, rodadas repetidas passaram a resolver a linha errada (`fetch_optional` sem `ORDER BY`
  não garante qual "primeira" linha volta). Trocado por hash aleatório (`Uuid::new_v4()`) por
  execução, mesmo padrão já usado em `tests/common/mod.rs`.
- **Tests**: integração + unit (hash/parse do Bearer). **Gate**: `rust`.

### API-T6 — Rate limiting `[P]` — ✅ Concluída (2026-07-02)
- **What**: limite por IP (register) e por conta (rotas autenticadas), parâmetros na config;
  estouro → 429 + `Retry-After`.
- **Where**: `src/middleware/ratelimit.rs`.
- **Depends on**: API-T5. **Covers**: ID-04.
- **Done when**: integração — flood no register (por IP) e no account (por conta) → 429 com
  header; abaixo do limite → passa.
- **Tests**: integração. **Gate**: `rust`.
- **Nota de execução**: `governor` 0.10.4 (`DefaultKeyedRateLimiter<K>`, algoritmo GCRA) — API
  conferida em docs.rs, não fabricada. `RateLimiters` no `AppState` com dois limitadores keyed
  independentes (`IpAddr` pro register, `Uuid` da conta pras autenticadas); `Retry-After`
  calculado de `NotUntil::wait_time_from`, arredondado pra cima e nunca 0. `POST /v1/register`
  ganhou `ConnectInfo<SocketAddr>` (produção liga via
  `into_make_service_with_connect_info` no `main.rs`; testes injetam a extensão direto no
  request). Limites configuráveis via `REGISTER_RATE_LIMIT_PER_MINUTE`/
  `ACCOUNT_RATE_LIMIT_PER_MINUTE` (opcionais, defaults 5 e 60). **"Por conta" ainda não tem rota
  de negócio real** (`GET /v1/account` é a API-T7) — testado em `tests/ratelimit.rs` com o mesmo
  padrão já usado no Bearer (T5): router efêmero com o extractor `Account` real chamando
  `check_account` sobre o `AppState` real, nenhuma rota de produção nova além do que já existia.
  4 testes de integração via HTTP real (`tower::ServiceExt::oneshot`) cobrindo flood por IP →
  429+header, IPs diferentes não se afetam, flood por conta → 429+header — mais 4 unit tests do
  limitador isolado (`middleware::ratelimit`).

### API-T7 — `GET /v1/account` `[P]` — ✅ Concluída (2026-07-02)
- **What**: saldo (segundos) + entitlements (`adsOff`, `skinsUnlocked`) num payload único e barato.
- **Where**: `src/routes/account.rs`.
- **Depends on**: API-T5. **Covers**: PAY-05.
- **Done when**: integração — conta nova → 0/false/false; após seeds de ledger/entitlements →
  valores certos; sem Bearer → 401.
- **Tests**: integração. **Gate**: `rust`.
- **Nota de execução**: handler trivial — o extractor `Account` (T5) já resolveu Bearer→conta
  antes dele rodar, então só serializa `balance_seconds`/`ads_off`/`skins_unlocked` (camelCase) e
  aplica `check_account` do rate limiter (T6, primeira rota de negócio real a usá-lo — os testes
  da T6 tinham provado o mecanismo num router efêmero, agora há uma rota de produção de verdade).
  4 testes de integração via HTTP real: conta nova zerada, valores refletindo um `UPDATE` direto
  na tabela (equivalente ao que a T8 fará via `credit`/entitlements), 401 sem Bearer e com Bearer
  inválido. Fecha a **Fase B (Identidade)** — API-T3..T7 completas.

## Fase C — Compras

### API-T8 — `POST /v1/purchases/redeem` (validação + idempotência + entitlements) — ✅ Concluída (2026-07-02)
- **What**: fluxo do design §Compras: Integrity → `purchases.products.get` (wiremock) → PENDING
  sem crédito / PURCHASED → tx idempotente por `token_hash` → crédito (minutos do catálogo na
  config) + entitlements se SKU ≥ R$20 → acknowledge. `obfuscatedExternalAccountId` conferido
  contra `sha256hex(deviceKey)[..64]`.
- **Where**: `src/routes/redeem.rs`, `src/google/publisher.rs`.
- **Depends on**: API-T4, API-T5 (+catálogo da config; shape mínimo pode ser embutido até T10).
- **Covers**: PAY-01, PAY-02, PAY-03, PAY-04.
- **Done when**: integração — compra válida credita 1×; replay → resultado original sem crédito
  novo; produto/conta errados → rejeitado; PENDING → "pending" sem crédito; `credits_20` liga
  entitlements, `credits_10` não; acknowledge chamado no wiremock.
- **Tests**: integração + unit (regra de entitlements/catálogo). **Gate**: `rust`.
- **Nota de execução**: contrato de `purchases.products.get`/`...acknowledge` conferido na
  discovery doc oficial (`androidpublisher.googleapis.com/$discovery/rest?version=v3`, schemas
  `ProductPurchase`/`ProductPurchasesAcknowledgeRequest`) — `purchaseState` é **inteiro** (0
  Purchased, 1 Canceled, 2 Pending), não string como a prosa do design sugeria; usei os valores
  reais da API. `src/catalog.rs`: catálogo estático (`credits_10/20/50`, `unlock_forever`) —
  **minutos são placeholder** explicitamente marcado no código (Yan calibra depois, sem release,
  vira remoto na T10). Idempotência: `INSERT ... ON CONFLICT (token_hash) DO NOTHING RETURNING
  granted_entitlements` na mesma tx do `credit()` (savepoint, T3) — `Some` = primeira vez (credita
  + liga entitlements + `acknowledge` depois do commit); `None` = replay, sem crédito novo e sem
  acknowledge de novo (testado com `wiremock` `.expect(1)`, provando que a 2ª chamada não bate no
  Google). `device_key_account_id` (novo, `auth.rs`) implementa `sha256hex(deviceKey)`,
  compartilhável com a `monetization` (mesma função, mesmo hash — risco documentado no design).
  `db::accounts::Account` ganhou o campo `device_key` (precisava dele pra essa checagem). 10 testes
  novos (4 unit de catálogo/publisher-client + 6 integração via HTTP real: compra válida+ack,
  `credits_10` sem entitlements, replay sem crédito duplo, PENDING, conta errada, produto
  desconhecido).

### API-T9 — Job de voided purchases — ✅ Concluída (2026-07-02)
- **What**: task tokio periódica (intervalo na config): `voidedpurchases.list` (wiremock) → p/
  cada token conhecido, tx `revoke` (saldo pode negativar) + recomputa entitlements das compras
  restantes. Idempotente entre execuções (voided já revertido não reverte 2×).
- **Where**: `src/jobs/voided.rs`.
- **Depends on**: API-T8. **Covers**: PAY-06.
- **Done when**: integração — reembolso reverte crédito e entitlements; saldo negativo bloqueia
  proxy (asserção junto com T11 ou via helper); rodar o job 2× não duplica reversão.
- **Tests**: integração + unit (recomputação de entitlements). **Gate**: `rust`.
- **Nota de execução**: contrato de `voidedpurchases.list` conferido na discovery doc oficial
  (schemas `VoidedPurchasesListResponse`/`VoidedPurchase`); sem paginação — a janela seguinte do
  job cobre o que ficar de fora (`startTime` default do Google já é "agora - 30 dias"). Idempotência
  via `UPDATE purchases SET status='voided' WHERE token_hash=$1 AND status != 'voided' RETURNING
  ...` (mesmo padrão do `debit_guarded` da T3: o `UPDATE` condicional detecta se já foi
  processado). Entitlements recomputados **do zero** a cada void (`EXISTS` sobre as compras
  restantes não-voided com `granted_entitlements=true`), não decrementados — evita drift quando
  há mais de uma compra concedendo entitlements na mesma conta (testado). `run_periodically`
  nunca para em erro (loga e segue pra próxima janela — reembolso é melhor-esforço, não bloqueia
  request). `main.rs` sobe o job via `tokio::spawn` com uma `PublisherClient`/`TokenCache`
  própria (separada da que atende requests). **"Saldo negativo bloqueia proxy"** do done-when
  fica para a T11 de fato bloquear (o mecanismo — saldo pode ficar negativo e `debit_guarded`
  rejeita nesse caso — já está testado desde a T3/aqui; só falta a rota do proxy existir). 5
  testes novos (1 unit de `list_voided_purchases` + 4 integração: reembolso reverte crédito e
  entitlements, saldo pode negativar, job 2× não duplica, entitlements sobrevivem se outra compra
  ainda os concede). Fecha a **Fase C (Compras)** — API-T8/T9 completas.

## Fase D — Conteúdo

### API-T10 — `GET /v1/models` + `GET /v1/config` (ETag) `[P]` — ✅ Concluída (2026-07-02)
- **What**: servir `documents` com ETag sha256 + `If-None-Match`/304 + `Cache-Control`; seeds de
  dev com o shape do design (products/ads/transcription/minAppVersion/killSwitch; manifest de
  modelos espelhando o `ModelCatalog` do app — ids/URLs/sha256 reais quando a native-voice-models
  já tiver colhido, senão placeholder marcado).
- **Where**: `src/routes/content.rs`, `migrations/seed*`.
- **Depends on**: API-T3. **Covers**: MDL-01, CFG-01.
- **Done when**: integração — 200 com ETag; If-None-Match → 304; body bate com o seed; unit do
  cálculo de ETag.
- **Tests**: integração + unit. **Gate**: `rust`.
- **Nota de execução**: `migrations/0002_seed_documents.sql` semeia `config`/`models`; o shape do
  `config` segue o design (`products`/`ads`/`transcription`/`minAppVersion`/`killSwitch`) —
  **valores de `ads`/`transcription` e o manifest de `models` são placeholder explícito** (chave
  `"note"` no próprio JSON), já que `native-voice-models` (Bloco 4 do
  `DEVELOPMENT-ORDER.md`) ainda não colheu os modelos reais nem o Yan calibrou os caps de
  anúncio. ETag: `serde_json::to_vec` do `Value` lido do Postgres vira **os mesmos bytes** do
  corpo e a base do sha256 — garante que o header sempre bate com o que foi enviado (calculado
  em request-time, não armazenado; simples o bastante pro alvo de latência do design, é 1 SELECT
  de linha única). `If-None-Match` aceita `*` ou o valor exato (com aspas, formato padrão HTTP);
  10 testes novos (5 unit de ETag/matching + 5 integração via HTTP real, incluindo ETag estável
  entre duas requests). Rotas **sem Bearer** (conteúdo público, como o design já indicava
  implicitamente ao não mencionar auth pra elas). Abre a **Fase E** (proxy) em paralelo — T10 era
  a única dependência restante de T3 nessa frente.

## Fase E — Proxy

### API-T11 — `POST /v1/transcribe` (streaming + débito atômico + 402/413 + fallback) — ✅ Concluída (2026-07-03)
- **What**: fluxo completo do design §Proxy: saldo ≤ 0 → 402 com saldo; stream axum→reqwest sem
  bufferizar (TRX-04) com contador de bytes → 413 no cap; débito `ceil(bytes/8000)` (tabela na
  config) **na mesma decisão da resposta de sucesso** (erro/timeout → não debita); idempotência
  por `X-Request-Id`; header `X-KeyFlow-Balance-Seconds` na resposta; timeout → 504 distinguível;
  fallback de provedor em falha de conexão/5xx.
- **Where**: `src/routes/transcribe.rs`.
- **Depends on**: API-T5, API-T10 (config de providers/caps).
- **Covers**: TRX-01, TRX-02, TRX-03, TRX-04, TRX-05.
- **Done when**: integração (wiremock como "provedor") — 60s de saldo + upload de "10s" → saldo
  50s + `{"text":...}` repassado + header de saldo; sem saldo → 402; cap → 413; provedor caindo →
  fallback usado; provedor final falhando → erro sem débito; retry com mesmo `X-Request-Id` não
  debita 2×; memória não cresce com upload grande (teste com body chunked grande + limite de
  alocação verificado por code review documentado — sem `to_vec()` do body no caminho).
- **Tests**: integração + unit (duração por bytes/roundup). **Gate**: `rust`.
- **SPEC_DEVIATION documentado (arquitetura do fallback)**: o design pede "falha de conexão/5xx
  imediato → tenta o próximo [provedor]" — mas isso é **incompatível** com TRX-04 ("nunca
  bufferizar") depois que o corpo já começou a ser streamado pro provedor 1: não existe como
  "rebobinar" um `reqwest::Body::wrap_stream` consumido parcialmente e reenviar pro provedor 2 sem
  materializar o corpo inteiro em memória, o que contradiria o próprio requisito que motiva a
  arquitetura de streaming. Resolvido com um **circuit breaker entre requests**
  (`providers::ProviderHealth`): provedor que falha (conexão/5xx) fica indisponível por
  `PROVIDER_UNHEALTHY_COOLDOWN_SECS`; a *próxima* request pula pro próximo da lista. Em regime
  permanente o efeito é equivalente ao fallback do design (toda request após a 1ª falha já usa o
  saudável) — só a request que descobriu a falha não tem retry intra-request; recebe `502` e cabe
  ao cliente decidir reenviar. Detalhe completo no `README.md` da API (§Arquitetura).
  Testado (`provider_5xx_marks_unhealthy_and_next_request_falls_back`): 1ª request no provedor
  caído → 502 sem débito; 2ª request (novo `X-Request-Id`) → vai direto pro fallback e sucede.
- **Nota de execução**: `TRANSCRIPTION_PROVIDERS_JSON` (novo, obrigatório) tipa a "lista ordenada
  `[{name,url,authHeaderName,authValueEnv,model}]`" do design literalmente — o segredo de cada
  provedor mora em OUTRA env var (nome referenciado por `authValueEnv`), nunca inline (OPS-03);
  parseado/resolvido em `src/providers.rs`. Corpo de entrada é passthrough puro — **não faz parse
  de multipart** (não precisa: o design já pede "repassado em streaming", então contar bytes +
  encaminhar os mesmos chunks pro provedor via `reqwest::Body::wrap_stream` preserva 100% a
  estrutura multipart original sem tocar nela). Cap (413) detectado via um erro synthetic
  (`StreamCapExceeded`) que o stream contador injeta e a handler reconhece andando pela cadeia
  `source()` do `reqwest::Error` — distingue "corpo grande demais" de "provedor caiu". 402 usa o
  saldo já resolvido pelo extractor `Account` (sem re-`SELECT`) — a corrida entre duas
  transcrições simultâneas da mesma conta é aceita pelo próprio design ("saldo pode negativar
  levemente; o próximo uso leva 402"). Idempotência: `INSERT INTO transcribe_requests ... ON
  CONFLICT DO NOTHING` decide se debita, mas o provedor é **sempre** chamado de novo no retry
  (não persistimos texto, ID-03). `credit()` (T3) passou a aceitar delta negativo pra esse débito
  sem guarda — doc-comment atualizado, mecânica não mudou. **Teste de memória por RSS de processo
  foi tentado e descartado**: cresceu ruidosamente mais que o esperado por confundir com
  overhead do alocador/runtime/pool de conexões da própria execução do teste, não por buffer real
  do corpo — mesma lição da API-T2 (teste flaky por causa de infraestrutura de teste, não bug);
  TRX-04 é garantido por **inspeção de código** (nenhum `to_vec()`/`.bytes()` no caminho do corpo
  de entrada, só `body.into_data_stream()` repassado direto), com um teste funcional (upload de
  5 MB) provando que segue funcionando corretamente em maior escala. 9 testes de integração novos
  + 1 unit (arredondamento bytes→segundos) + testes de `providers.rs` (parsing/circuit breaker).

## Fase F — Rewarded

### API-T12 — `GET /v1/ssv/admob` (ECDSA + cap diário) — ✅ Concluída (2026-07-03)
- **What**: verificação ECDSA da query (chaves públicas do AdMob cacheadas, refresh por `key_id`
  desconhecido), `custom_data` → conta, crédito idempotente por `transaction_id` com **cap
  diário** da config. Inválido → 403.
- **Where**: `src/routes/ssv.rs`.
- **Depends on**: API-T5, API-T10.
- **Covers**: ADS-01.
- **Done when**: unit — assinatura com vetor de teste (chave própria gerada no teste) passa/falha;
  integração — crédito 1× por transaction_id; cap diário corta; conta inexistente → 200 sem
  crédito (não vazar existência) com log.
- **Tests**: unit + integração. **Gate**: `rust`.
- **Nota de execução**: algoritmo e endpoint conferidos na doc oficial
  (developers.google.com/admob/android/rewarded-video-ssv) — conteúdo assinado é a query string
  crua **até** `&signature=` (nunca reordenar/reserializar), `signature`/`key_id` sempre por
  último nessa ordem; ECDSA **P-256**/SHA-256, assinatura em **DER**; chaves em
  `https://www.gstatic.com/admob/reward/verifier-keys.json` (confirmado via `curl` real durante a
  task — `www.` importa, `gstatic.com` sem `www` dá 301). `p256` 0.13.2 (última estável; a 0.14 só
  tem release candidates nesta data) — `Signature::from_der`/`VerifyingKey::from_public_key_pem`
  funcionam com as features default do crate (`pkcs8` já traz `der` transitivamente). Cache
  (`AdmobKeysCache`) refetch completo (não incremental) quando um `key_id` desconhecido aparece —
  cobre rotação sem precisar de timer. `ADS_REWARD_SECONDS` é decisão nossa (config), não vem do
  `reward_amount` do AdMob (que só reflete o valor configurado no console AdMob, sem relação com
  segundos). `credit_reward` faz o cap diário com um `SELECT count(*)` antes do `INSERT ... ON
  CONFLICT DO NOTHING` na mesma tx — pequena janela de corrida entre duas callbacks quase
  simultâneas perto do limite é aceita (rewarded ads são baixa frequência; diferente do débito de
  transcrição, o design não pede guarda atômica aqui). Sempre responde `200` (Google reenvia até
  5× sem isso) exceto assinatura inválida (`403`, conforme a task). **Achado corrigido antes de
  virar bug**: a 1ª versão só validava que `custom_data` era um UUID sintaticamente válido, sem
  checar se a conta existia — um UUID bem-formado de conta inexistente estourava a FK do `ledger`
  em 500 em vez do `200 sem crédito` esperado; adicionado `db::accounts::exists`. **Achado
  corrigido (mesma classe do bug da T5)**: os testes usavam `transaction_id` literais fixos
  ("tx-1", "tx-a"...) — coincidiam com o PK entre execuções no Postgres de dev (nunca truncado),
  então rodadas repetidas paravam de creditar (idempotência "vazando" entre execuções de teste,
  não da API); trocado por sufixo `Uuid::new_v4()`. Smoke real via `cargo run`: a chamada ao
  `gstatic.com` de verdade (latência ~700ms, condizente com rede real) confirmou que o cache busca
  as chaves reais do Google e rejeita corretamente uma assinatura forjada com `key_id`
  inexistente. 6 testes de integração + 6 unit de `admob.rs` (assinatura válida/adulterada/chave
  errada/`key_id` desconhecido/params ausentes). Fecha a **Fase F (Rewarded)**.

## Fase G — Deploy & e2e

### API-T13 — Deploy (TLS/secrets/Postgres) + smoke em staging — 🟡 Parcial (2026-07-03) — **[YAN]**

> **Nota (2026-10-23)**: o alvo da v1 é AWS, staging e produção, na menor e mais barata máquina
> (plano em `api/docs/aws.md`; máquina e região ainda pendentes). DNS/TLS pela Cloudflare. A rota
> de transcrição (API-T11) e a SSV (API-T12) ficam desligadas na v1. Ver V1-9 no
> `DEVELOPMENT-ORDER.md`.

- **What**: Dockerfile de release; guia de deploy (reverse proxy com TLS, envs/secrets, Postgres
  gerenciado ou host, job ativo); subir staging; smoke: health/config/models/register (com
  Integrity **relaxado por flag** se a credencial ainda não existir — documentar o que ficou
  pendente de credencial real). **Parte com credenciais reais (service account, chave de
  provedor, domínio/TLS) é do Yan** — a task prepara e documenta cada passo.
- **Where**: `api/Dockerfile`, `api/docs/deploy.md`, infra do Yan.
- **Depends on**: API-T6..T12. **Covers**: OPS-03, OPS-04.
- **Done when**: staging responde health/config/models via HTTPS; secrets só por ambiente;
  checklist de credenciais pendentes entregue ao Yan.
- **Tests**: smoke manual documentado. **Gate**: `rust` + manual.
- **Feito nesta sessão (sem depender de credencial)**: `api/Dockerfile` multi-stage
  (`rust:1.96-slim-bookworm` → `debian:bookworm-slim`, tags conferidas no Docker Hub — não
  fabricadas; migrations embutidas no binário via `sqlx::migrate!()`, sem comando separado no
  boot; só `ca-certificates` no runtime, já que `rustls`/`sqlx-postgres` são puro-Rust e não
  precisam de `libssl`/`libpq` do sistema — mas `rustls-platform-verifier` usa o trust store do
  SO, daí `ca-certificates` continuar necessário). **Validado de verdade**: `docker build` local
  (imagem final 101 MB) + `docker run` contra o Postgres do `docker-compose.yml` — `/health` e
  `/v1/config` responderam `200` de ponta a ponta, migrations aplicaram no boot. `api/docs/
  deploy.md`: guia completo (reverse proxy/TLS com exemplo de Caddyfile, tabela de env vars com
  origem de cada uma, service account do Google — quais APIs habilitar e como vincular ao Play
  Console, checklist de credenciais pendentes).
- **Parou no gate [YAN]** (conforme `DEVELOPMENT-ORDER.md` — "credenciais reais... é do Yan"):
  **não** subiu staging de verdade nem tentou nenhuma ação de infraestrutura real (domínio,
  Postgres gerenciado, DNS, secrets em produção) — são ações com efeito fora deste
  ambiente/sandbox, fora do escopo de algo que se prepara e valida sozinho. Pendências exatas no
  checklist do fim de `api/docs/deploy.md`: Postgres de staging, domínio/DNS, service account do
  Google (Play Integrity + Developer API habilitadas e vinculada ao app no Play Console),
  provedor(es) de transcrição + chaves, unidade de anúncio rewarded no AdMob configurada, e o
  smoke de `POST /v1/register` com device físico (só é testável com um `integrityToken` real, que
  não dá pra fabricar fora do fluxo real do Play). **API-T14 seguirá bloqueada** até isso e até
  MZ-T1..T8 no app (ver `DEVELOPMENT-ORDER.md`).

### API-T14 — e2e com o app + traceability
- **What**: com a `monetization` (MZ) implementada no app: rodar o Independent Test de cada área
  da spec contra o staging (registro/compras de teste do Play/transcrição/débito/402/reembolso/
  SSV de teste); ajustar traceability do `spec.md` → Verified. Cruza com MZ-T15 (mesma sessão de
  validação; ver `DEVELOPMENT-ORDER.md`).
- **Where**: staging + device físico do Yan; `spec.md`.
- **Depends on**: API-T13 + monetization MZ-T1..T8 no app.
- **Covers**: success criteria da spec.
- **Done when**: fluxos e2e documentados; requisitos → Verified (os que dependem de credencial/
  produção anotados como tal).
- **Tests**: manual. **Gate**: manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — | raiz A | ✅ |
| T2 | T1 | T1→T2 | ✅ |
| T3 | T2 | T2→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T3, T4 | T4→T5 | ✅ |
| T6 | T5 | T5→T6 `[P]` | ✅ |
| T7 | T5 | T5→T7 `[P]` | ✅ |
| T8 | T4, T5 | (T4,T5)→T8 | ✅ |
| T9 | T8 | T8→T9 | ✅ |
| T10 | T3 | T3→T10 `[P]` | ✅ |
| T11 | T5, T10 | (T5,T10)→T11 | ✅ |
| T12 | T5, T10 | (T5,T10)→T12 | ✅ |
| T13 | T6..T12 | →T13 | ✅ |
| T14 | T13 (+MZ do app) | T13→T14 | ✅ |

### Cobertura de requisitos
| Req | Tasks |
| --- | ----- |
| ID-01..04 | T5 (01/02/03), T6 (04), T4 (02) |
| PAY-01..06 | T8 (01..04), T7 (05), T9 (06) |
| TRX-01..05 | T11 |
| MDL-01, CFG-01 | T10 |
| ADS-01 | T12 |
| OPS-01..04 | T3 (01), T2 (02/03), T13 (03/04), T1 (04) |

**Saída**: backend Rust em staging com identidade anônima segura, compras validadas e
idempotentes, ledger transacional, proxy de transcrição streaming com débito por segundo,
manifest/config remota cacheáveis e SSV de rewarded — pronto p/ o app (`monetization`) consumir.

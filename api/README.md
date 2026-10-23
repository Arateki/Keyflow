# KeyFlow API

Backend Rust (axum) do KeyFlow — identidade anônima, créditos/ledger, compras, proxy de
transcrição e conteúdo remoto. Spec/design/tasks completos em
`.specs/features/keyflow-api/` na raiz do monorepo (não duplicar aqui).

Módulo disjunto do app Android (`app/`): o Gradle não enxerga esta pasta e vice-versa.

## Setup

- Rust stable (edição 2024).
- Postgres local para testes de integração: `docker compose up -d` (sobe na porta `5432`,
  usuário/senha/banco `keyflow`).
- Variáveis de ambiente (ver `src/config.rs`):
  - `DATABASE_URL` (obrigatória) — string de conexão Postgres. Ausente → erro claro no boot
    (`missing required environment variable: DATABASE_URL`), sem stack trace genérico.
  - `BIND_ADDR` (opcional, default `0.0.0.0:8080`; `VAR=""` conta como ausente) — endereço:porta
    do axum.
  - `RUST_LOG` (opcional, default `info,tower_http=debug`) — diretivas do `tracing_subscriber`;
    o default já deixa a latência por rota (`tower_http::trace`) visível sem precisar setar nada.
  - `GOOGLE_SERVICE_ACCOUNT_JSON_PATH` (obrigatória) — caminho do JSON da service account do
    Google (campos usados: `client_email`, `private_key`, `token_uri`); usada para assinar o JWT
    do fluxo OAuth (Play Integrity). **Credencial real é do Yan** (API-T13); localmente, qualquer
    JSON com esse shape e uma chave RSA válida serve para o processo subir (só falha de verdade
    ao chamar o Google).
  - `PACKAGE_NAME` (obrigatória) — package name do app Android (ex.: `com.keyflow`), conferido
    contra `requestPackageName` do Play Integrity.
  - `INTEGRITY_RELAXED` (opcional, default `false`; `true`/`1` liga) — relaxa só o
    `appRecognitionVerdict` (builds de teste/interno); nunca relaxa pacote nem device.
  - `REGISTER_RATE_LIMIT_PER_MINUTE` (opcional, default `5`) — limite de `POST /v1/register` por
    IP (ID-04). `ACCOUNT_RATE_LIMIT_PER_MINUTE` (opcional, default `60`) — limite por conta nas
    rotas autenticadas. Estouro → `429` + header `Retry-After` (segundos).
  - `TRUSTED_PROXIES` (opcional, default vazio) — IPs ou CIDRs, separados por vírgula, de onde
    `CF-Connecting-IP` pode substituir o IP do socket no limite de `/v1/register`. Sem esta
    variável o header é ignorado (um cliente não forja o IP). IP sem máscara vale como `/32` ou
    `/128`.
  - `DB_MAX_CONNECTIONS` (opcional, default `5`), `DB_MIN_CONNECTIONS` (opcional, default `0`,
    não pode passar do máximo), `DB_IDLE_TIMEOUT_SECS` (opcional, default `300`),
    `DB_ACQUIRE_TIMEOUT_SECS` (opcional, default `5`) — pool do Postgres.
  - `TOKIO_WORKER_THREADS` (opcional, default `2`), `TOKIO_MAX_BLOCKING_THREADS` (opcional,
    default `8`) — runtime do processo. Ambos ≥ 1.
  - `PLAY_INTEGRITY_BASE_URL` e `ANDROID_PUBLISHER_BASE_URL` (opcionais) — caem nas URLs oficiais
    do Google. O teste de carga aponta as duas para um mock.
  - `TRANSCRIBE_ENABLED` e `SSV_ADMOB_ENABLED` (opcionais, padrão desligado; `true`/`1` ligam,
    `VAR=""` conta como ausente) — uma flag por rota. Na v1 as duas nascem desligadas:
    `POST /v1/transcribe` e `GET /v1/ssv/admob` respondem `404` sem consultar o banco. Ligar uma
    não liga a outra.
  - `TRANSCRIPTION_PROVIDERS_JSON` — lista ordenada de provedores Whisper-compatible
    (design §Proxy): `[{"name","url","authHeaderName","authValueEnv","model"}]`. `authValueEnv`
    é o **nome de outra env var** que guarda o segredo de verdade (nunca inline no JSON — OPS-03).
    Obrigatória só com `TRANSCRIBE_ENABLED` ligado; nesse caso a env da chave também é. Com a
    rota desligada as duas são ignoradas (JSON vazio, ausente ou sem chave não impede o boot).
    Exemplo, quando a rota está ligada: `[{"name":"openai","url":"https://api.openai.com/v1/audio/transcriptions",
    "authHeaderName":"Authorization","authValueEnv":"OPENAI_KEY","model":"whisper-1"}]` com
    `OPENAI_KEY=Bearer sk-...` setada à parte. A SSV não acrescenta variável obrigatória:
    `ADS_REWARD_SECONDS` e `ADS_DAILY_CAP` têm padrão, e a URL das chaves do AdMob não é env.
  - `TRANSCRIPTION_TIMEOUT_MS` (opcional, default `30000`), `TRANSCRIPTION_MAX_BYTES` (opcional,
    default `26214400` = 25 MiB), `PROVIDER_UNHEALTHY_COOLDOWN_SECS` (opcional, default `60`).
  - `ADS_REWARD_SECONDS` (opcional, default `60`) — segundos creditados por rewarded ad
    verificado (decisão nossa, não vem do `reward_amount` do AdMob). `ADS_DAILY_CAP` (opcional,
    default `5`) — máximo de recompensas por conta por dia (ADS-01).

## Rotas

- `GET /health` — `SELECT 1` no Postgres.
- `POST /v1/register` — `{deviceKey, integrityToken}` → `{accessToken}` (403 `{"error":
  "integrity"}` se o Play Integrity rejeitar). Re-registro do mesmo `deviceKey` rotaciona o token.
- `GET /v1/account` (Bearer) — `{balanceSeconds, adsOff, skinsUnlocked}`.
- `POST /v1/purchases/redeem` (Bearer) — `{productId, purchaseToken, integrityToken}` →
  `{status: "credited"}` ou `{status: "pending"}`. Catálogo estático em `src/catalog.rs`
  (`credits_10/20/50`, `unlock_forever`; minutos são **placeholder** até o Yan calibrar — vira
  remoto na API-T10). Idempotente por `purchaseToken`; SKUs ≥ R$20 ligam `adsOff`+`skinsUnlocked`.
- `GET /v1/config` / `GET /v1/models` — `documents.body` (seed em `migrations/
  0002_seed_documents.sql`, editável só por SQL/migration no MVP). `ETag` = sha256 hex do corpo
  entre aspas; `If-None-Match` (valor exato ou `*`) → `304` sem corpo; `Cache-Control: max-age=300`.
- `POST /v1/transcribe` (Bearer, header `X-Request-Id` obrigatório) — corpo `multipart/form-data`
  **repassado em streaming, byte a byte, pro provedor** (nunca bufferizado — TRX-04; ver
  `src/routes/transcribe.rs`). Saldo ≤ 0 (e request nova) → `402 {"balanceSeconds"}` antes de
  tocar o provedor; corpo > `TRANSCRIPTION_MAX_BYTES` → `413`; timeout → `504`; provedor caindo
  (conexão/5xx) → `502` + marca o provedor indisponível por `PROVIDER_UNHEALTHY_COOLDOWN_SECS`
  (fallback é **entre requests**, não dentro da mesma — ver nota de arquitetura abaixo). Sucesso:
  repassa o JSON do provedor (`{"text":...}`) + header `X-KeyFlow-Balance-Seconds`; debita
  `ceil(bytes/8000)` segundos numa tx atômica com o registro de idempotência
  (`transcribe_requests`, chave `X-Request-Id`) — retry com o mesmo id re-executa o provedor mas
  não debita 2×.
- `GET /v1/ssv/admob` — callback de rewarded SSV do AdMob (design §Rewarded, ADS-01). Verifica
  ECDSA P-256/SHA-256 (assinatura DER) contra a chave pública de `key_id`, buscada e cacheada de
  `https://www.gstatic.com/admob/reward/verifier-keys.json` (refetch automático se `key_id` for
  desconhecido). Assinatura inválida → `403`. Assinatura válida: `custom_data` é o `accountId`
  (UUID); conta inexistente/`custom_data` inválido → `200` sem crédito, só loga (não vazar
  existência). Crédito de `ADS_REWARD_SECONDS` idempotente por `transaction_id`, sujeito a
  `ADS_DAILY_CAP` por conta/dia — sempre responde `200` mesmo quando o cap bloqueia (é assim que
  o AdMob espera: sem `200`, ele reenvia a callback até 5×).

### Arquitetura: por que o fallback de provedor é entre requests, não dentro da mesma

O design pede "falha de conexão/5xx imediato → tenta o próximo" provedor. Isso é **incompatível**
com TRX-04 ("nunca bufferizar"): uma vez que o corpo da request já foi parcialmente consumido e
repassado como stream pro provedor 1, não há como "rebobinar" e reenviar pro provedor 2 sem
materializar o corpo inteiro em memória — contradiria o próprio requisito que motiva a arquitetura
de streaming. Decisão registrada em `.specs/features/keyflow-api/tasks.md` (API-T11): um provedor
que falha fica marcado indisponível por um cooldown (`providers::ProviderHealth`, circuit
breaker simples); a **próxima** request pula direto pro próximo da lista. Em regime permanente o
efeito é equivalente ao fallback do design (toda request após a 1ª falha já usa o provedor
saudável) — só a request que descobriu a falha não tem retry automático; ela recebe erro e cabe
ao cliente decidir reenviar.

## Job em background

`jobs::voided::run_periodically` (disparado no `main.rs` via `tokio::spawn`) consulta
`voidedpurchases.list` a cada `VOIDED_PURCHASES_JOB_INTERVAL_SECS` (opcional, default `3600`) e
reverte compras canceladas/reembolsadas: debita o ledger (saldo pode negativar — sem guarda,
igual a um reembolso de verdade) e recomputa `adsOff`/`skinsUnlocked` a partir das compras que
restaram válidas. Idempotente (`purchases.status` vira `'voided'`, nunca reverte 2×); erro numa
execução só loga e tenta de novo na próxima janela (não é caminho crítico de request).

```bash
DATABASE_URL=postgres://keyflow:keyflow@localhost/keyflow_api \
GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/path/to/service-account.json \
PACKAGE_NAME=com.keyflow \
cargo run
```

Logs saem em **JSON estruturado** (um objeto por linha), com a rota casada (`matched_path`) e a
latência da request no evento de resposta:

```json
{"timestamp":"...","level":"DEBUG","fields":{"message":"finished processing request","latency":"0 ms","status":200},"target":"tower_http::trace::on_response","span":{"matched_path":"/health","method":"GET","name":"http_request"}}
```

## Gate `rust`

Rodar sempre dentro de `api/`, com o Postgres do `docker-compose.yml` no ar (os testes de
integração precisam de `DATABASE_URL` — rodam as migrations sozinhos a cada teste):

```bash
docker compose up -d
DATABASE_URL=postgres://keyflow:keyflow@localhost:5432/keyflow_api \
  bash -c 'cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test'
```

CI (`.github/workflows/api.yml`) roda o mesmo gate com path filter `api/**`, subindo um serviço
Postgres efêmero (mesmas credenciais do `docker-compose.yml`).

## Convenções de teste

- **Unit** (`cargo test`, sem I/O externo, em `#[cfg(test)]` dentro do próprio módulo): regras
  puras — validação de config (`config.rs`), validação de verdicts do Play Integrity, duração por
  bytes (arredondamento), cálculo de ETag, seleção de fallback de provedor, verificação ECDSA
  (vetor de teste fixo), regra de entitlements por SKU. **Nunca mutam `std::env` do processo real**
  (env vars são estado global compartilhado entre `#[test]`s paralelos do mesmo binário — testável
  via uma função `from_map`/injeção em vez de `std::env::set_var`).
- **Integração** (arquivos em `tests/`, mesmo `cargo test`, requer `DATABASE_URL` de teste —
  `docker-compose.yml`; helpers comuns em `tests/common/mod.rs`): exercita rotas reais via
  `tower::ServiceExt::oneshot`/servidor real, e os helpers de `db::ledger` direto contra o
  Postgres; Google/provedores externos são substituídos por **wiremock** (nunca chamados de
  verdade em teste). Cada teste cria sua própria conta com `device_key` aleatório (`uuid::new_v4`)
  para não colidir com outros testes rodando em paralelo no mesmo Postgres.
- **Nunca fabricar formato de API externa** (Play Integrity, Play Developer API, SSV do AdMob):
  cada task que integra com essas APIs confere a documentação oficial e fixa o contrato exato
  numa fixture wiremock — ver Knowledge Verification Chain em
  `.specs/features/keyflow-api/design.md`.

## Convenções de código

- `cargo fmt` (config em `rustfmt.toml`) + `clippy -D warnings` sem exceções silenciosas.
- Nenhum secret no repositório — configuração via env (módulo `config`, API-T2).
- Nenhum dado pessoal, áudio ou texto transcrito é persistido — ver `design.md` §Princípio
  condutor.

## Deploy

`Dockerfile` (multi-stage, `rust:1.96-slim-bookworm` → `debian:bookworm-slim`) builda um binário
de release com as migrations embutidas (`sqlx::migrate!()` — nada roda separado no boot além do
próprio processo). Guia completo (reverse proxy/TLS, secrets, Postgres, checklist de credenciais
pendentes do Yan) em [`docs/deploy.md`](docs/deploy.md).

## Status

Ver o `Status` de cada task em `.specs/features/keyflow-api/tasks.md` (fonte da verdade de
progresso desta frente).

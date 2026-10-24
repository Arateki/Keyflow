# KeyFlow API — Design

Arquitetura do backend Rust. Decisões em `context.md` (M1–M6); requisitos em `spec.md`.
**Status**: Draft (aguardando aprovação do Yan).

> **Monorepo (decisão do Yan, 2026-07-02)**: o código vive em **`api/`** na raiz deste
> repositório, irmã de `app/` — Cargo/CI/docker próprios; o Gradle não enxerga a pasta e
> vice-versa. Esta spec permanece em `.specs/features/keyflow-api/`. O módulo Android só é
> tocado pela feature `monetization` (consumidora).

## Princípio condutor

**O ledger no Postgres é a única fonte de verdade; ninguém é confiado sem prova.** O app nunca
decide crédito (só apresenta tokens); o Google nunca é aceito sem validação criptográfica ou
server-to-server (Play Developer API, Play Integrity decode, ECDSA do AdMob). Nenhum dado pessoal,
áudio ou texto transcrito persiste (ID-03) — o schema inteiro é auditável por inspeção.

```
app (Bearer token) ──► axum router
  ├─ POST /v1/register ──────► Play Integrity decode (Google) ──► accounts (+ token)
  ├─ GET  /v1/account ───────► saldo + entitlements (barato, sem Integrity)
  ├─ POST /v1/purchases/redeem ► Play Developer API ──► tx: purchases+ledger+entitlements
  ├─ POST /v1/transcribe ────► stream ──► provedor (chave do servidor) ──► débito atômico
  ├─ GET  /v1/models · GET /v1/config ──► documents (ETag/304)
  ├─ GET  /v1/ssv/admob ─────► ECDSA AdMob ──► tx: rewards+ledger (cap diário)
  └─ GET  /health
job em background: voided purchases (reverte reembolsos — PAY-06)
Postgres: accounts · ledger · purchases · rewards · transcribe_requests · documents
```

## Stack (OPS-01)

- **Rust stable**; **axum** + **tokio**; **sqlx** (Postgres, migrations versionadas); **reqwest**
  (rustls) para Google/provedores; **serde**; **tracing** + subscriber JSON (OPS-02); rate limit
  keyed (crate tipo `governor` ou tower middleware próprio — decidir na task com a chain de
  verificação, não fabricar API de crate).
- Versões exatas dos crates conferidas na API-T1 (docs.rs/crates.io) — nunca de memória.
- TLS termina num reverse proxy na frente (Caddy/nginx/LB do host); o binário fala HTTP no
  loopback. OPS-03 é atendido no deploy (API-T13).

## Identidade & auth (ID-01..04)

- `POST /v1/register` `{deviceKey, integrityToken}`:
  1. Decodifica o token via `playintegrity.googleapis.com/v1/{pkg}:decodeIntegrityToken` (OAuth de
     service account). Verdicts exigidos: `requestPackageName` correto,
     `appRecognitionVerdict = PLAY_RECOGNIZED` (config permite relaxar p/ build de teste/interno) e
     `deviceRecognitionVerdict ⊇ MEETS_DEVICE_INTEGRITY`. Falha → **403** `{"error":"integrity"}`.
  2. `deviceKey` (UUID gerado no device, M2) inédito → cria `account`; existente → **rotaciona o
     token de acesso e devolve a mesma conta** (re-registro É o fluxo de recuperação do edge
     "conta não existe/estado corrompido").
  3. `accessToken`: 32 bytes aleatórios (base64url), devolvido **uma única vez**; armazenado só
     como SHA-256 (`token_hash`). Middleware Bearer resolve `Account` p/ as demais rotas.
- **Onde o Integrity é exigido**: `register` e `redeem` (operações que criam valor). O `transcribe`
  **não** exige Integrity por chamada (Bearer basta) → **indisponibilidade do Google não trava quem
  já tem saldo** (edge da spec decidido aqui; SSV é assinado pelo AdMob, não pelo app).
- **Rate limit (ID-04)**: por IP no `register`; por conta nas demais; estouro → **429** +
  `Retry-After`.

## Schema (Postgres)

```sql
accounts(id uuid pk default gen_random_uuid(),
         device_key   text unique not null,
         token_hash   bytea not null,
         balance_seconds bigint not null default 0,   -- materializado; ledger é a auditoria
         ads_off bool not null default false,
         skins_unlocked bool not null default false,
         created_at timestamptz not null default now())

ledger(id bigserial pk, account_id uuid references accounts,
       delta_seconds bigint not null,
       reason text not null check (reason in ('purchase','transcription','reward','revoke')),
       ref text,                                       -- ex.: product_id, request_id (sem PII)
       created_at timestamptz not null default now())

purchases(token_hash bytea pk,                         -- sha256(purchaseToken): idempotência PAY-04
          account_id uuid, product_id text, status text,
          credited_seconds bigint, granted_entitlements bool,
          created_at timestamptz)

rewards(transaction_id text pk,                        -- idempotência ADS-01
        account_id uuid, seconds bigint, created_at timestamptz)

transcribe_requests(account_id uuid, request_id text,  -- idempotência de débito (edge de retry)
                    seconds bigint, created_at timestamptz,
                    primary key (account_id, request_id))

documents(key text pk, body jsonb not null, updated_at timestamptz)  -- 'models' e 'config'
```

- **Toda mutação de saldo é uma transação**: insert no `ledger` + `UPDATE accounts SET
  balance_seconds = balance_seconds + $delta ...` na mesma tx (crédito/débito nunca parcial,
  OPS-01; corrida de débito resolvida pelo banco).
- Reembolso (`revoke`) atualiza **sem guarda** — saldo pode negativar (spec: bloqueia o proxy até
  nova recarga; consulta continua funcionando).

## Compras (PAY-01..06)

- **Catálogo** em `documents.config.products`: `credits_10/20/50` (minutos por SKU — Yan calibra
  sem release, M4) e `unlock_forever`; SKUs ≥ R$20 marcam `grantsEntitlements: true` (PAY-03).
- `POST /v1/purchases/redeem` `{productId, purchaseToken, integrityToken}`:
  1. Integrity (acima) → 403 se inválido.
  2. `purchases.products.get` na Play Developer API (service account, escopo `androidpublisher`):
     exige `purchaseState = PURCHASED`, `productId` confere e `obfuscatedExternalAccountId ==
     sha256hex(deviceKey)[..64]` (elo compra↔identidade, M2; limite de 64 chars do Play).
  3. `PENDING` → responde `{"status":"pending"}` **sem creditar** (app re-tenta depois).
  4. Sucesso → tx: `INSERT INTO purchases ... ON CONFLICT (token_hash) DO NOTHING`; conflito →
     devolve o **resultado original** (idempotência PAY-04); senão credita ledger+saldo e, se
     `grantsEntitlements`, liga `ads_off`+`skins_unlocked`. Depois **acknowledge** server-side.
     O **consume** do consumível é do app (Billing client), só após o redeem OK — contrato com
     `monetization` CRD-03.
- **Voided purchases job** (PAY-06): task tokio periódica (intervalo na config) chama
  `voidedpurchases.list`; para cada token conhecido → tx `revoke` (ledger negativo) e recomputa
  entitlements a partir das purchases restantes válidas.

## Proxy de transcrição (TRX-01..05)

- `POST /v1/transcribe`: multipart OpenAI-style **repassado em streaming** — body stream do axum →
  `reqwest::Body::wrap_stream` (nunca o arquivo inteiro em memória, TRX-04). Contador de bytes no
  caminho: estourou o cap da config → **413** com os limites (TRX-03).
- **Débito por duração derivada de bytes**: o `AudioRecorder` do app grava AAC CBR 64 kbps →
  `segundos = ceil(bytes / 8000)` (tabela bitrate×content-type na config). Header opcional
  `X-Audio-Seconds` do app serve de cross-check/telemetria; o valor **autoritativo é o derivado de
  bytes** (não dá pra mentir pra baixo em CBR sem cortar áudio). *Decisão registrada: suficiente
  p/ MVP; revisitar se entrar VBR/outros formatos.*
- **Fluxo**: saldo ≤ 0 → **402** `{"balanceSeconds": n}` antes de tocar o provedor; senão stream →
  provedor → sucesso → **tx de débito atômica com a resposta** (TRX-02; erro/timeout upstream →
  não debita). Corrida entre duas transcrições simultâneas da mesma conta (raro — o fluxo do app é
  serial): débito do sucesso pode negativar levemente; o próximo uso leva 402. Simples e justo.
- **Idempotência** (edge de retry): header `X-Request-Id` (UUID por gravação, gerado no app) →
  `INSERT INTO transcribe_requests ... ON CONFLICT DO NOTHING`; conflito → re-executa upstream mas
  **não debita de novo** (não persistimos texto, ID-03 — trabalho duplicado é o custo aceito).
- **Resposta**: repassa o JSON do provedor (`{"text": ...}` — contrato que o `TranscriptionClient`
  parseia) + header **`X-KeyFlow-Balance-Seconds`** com o saldo restante (CRD-05 do app lê daqui;
  o corpo fica 100% OpenAI-compatible).
- **Timeout/fallback (TRX-05)**: timeout upstream do config (teto); expira → **504** distinguível
  (o app mapeia p/ `ErrorKind.TIMEOUT`). `providers` na config é uma lista ordenada
  `[{name, url, authHeaderName, authValueEnv, model}]`: falha de conexão/5xx imediato → tenta o
  próximo; timeout não faz fallback (responde logo).

## Manifest & config remota (MDL-01, CFG-01)

- `GET /v1/models` e `GET /v1/config` servem `documents[key].body`; **ETag** = sha256 do body
  (`If-None-Match` → 304); `Cache-Control: max-age=300`. Latência alvo < 100 ms (é um SELECT de
  linha única).
- Edição no MVP: SQL direto/migration + deploy (painel admin fora de escopo). O shape do
  `config` inclui: `products` (minutos/SKU), `ads` (cooldown inicial, fator, caps, engajamento
  mínimo, janela pós-compra), `transcription` (caps de tamanho/duração, providers, timeout),
  `minAppVersion`, `killSwitch`.

## Rewarded SSV (ADS-01)

- `GET /v1/ssv/admob?...&signature=...&key_id=...`: verifica **ECDSA** da query string conforme a
  doc do AdMob (chaves públicas de `https://www.gstatic.com/admob/reward/verifier-keys.json`,
  cacheadas e re-buscadas quando `key_id` desconhecido). `custom_data` = accountId anônimo.
  Válido → tx `rewards` (idempotente por `transaction_id`) + crédito com **cap diário** por conta
  (config). Assinatura inválida → 403 sem crédito.

## Operacional (OPS-02..04)

- `tracing` JSON estruturado + `TraceLayer` (latência por rota); eventos de negócio contados por
  log (débitos, rejeições de Integrity, erros de provedor) — métricas Prometheus são futuro.
- Secrets **só por ambiente**: `DATABASE_URL`, `GOOGLE_SERVICE_ACCOUNT_JSON_PATH`,
  `PROVIDER_KEY_*`, `ADMOB_SSV_RELAXED` (test) etc. Nada no repositório (OPS-03).
- `GET /health`: `SELECT 1` + versão do binário (OPS-04).

## Plano de testes

Sem TESTING.md do app aqui — convenção própria do repo Rust (fica no README da T1):

- **Unit** (`cargo test`): validação de verdicts, duração por bytes (arredondamento), ETag,
  seleção de fallback, verificação ECDSA (vetor de teste fixo), regra de entitlements por SKU,
  recomputação de entitlements pós-revoke.
- **Integração** (`cargo test` com Postgres via Docker + **wiremock** p/ Google/provedor):
  register (integrity ok/fail) → account → redeem (credita 1×; replay idempotente; PENDING;
  conta errada) → transcribe (402/413/débito/idempotência/timeout) → SSV (cap diário, replay).
- **Contrato**: resposta do `/v1/transcribe` parseável pelo parser mínimo do app (`{"text":...}`).
- **Gate `rust`** (rodar dentro de `api/`): `cargo fmt --check && cargo clippy --all-targets
  -- -D warnings && cargo test` (CI desde a T1 com path filter `api/**`; testes de integração
  exigem `DATABASE_URL` de teste — `api/docker-compose.yml`).

## Riscos / mitigação

- **Quota do Play Integrity** (classic requests): registro/resgate são raros — cabe no free tier;
  standard requests se o volume crescer.
- **APIs Google (Developer API/Integrity/SSV)** têm formatos exatos que NÃO devem ser fabricados —
  cada task que as toca confere a doc oficial (Knowledge Verification Chain) e cobre com wiremock.
- **`obfuscatedAccountId` ≤ 64 chars** → sha256 hex truncado (documentado no design da
  `monetization` também — os dois lados usam a MESMA função).
- **Duração por bytes** subestima/superestima se o app mudar bitrate → tabela na config + teste de
  contrato no app (monetization) fixa o bitrate esperado.
- **Monorepo**: CI da API com path filter (`api/**`) p/ não rodar cargo em commits só-Android
  (e o CI Android, se existir, ignora `api/**`); `api/target/` no `.gitignore` da raiz.


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

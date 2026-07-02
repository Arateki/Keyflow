# KeyFlow API

Backend Rust (axum) do KeyFlow — identidade anônima, créditos/ledger, compras, proxy de
transcrição e conteúdo remoto. Spec/design/tasks completos em
`.specs/features/keyflow-api/` na raiz do monorepo (não duplicar aqui).

Módulo disjunto do app Android (`app/`): o Gradle não enxerga esta pasta e vice-versa.

## Setup

- Rust stable (edição 2024).
- Postgres local para testes de integração: `docker compose up -d` (sobe na porta `5432`,
  usuário/senha/banco `keyflow`).

```bash
cargo run        # sobe em 0.0.0.0:8080, GET /health
```

## Gate `rust`

Rodar sempre dentro de `api/`:

```bash
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

CI (`.github/workflows/api.yml`) roda o mesmo gate com path filter `api/**`.

## Convenções de teste

- **Unit** (`cargo test`, sem I/O externo): regras puras — validação de verdicts do Play
  Integrity, duração por bytes (arredondamento), cálculo de ETag, seleção de fallback de
  provedor, verificação ECDSA (vetor de teste fixo), regra de entitlements por SKU.
- **Integração** (mesmo `cargo test`, requer `DATABASE_URL` de teste — `docker-compose.yml`):
  exercita rotas reais via `tower::ServiceExt::oneshot`/servidor real; Google/provedores externos
  são substituídos por **wiremock** (nunca chamados de verdade em teste). Cada teste de
  integração usa um schema/transação isolada para não vazar estado entre testes.
- **Nunca fabricar formato de API externa** (Play Integrity, Play Developer API, SSV do AdMob):
  cada task que integra com essas APIs confere a documentação oficial e fixa o contrato exato
  numa fixture wiremock — ver Knowledge Verification Chain em
  `.specs/features/keyflow-api/design.md`.

## Convenções de código

- `cargo fmt` (config em `rustfmt.toml`) + `clippy -D warnings` sem exceções silenciosas.
- Nenhum secret no repositório — configuração via env (módulo `config`, API-T2).
- Nenhum dado pessoal, áudio ou texto transcrito é persistido — ver `design.md` §Princípio
  condutor.

## Status

Ver o `Status` de cada task em `.specs/features/keyflow-api/tasks.md` (fonte da verdade de
progresso desta frente).

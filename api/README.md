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
  - `BIND_ADDR` (opcional, default `0.0.0.0:8080`) — endereço:porta do axum.
  - `RUST_LOG` (opcional, default `info,tower_http=debug`) — diretivas do `tracing_subscriber`;
    o default já deixa a latência por rota (`tower_http::trace`) visível sem precisar setar nada.

```bash
DATABASE_URL=postgres://keyflow:keyflow@localhost/keyflow_api cargo run   # GET /health
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

## Status

Ver o `Status` de cada task em `.specs/features/keyflow-api/tasks.md` (fonte da verdade de
progresso desta frente).

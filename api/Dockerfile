# Build reproduzível: mesma versão de toolchain usada em dev (ver rustc --version no README).
# Tags conferidas no Docker Hub em 2026-07-03 (não fabricadas).
FROM rust:1.96-slim-bookworm AS builder

WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY migrations ./migrations
COPY src ./src

# --locked: falha se Cargo.lock estiver desatualizado em vez de silenciosamente resolver de novo.
RUN cargo build --release --locked --bin keyflow-api

FROM debian:bookworm-slim

# rustls (não OpenSSL) faz o TLS de saída (Google, provedores de transcrição, AdMob) — mas
# rustls-platform-verifier usa o trust store do SO, por isso ca-certificates é necessário mesmo
# sem libssl. sqlx-postgres é driver puro-Rust: sem libpq no runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --no-create-home --shell /usr/sbin/nologin keyflow
COPY --from=builder /build/target/release/keyflow-api /usr/local/bin/keyflow-api

USER keyflow

# `sqlx::migrate!()` embute o SQL das migrations NO BINÁRIO em tempo de compilação (não lê
# migrations/ do disco em runtime) — roda sozinho no boot (db::MIGRATOR.run, ver src/main.rs),
# sem comando de migração separado no entrypoint nem COPY da pasta migrations/ pro runtime.
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/keyflow-api"]

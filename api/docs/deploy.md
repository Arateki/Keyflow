# Deploy da KeyFlow API (staging/produção)

Guia de deploy (API-T13). A parte com **credenciais reais é do Yan** — este documento prepara e
documenta cada passo; quem sobe o staging de fato precisa preencher os itens marcados **[YAN]**.

## 1. Build da imagem

```bash
cd api/
docker build -t keyflow-api:latest .
```

Multi-stage (`api/Dockerfile`): builda com `rust:1.96-slim-bookworm` (mesma versão de toolchain
do dev), roda em `debian:bookworm-slim` com só `ca-certificates` (TLS de saída é via `rustls`,
que usa o trust store do SO — não precisa de `libssl`; `sqlx-postgres` é driver puro-Rust, não
precisa de `libpq`). As migrations do Postgres estão **embutidas no binário** em tempo de
compilação (`sqlx::migrate!()`) e rodam sozinhas no boot — nenhum comando de migração separado.

## 2. Postgres

Gerenciado (RDS, Cloud SQL, Supabase, etc.) ou uma instância própria no host — sem preferência
do design, qualquer Postgres acessível via `DATABASE_URL` serve. Recomendado: gerenciado, para
não ter que operar backup/failover manualmente.

- **[YAN]** provisionar a instância e gerar a `DATABASE_URL` de produção.
- Migrations rodam automaticamente no boot do processo (não precisa rodar `sqlx migrate` à mão).

## 3. Reverse proxy com TLS

O binário fala HTTP puro no loopback (`BIND_ADDR`, default `0.0.0.0:8080`) — TLS termina na
frente, conforme o design (`design.md` §Stack: "Caddy/nginx/LB do host"). Exemplo mínimo com
[Caddy](https://caddyserver.com) (certificado automático via Let's Encrypt, sem config manual de
certs):

```caddyfile
# [YAN] trocar api.keyflow.example pelo domínio real
api.keyflow.example {
    reverse_proxy localhost:8080
}
```

- **[YAN]** decidir/registrar o domínio real e apontar o DNS pro host do staging.
- Se usar nginx em vez de Caddy, o único requisito é: TLS termina ali, proxy_pass pro
  `BIND_ADDR` do processo, sem exigências adicionais de headers/timeouts especiais (o axum já
  lida com `X-Forwarded-*` corretamente via `into_make_service_with_connect_info` só se o proxy
  preservar o IP original — Caddy/nginx fazem isso por padrão com `reverse_proxy`/`proxy_pass`).

## 4. Variáveis de ambiente / secrets

**Nenhum secret no repositório** (OPS-03) — tudo por variável de ambiente no host/orquestrador.
Lista completa (ver também `api/README.md` §Setup):

| Variável | Obrigatória | Origem |
| --- | --- | --- |
| `DATABASE_URL` | sim | Passo 2 acima |
| `BIND_ADDR` | não (default `0.0.0.0:8080`) | — |
| `GOOGLE_SERVICE_ACCOUNT_JSON_PATH` | sim | **[YAN]** service account do Google Cloud (Play Integrity + Developer API habilitadas) — ver abaixo |
| `PACKAGE_NAME` | sim | `com.keyflow` (já conhecido, não é segredo) |
| `INTEGRITY_RELAXED` | não (default `false`) | `true` só se a credencial real ainda não existir (ver §5) |
| `REGISTER_RATE_LIMIT_PER_MINUTE`, `ACCOUNT_RATE_LIMIT_PER_MINUTE` | não | defaults razoáveis, ajustar depois de medir tráfego real |
| `VOIDED_PURCHASES_JOB_INTERVAL_SECS` | não (default `3600`) | — |
| `TRANSCRIPTION_PROVIDERS_JSON` | sim | **[YAN]** decidir o(s) provedor(es) Whisper-compatible (OpenAI, Groq, etc.) |
| `<nome que `authValueEnv` referenciar>` | sim, 1 por provedor | **[YAN]** a(s) chave(s) de API do(s) provedor(es) |
| `TRANSCRIPTION_TIMEOUT_MS`, `TRANSCRIPTION_MAX_BYTES`, `PROVIDER_UNHEALTHY_COOLDOWN_SECS` | não | defaults do MVP |
| `ADS_REWARD_SECONDS`, `ADS_DAILY_CAP` | não | defaults do MVP — **[YAN]** calibrar depois de ver o AdMob real configurado |

### Service account do Google — **[YAN]**

Precisa de uma service account no Google Cloud com:
- API **Play Integrity** habilitada, permissão pra decodificar tokens do app `com.keyflow`.
- API **Google Play Android Developer** habilitada (`androidpublisher`), vinculada ao app no
  Play Console (Configurações da API no Play Console → vincular o projeto GCP → conceder acesso
  à service account).

O arquivo JSON da credencial baixado do Console GCP vai direto em
`GOOGLE_SERVICE_ACCOUNT_JSON_PATH` — os campos usados são só `client_email`, `private_key`,
`token_uri` (ver `src/google/auth.rs`).

## 5. Job ativo (voided purchases)

Roda **no mesmo processo** via `tokio::spawn` (`src/main.rs`) — não é um deploy separado, sobe
junto com a API. Usa a mesma `DATABASE_URL` e a mesma service account (Developer API). Se a
credencial ainda não existir, o job vai logar erro a cada janela (`VOIDED_PURCHASES_JOB_INTERVAL_SECS`)
sem derrubar o processo (ver `jobs::voided::run_periodically` — nunca propaga erro).

## 6. Smoke test em staging

Com a credencial do Google ainda pendente, suba com `INTEGRITY_RELAXED=true` (só relaxa
`appRecognitionVerdict` — pacote e device continuam validados; documentar isso como pendência,
não como definitivo) pra pelo menos `/health`, `/v1/config` e `/v1/models` responderem:

```bash
curl -s https://api.keyflow.example/health
curl -si https://api.keyflow.example/v1/config | head -20
curl -si https://api.keyflow.example/v1/models | head -20
```

`POST /v1/register` só é testável de ponta a ponta com um `integrityToken` real gerado por um
device Android de verdade contra o app `com.keyflow` publicado (ou em teste interno) no Play —
não dá pra fabricar um token de Integrity válido fora desse fluxo. Esse smoke específico fica
**[YAN]** (junto com API-T14, que cruza com MZ-T15 do app).

## 7. Checklist de credenciais pendentes (entregar ao Yan)

- [ ] **[YAN]** Postgres de staging provisionado, `DATABASE_URL` gerada.
- [ ] **[YAN]** Domínio de staging registrado/DNS apontado.
- [ ] **[YAN]** Service account do Google Cloud (Play Integrity + Developer API habilitadas,
      vinculada ao app no Play Console) — arquivo JSON.
- [ ] **[YAN]** Decidir provedor(es) de transcrição e gerar as chaves de API.
- [ ] **[YAN]** Configurar a unidade de anúncio rewarded no AdMob (o `key_id`/chaves públicas de
      verificação são públicos e já funcionam sem nenhuma config nossa — só a unidade de anúncio
      em si precisa existir no console AdMob pra gerar callbacks reais).
- [ ] **[YAN]** Smoke de `POST /v1/register` com device físico + app publicado/teste interno.

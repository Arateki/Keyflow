# Deploy da KeyFlow API na Máquina `keyflow-v1` (Lightsail Debian 12)

Guia operacional da infraestrutura em **`<IP_DA_MAQUINA>`** (Amazon Lightsail, `sa-east-1` São Paulo,
Debian 12 `x86_64`, 447 MB de RAM úteis, 2 vCPUs) e do pipeline de deploy em modelo **pull** via
GitHub Releases assinadas (`.github/workflows/api-deploy.yml`).

> **Firewall da instância Lightsail (configurado no console AWS, fora do git):** a porta `443/tcp`
> aceita conexões exclusivamente das faixas de IP da Cloudflare e a porta `22/tcp` (SSH) aceita
> conexões exclusivamente do IP residencial do Yan. Nenhum IP da máquina ou residencial é gravado no
> repositório.

---

## Pré-requisito Obrigatório Antes do 1º Deploy: Merge da `equipe/api-carga` na `main`

Antes de disparar o primeiro deploy, a branch **`equipe/api-carga` precisa estar integrada na
`main`**. Sem ela na `main`, o pool padrão do `sqlx` é de `10` conexões por processo (20 no total
entre prod e staging, esgotando o `max_connections = 20` do Postgres) e a variável `TRUSTED_PROXIES`
ainda não existe em `api/src/config.rs`. Com `equipe/api-carga` integrada:
- `DB_MAX_CONNECTIONS` cai para `5` por padrão (`5` em prod + `3` em staging via `.env`), além de
  `DB_MIN_CONNECTIONS`, `DB_IDLE_TIMEOUT_SECS` e `DB_ACQUIRE_TIMEOUT_SECS`;
- `TOKIO_WORKER_THREADS` e `TOKIO_MAX_BLOCKING_THREADS` limitam as threads do runtime;
- `TRUSTED_PROXIES=127.0.0.1, ::1` permite confiar no `CF-Connecting-IP` vindo do Nginx local.

---

## Arquitetura e Decisões Técnicas

### 1. Manifesto assinado com Ed25519 via `openssl` e chaves separadas por canal
Como a porta `22/tcp` só aceita o IP residencial do Yan, o GitHub Actions não acessa a máquina por
SSH. O `keyflow-atualizar.timer` roda a cada **5 minutos**, faz **uma única listagem** das GitHub
Releases para os dois canais enviando `If-None-Match` com o ETag salvo em
`/opt/keyflow/state/releases.etag` (`304 Not Modified` não consome cota anônima da API do GitHub;
qualquer erro HTTP `≠ 200/304`, como `403`, falha explicitamente com `exit 1`), ignora tags fora do
formato `^api-(staging|prod)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$` e só instala um binário após validar a
assinatura Ed25519 do manifesto `<canal>|<tag>|<sha256>` e confirmar que o `MainPID` do serviço
mudou no restart antes de testar `/health`.

### 2. Isolamento total entre Staging e Produção (Usuários, `/proc`, Credenciais e PostgreSQL)
- **Usuários de sistema e `/proc` isolados:** `keyflow-api-prod.service` roda como
  `User=keyflow-prod` e `keyflow-api-staging.service` roda como `User=keyflow-staging`, ambos com
  `ProtectProc=invisible` e `ProcSubset=pid`. O staging não enxerga `/proc/<pid>/environ` da
  produção nem lê `/etc/keyflow/google-sa-prod.json` (`0640 root:keyflow-prod`) ou
  `/etc/keyflow/prod.env` (`0600 root:root`).
- **PostgreSQL iniciado só após ajuste de memória:** durante o `apt-get install`, o `provisionar.sh`
  instala um `/usr/sbin/policy-rc.d` temporário (`exit 101`) para impedir que o Debian inicie o
  PostgreSQL com o padrão de `shared_buffers=128MB` nos 447 MB de RAM; o serviço só sobe depois que
  `99-keyflow.conf` (`shared_buffers=64MB`, `max_connections=20`) está gravado.

### 3. Domínio em lugar único (`KEYFLOW_DOMAIN`)
Centralizado em `/etc/keyflow/domain.conf` (`KEYFLOW_DOMAIN`, padrão `keyflow-api.arateki.com`):
```bash
ssh admin@<IP_DA_MAQUINA> 'sudo KEYFLOW_DOMAIN=novo.dominio.com bash -s' < api/deploy/provisionar.sh
```

---

## Preparação Única no GitHub (Coordenador)

### 1. Gerar os DOIS pares de chaves Ed25519 (staging e produção, fora do git!)
```bash
umask 077
openssl genpkey -algorithm Ed25519 -out /tmp/keyflow-release-staging.key
openssl pkey -in /tmp/keyflow-release-staging.key -pubout -out /tmp/keyflow-release-staging.pub

openssl genpkey -algorithm Ed25519 -out /tmp/keyflow-release-prod.key
openssl pkey -in /tmp/keyflow-release-prod.key -pubout -out /tmp/keyflow-release-prod.pub
```

### 2. Criar os GitHub Environments `staging` e `producao` e cadastrar os Secrets por ambiente
> **Importante:** **não** cadastre `API_RELEASE_SIGNING_KEY` como secret global do repositório.

1. Em **Settings → Environments → New environment**:
   - Crie **`staging`** (restrito à branch `main`).
   - Crie **`producao`**, ative **Required reviewers** (Yan) e restrinja **Deployment branches**
     apenas à branch `main`.
2. Cadastre o secret **`API_RELEASE_SIGNING_KEY`** em cada environment e apague as chaves privadas:
   ```bash
   gh secret set API_RELEASE_SIGNING_KEY --env staging  < /tmp/keyflow-release-staging.key
   gh secret set API_RELEASE_SIGNING_KEY --env producao < /tmp/keyflow-release-prod.key
   shred -u /tmp/keyflow-release-staging.key /tmp/keyflow-release-prod.key
   ```

---

## Ordem de Execução

### Passo 1 — Provisionar a máquina (`provisionar.sh`)

```bash
PUB_STAGING="$(cat /tmp/keyflow-release-staging.pub)"
PUB_PROD="$(cat /tmp/keyflow-release-prod.pub)"
ssh admin@<IP_DA_MAQUINA> \
  "sudo KEYFLOW_RELEASE_STAGING_PUBKEY_PEM='$PUB_STAGING' KEYFLOW_RELEASE_PROD_PUBKEY_PEM='$PUB_PROD' bash -s" \
  < api/deploy/provisionar.sh
```

### Passo 2 — Instalar o Certificado TLS de Origem (Cloudflare) e as Service Accounts do Google

1. Certificado Origin CA (15 anos) da Cloudflare para `keyflow-api.arateki.com`:
   ```bash
   scp origin.pem origin.key admin@<IP_DA_MAQUINA>:/tmp/
   ssh admin@<IP_DA_MAQUINA> 'sudo install -m 0644 -o root -g root /tmp/origin.pem /etc/keyflow/tls/origin.pem && \
                              sudo install -m 0600 -o root -g root /tmp/origin.key /etc/keyflow/tls/origin.key && \
                              rm -f /tmp/origin.pem /tmp/origin.key'
   ```
2. JSON da Service Account do Google separado por serviço (`google-sa-prod.json` com grupo
   `keyflow-prod` e `google-sa-staging.json` com grupo `keyflow-staging`):
   ```bash
   scp google-sa-prod.json google-sa-staging.json admin@<IP_DA_MAQUINA>:/tmp/
   ssh admin@<IP_DA_MAQUINA> 'sudo install -m 0640 -o root -g keyflow-prod /tmp/google-sa-prod.json /etc/keyflow/google-sa-prod.json && \
                              sudo install -m 0640 -o root -g keyflow-staging /tmp/google-sa-staging.json /etc/keyflow/google-sa-staging.json && \
                              rm -f /tmp/google-sa-prod.json /tmp/google-sa-staging.json'
   ```
3. Re-execute o `provisionar.sh` para ativar o site no Nginx:
   ```bash
   ssh admin@<IP_DA_MAQUINA> 'sudo bash -s' < api/deploy/provisionar.sh
   ```

### Passo 3 — Primeiro Deploy (Staging) e Verificação de `MemoryDenyWriteExecute`

1. Com `equipe/api-carga` já na `main`, um push na `main` (ou `gh workflow run api-deploy.yml`)
   publica `api-staging-<YYYYMMDDTHHMMSSZ>-<sha>`. Para aplicar imediatamente e testar via túnel SSH:
   ```bash
   ssh admin@<IP_DA_MAQUINA> 'sudo /usr/local/sbin/keyflow-atualizar.sh staging'
   ssh -L 8081:127.0.0.1:8081 admin@<IP_DA_MAQUINA>
   curl -i http://127.0.0.1:8081/health
   ```
2. **Nota sobre `MemoryDenyWriteExecute=true` e `aws-lc-rs`:** caso o serviço caia no primeiro boot,
   verifique com `sudo journalctl -u keyflow-api-staging.service -n 50 --no-pager`. Se houver falha
   de `mmap`/`mprotect` (`W^X`) vinda do `aws-lc-rs`, desligue a diretiva criando um drop-in
   (`/etc/systemd/system/keyflow-api-staging.service.d/override.conf` e o equivalente para `prod`)
   com `[Service]` / `MemoryDenyWriteExecute=false` e rode `sudo systemctl daemon-reload`.

### Passo 4 — Promover para Produção (Exclusivo a partir de `refs/heads/main`)

1. Dispare o workflow na `main` para o canal `producao` e aprove no environment **`producao`**:
   ```bash
   gh workflow run api-deploy.yml --ref main -f canal=producao
   ```
2. Após publicar `api-prod-<YYYYMMDDTHHMMSSZ>-<sha>`, aguarde o timer (até 5 min) ou rode
   `sudo /usr/local/sbin/keyflow-atualizar.sh prod` e valide `curl -i https://keyflow-api.arateki.com/health`.

### Passo 5 — Como Testar e Restaurar um Backup do S3

O `keyflow-backup.timer` roda às `03:15 UTC` (ou `sudo systemctl start keyflow-backup.service`),
exige `/etc/keyflow/backup.env` (`0600 root:root`), grava o dump via stdout aberto pelo root
(`runuser -u postgres -- pg_dump -Fc -Z 9 "$db" > "$arq"`) e envia via `curl --aws-sigv4`. Para
restaurar um `.dump` copiado para `/tmp/restaurar.dump`:
```bash
ssh admin@<IP_DA_MAQUINA> 'sudo bash -s' <<'EOF'
set -euo pipefail
systemctl stop keyflow-api-prod.service
chmod 0644 /tmp/restaurar.dump
runuser -u postgres -- pg_restore --dbname=keyflow_prod --clean --if-exists --no-owner --role=keyflow_prod /tmp/restaurar.dump
rm -f /tmp/restaurar.dump
systemctl start keyflow-api-prod.service
curl -fsS http://127.0.0.1:8080/health && echo " -> Restauração concluída e API saudável!"
EOF
```

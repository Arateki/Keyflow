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

### 3. Domínios em lugar único (`KEYFLOW_DOMAIN` e `KEYFLOW_STAGING_DOMAIN`)
Centralizados em `/etc/keyflow/domain.conf`. Padrões: produção `keyflow-api.arateki.com` e staging
`keyflow-api-staging.arateki.com`. O valor passado no ambiente vence o arquivo; o que não for passado
é reaproveitado. Rodar o script de novo reescreve o vhost, sem duplicar `server`.
```bash
ssh admin@<IP_DA_MAQUINA> 'sudo KEYFLOW_DOMAIN=novo.dominio.com KEYFLOW_STAGING_DOMAIN=staging.novo.dominio.com bash -s' < api/deploy/provisionar.sh
```

### 4. Staging e produção na mesma máquina, staging atrás do Cloudflare Access
Os dois processos dividem a Lightsail: produção em `127.0.0.1:8080`, staging em `127.0.0.1:8081`.
O Nginx publica os dois com o mesmo certificado de origem (`*.arateki.com` em `/etc/keyflow/tls/`):

- `https://keyflow-api.arateki.com` → produção
- `https://keyflow-api-staging.arateki.com` → staging

O endereço de staging está numa aplicação do **Cloudflare Access**. Sem o service token a Cloudflare
responde **401** e o pedido não chega na máquina. Quem chama envia os cabeçalhos
`CF-Access-Client-Id` e `CF-Access-Client-Secret`. O token (Client ID e Client Secret) fica **fora do
git** — no cofre ou no painel Zero Trust, nunca neste repositório, no `provisionar.sh` nem num `.env`
versionado. A política de Access desse hostname precisa ser **Service Auth**; política de login HTML
devolve página em vez do JSON da API.

Depois que o Access deixa o pedido passar, ele injeta `Cf-Access-Jwt-Assertion`. O vhost de staging
responde **403** se esse cabeçalho não vier. O Nginx não valida a assinatura do JWT: a checagem só
fecha o acesso direto à origem. Host que não é produção nem staging cai no `default_server` (portas
80 e 443) e a conexão é encerrada (`return 444`), para não ser atendido pela produção.

### 5. Staging no APK de debug (Cloudflare Access)

A produção exige Play Integrity e recusa o APK de debug instalado à mão. O staging relaxa essa
checagem. O app escolhe o endereço pelo tipo de build:

| Variante | Endereço padrão |
| -------- | --------------- |
| debug | `https://keyflow-api-staging.arateki.com` |
| release | `https://keyflow-api.arateki.com` |

`-PkeyflowApiBaseUrl` vence os dois (é o que aponta um teste para o MockWebServer).

O hostname de staging está atrás do **Cloudflare Access**. Sem o service token a borda responde
**401**. O APK de debug envia `CF-Access-Client-Id` e `CF-Access-Client-Secret` juntos, e só no host
da base da API. O APK de release não lê o token: os campos ficam vazios e o segredo não entra no
pacote.

O token é do Yan e **não entra no git**, no código nem em log. Configure as duas propriedades numa
destas fontes (a primeira que existir vence):

1. na linha de comando: `-PkeyflowStagingAccessClientId=…` e `-PkeyflowStagingAccessClientSecret=…`
2. no `gradle.properties` do `GRADLE_USER_HOME` (não o `gradle.properties` do repositório)
3. no `local.properties` da raiz do projeto (está no `.gitignore`)

```properties
keyflowStagingAccessClientId=<Client ID do service token>
keyflowStagingAccessClientSecret=<Client Secret do service token>
```

Sem as duas preenchidas, o debug continua apontando para o staging. O 401 vira esta linha no log de
debug, e o app não quebra (a configuração cai no cache ou nos padrões; a conta trata o erro da API):

```text
token do Cloudflare Access ausente — veja api/deploy/LEIA-ME.md
```

Para um `curl` no staging, exporte as mesmas variáveis no shell e mande os dois cabeçalhos. Não
cole o valor em arquivo versionado.

---

## Preparação Única no GitHub (Coordenador)

### Modo de compra simulada (somente staging, ativação manual)

O padrão é desligado: sem `SIMULATED_PURCHASES_ENABLED=true`, as duas rotas de simulação respondem 404 e o
registro continua exigindo Play Integrity real. Para uma rodada temporária no staging, o Yan pode definir no
arquivo de ambiente **não versionado** do serviço `keyflow-api-staging`:

```dotenv
SIMULATED_PURCHASES_ENABLED=true
SIMULATED_PURCHASE_HMAC_SECRET=<chave-de-teste-aleatoria-com-pelo-menos-32-bytes>
```

A chave precisa ser a mesma propriedade local `keyflowSimulatedPurchaseHmacSecret` usada para compilar o APK
debug, junto das propriedades Cloudflare Access já descritas acima. É uma chave de teste compartilhada que
fica legível no APK debug: use apenas em staging, com dados descartáveis, nunca uma credencial Google ou chave
de produção; rotacione-a ao encerrar a rodada. No serviço de produção deixe a flag ausente (ou `false`) e não
configure a chave. O boot falha se a simulação for ligada sem chave com pelo menos 32 bytes. Desligue a flag
depois do teste; este guia não autoriza nem realiza deploy.

O APK debug expõe a tela `.DebugSimulatedPurchaseActivity` (compra e reembolso identificáveis); o release não
inclui essa classe nem as rotas. O instrumentado `SimulatedPurchaseStagingE2eTest` pula explicitamente quando
faltam Access ou chave HMAC locais. A re-registração comprova a recuperação de entitlement no servidor, mas não
substitui o teste de reinstalação real do Play/Integrity pendente da MZ-T15.

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

1. Certificado Origin CA (15 anos) da Cloudflare. O mesmo arquivo cobre produção e staging
   (`*.arateki.com`; os dois vhosts usam `/etc/keyflow/tls/origin.pem` e `origin.key`):
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
   Pelo endereço público (o token não entra no comando versionado; exporte no shell e não o grave):
   ```bash
   curl -fsS \
     -H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}" \
     -H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}" \
     https://keyflow-api-staging.arateki.com/health
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

## Exclusão solicitada e retenção dos logs (decisão do Yan, 2026-10-23)

`DELETE /v1/account`, com o Bearer do próprio registro, apaga a conta e, por `ON DELETE CASCADE`,
ledger, compras, recompensas e pedidos de transcrição associados. Não recebe um ID de destino.
Responde `204`; sem credencial válida, `401`. Repetir o efeito é seguro, mas o token apagado já
não autentica. Se a resposta se perder, o app registra a mesma identidade e exclui novamente.
O aparelho conserva a identidade anônima usada no vínculo do Google Play: uma reinstalação com
restauração da identidade ou um novo registro com essa identidade pode restaurar a compra.
A compra permanece no Google Play. O botão na aba Privacidade limpa a credencial e o cache remoto somente
após o sucesso; um marcador local impede recriação automática ao reabrir o app. O desbloqueio
local já reconhecido pelo Play permanece. Configurações e chaves de provedores não são apagadas.
Pedidos por e-mail: **contato@arateki.com, prazo de até 15 dias**.

A API não tem tabela de IP/eventos: os dados operacionais ficam em logs. No Lightsail dedicado,
`provisionar.sh` instala as configurações versionadas em `api/deploy/logs/`:

- journald persistente: `MaxRetentionSec=29day`, arquivos de no máximo uma hora; sem encaminhar
  cópias para syslog, console, kernel ou wall. A política alcança o journal da máquina inteira;
- Nginx: acesso e erros em `/var/log/keyflow/`; acesso registra IP, horário, método, caminho sem
  query, status, tamanho e duração, sem Authorization nem corpo. A regra substitui a do Debian
  e também alcança os arquivos anteriores em `/var/log/nginx/`;
- PostgreSQL: substitui a regra semanal do Debian por rotação horária com `copytruncate`;
- `keyflow-retencao-logs.timer`: a cada hora rotaciona/vacua o journal e roda logrotate com o
  mesmo arquivo de estado do sistema e trava. `Persistent=true` recupera execução após desligamento.

O limite é **até 30 dias**. Usamos 29 dias de arquivos históricos (696 rotações horárias), com
margem para o arquivo ativo e o próximo ciclo de limpeza; limites de espaço podem apagar antes.
A limpeza também roda com logs vazios. Arquivos antigos abrangidos pelas regras são limpos no
primeiro ciclo. O backup PostgreSQL não inclui esses diretórios de logs. Esta configuração cobre
a origem Lightsail; retenção de serviços externos como Cloudflare exige conferência pelo Yan.

**Ativação pendente com o Yan:** revisar e reaplicar o provisionamento pelo procedimento deste
LEIA-ME, que instala os arquivos e habilita o timer; este trabalho não implanta na máquina.
Depois conferir `systemctl status keyflow-retencao-logs.timer` e
`journalctl -u keyflow-retencao-logs.service`, além de `nginx -t`.
Teste local, sem logs de produção: `python3 api/deploy/test-retencao.py` (Python 3, bash, logrotate, Nginx e OpenSSL).

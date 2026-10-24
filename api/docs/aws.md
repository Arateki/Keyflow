# Plano de Deploy na AWS — Máquina Mínima (Staging + Produção)

Planejamento de infraestrutura, dimensionamento de memória, rede, otimizações de código e teste de
carga para rodar a **KeyFlow API** (`api/`) na menor máquina viável da AWS nos ambientes de
**staging** e **produção**.

> **Escopo da v1 (decisão do Yan em 2026-10-23):** a v1 entra no ar com a compra única de **R$ 15**
> (`unlock_forever` → `adsOff` + `skinsUnlocked`) e anúncios; **créditos não entram na v1**. Com
> isso, as rotas `POST /v1/transcribe` (API-T11) e `GET /v1/ssv/admob` (API-T12) ficam **desligadas
> na v1**. A API servirá exclusivamente:
> - Identidade anônima (`POST /v1/register`, `GET /v1/account`);
> - Validação de compra única (`POST /v1/purchases/redeem`) e job de estorno (`voidedpurchases`);
> - Configuração remota e catálogo de modelos (`GET /v1/config`, `GET /v1/models` com `ETag`/`304`);
> - Healthcheck (`GET /health`).

---

## Resumo Executivo e Recomendação Final

Para usuários no Brasil, a latência de rede de `sa-east-1` (São Paulo, ~15–35 ms) contra
`us-east-1` (Virgínia do Norte, ~130–160 ms) faz diferença perceptível no handshake TLS e nas rotas
síncronas. Como no **Amazon Lightsail** o preço dos planos é **idêntico** entre `sa-east-1` e
`us-east-1` (ao contrário do EC2 avulso, que custa ~60% mais caro em São Paulo), o **Lightsail em
`sa-east-1`** entrega a menor conta mensal com IPv4 público, disco SSD e tráfego já inclusos.

Se o Yan preferir **EC2 puro** (para ter CPU ARM64 Graviton2 e AWS Systems Manager sem porta SSH),
a menor opção é a **`t4g.nano`**.

### Tabela de Custo Mensal — Recomendação Final (Staging, Produção, Total)

| Cenário | Máquina / Arquitetura | Região | Custo Staging (USD) | Custo Produção (USD) | **Total Mensal (USD)** |
| --- | --- | --- | --- | --- | --- |
| **Opção 1 — Recomendada (menor custo em SP c/ IPv4, 1 máquina compartilhada)** | **1× Lightsail Nano** (0,5 GB RAM · 2 vCPU · 20 GB SSD · IPv4 incluso) + backups S3 | `sa-east-1` (São Paulo) | $0,00 *(dividindo host via `systemd` cgroup + banco isolado)* | $5,00 (bundle) + $0,05 (backup S3) | **$5,05 / mês** *(grátis nos 3 primeiros meses)* |
| **Opção 2 — Folga de 1 GB RAM em SP (1 máquina compartilhada)** | **1× Lightsail Micro** (1,0 GB RAM · 2 vCPU · 40 GB SSD · IPv4 incluso) + backups S3 | `sa-east-1` (São Paulo) | $0,00 *(dividindo host via `systemd` cgroup + banco isolado)* | $7,00 (bundle) + $0,05 (backup S3) | **$7,05 / mês** *(grátis nos 3 primeiros meses)* |
| **Opção 3 — Isolamento físico total (2 máquinas separadas)** | **2× Lightsail Nano** (0,5 GB RAM · 2 vCPU · 20 GB SSD · IPv4 incluso) + backups S3 | Prod: `sa-east-1` · Staging: `sa-east-1` (ou `us-east-1`) | $5,00 (1× Lightsail Nano dedicado) | $5,00 (bundle) + $0,05 (backup S3) | **$10,05 / mês** |
| **Opção 4 — EC2 Graviton2 ARM64 em SP (1 máquina compartilhada)** | **1× EC2 `t4g.nano`** (0,5 GB RAM · 2 vCPU ARM64) + 10 GB `gp3` + 1 IPv4 + S3 | `sa-east-1` (São Paulo) | $0,00 *(dividindo a mesma EC2)* | $4,89 (EC2) + $1,52 (`gp3`) + $3,65 (IPv4) + $0,05 (S3) | **$10,11 / mês** *(ou **$6,46 / mês** se IPv6-only)* |
| **Opção 5 — EC2 Graviton2 ARM64 nos EUA (1 máquina compartilhada)** | **1× EC2 `t4g.nano`** (0,5 GB RAM · 2 vCPU ARM64) + 10 GB `gp3` + 1 IPv4 + S3 | `us-east-1` (N. Virginia) | $0,00 *(dividindo a mesma EC2)* | $3,07 (EC2) + $0,80 (`gp3`) + $3,65 (IPv4) + $0,05 (S3) | **$7,57 / mês** *(ou **$3,92 / mês** se IPv6-only)* |

*(Em conta AWS nova criada após 15/07/2025, os até **$200 USD em créditos do Free Tier** cobrem 100% das Opções 4 e 5 pelos primeiros **6 meses**).*

---

## 1. Máquina: Comparativo das Menores Opções (`sa-east-1` vs. `us-east-1`)

Preços oficiais On-Demand para Linux (mês comercial AWS = **730 horas**), consultados em
**2026-10-23**:

### 1.1 Componentes de preço no EC2 avulso

1. **Instância (Compute Linux On-Demand, 730 h/mês):**
   - `t4g.nano` (2 vCPUs AWS Graviton2 ARM64, baseline 5%, 0,5 GiB RAM):
     - `us-east-1`: `$0,0042 / hora` = **$3,07 / mês**
     - `sa-east-1`: `$0,0067 / hora` = **$4,89 / mês**
   - `t3a.nano` (2 vCPUs AMD EPYC x86_64, baseline 5%, 0,5 GiB RAM):
     - `us-east-1`: `$0,0047 / hora` = **$3,43 / mês**
     - `sa-east-1`: `$0,0076 / hora` = **$5,55 / mês**
   - `t4g.micro` (2 vCPUs AWS Graviton2 ARM64, baseline 10%, 1,0 GiB RAM):
     - `us-east-1`: `$0,0084 / hora` = **$6,13 / mês**
     - `sa-east-1`: `$0,0134 / hora` = **$9,78 / mês**
2. **Disco — Amazon EBS `gp3` (inclui 3.000 IOPS e 125 MB/s gratuitos no baseline):**
   - `us-east-1`: `$0,080 / GB-mês` → **10 GB = $0,80 / mês** (8 GB mínimo = $0,64 / mês).
   - `sa-east-1`: `$0,152 / GB-mês` → **10 GB = $1,52 / mês** (8 GB mínimo = $1,22 / mês).
3. **Endereço IPv4 Público (Amazon VPC Public IPv4 Address):**
   - Desde 01/02/2024, todo IPv4 público alocado (mesmo em uso na instância EC2) custa
     **`$0,005 / hora`** = **$3,65 / mês** (730 h), em qualquer região.
   - **Endereços IPv6 públicos são gratuitos ($0,00).** Como a Cloudflare faz proxy nativo para
     origens IPv6 (AAAA record) e converte clientes IPv4→IPv6 gratuitamente, uma EC2 **IPv6-only**
     economiza `$3,65/mês` (exigindo apenas DNS64/NAT64 ou endpoints dual-stack do Google para as
     chamadas de saída). Na tabela abaixo, orçamos **com IPv4 público** como padrão seguro e
     indicamos o valor sem IPv4 entre parênteses.
4. **Tráfego de Saída (Data Transfer Out para a internet):**
   - A AWS inclui **100 GB / mês gratuitos** de saída para a internet (somados globalmente na
     conta). Na v1 (apenas respostas JSON de < 1 KB e `304 Not Modified`), 100 GB cobrem mais de
     **100 milhões de chamadas por mês** → **$0,00 / mês**.
   - Excedente além de 100 GB/mês: `$0,09 / GB` em `us-east-1` e `$0,15 / GB` em `sa-east-1`.
5. **Snapshot / Backup:**
   - **Snapshot EBS Standard** (incremental, cobrado apenas pelos blocos usados):
     - `us-east-1`: `$0,05 / GB-mês` (~5 GB ocupados = **$0,25 / mês**).
     - `sa-east-1`: `$0,10 / GB-mês` (~5 GB ocupados = **$0,50 / mês**).
   - **Alternativa mais barata (Recomendada):** `pg_dump -Fc` diário via cron/systemd timer enviado
     para um bucket S3 Standard com regra de expiração de 30 dias (`< 1 GB` = **~$0,03 a $0,05 /
     mês**).

### 1.2 Componentes de preço no Amazon Lightsail

O Lightsail está disponível tanto em **`us-east-1`** quanto em **`sa-east-1` (São Paulo)** sem
sobretaxa regional de compute/disco:

- **Lightsail Nano (0,5 GB RAM, 2 vCPUs x86_64, 20 GB SSD, 1 TB de transferência):**
  - **Dual-Stack (com IPv4 público estático + IPv6):** **$5,00 / mês** (`~$0,0068/h`)
  - **IPv6-only (sem IPv4):** **$3,50 / mês** (`~$0,0048/h`)
- **Lightsail Micro (1,0 GB RAM, 2 vCPUs x86_64, 40 GB SSD, 2 TB de transferência):**
  - **Dual-Stack (com IPv4 público estático + IPv6):** **$7,00 / mês** (`~$0,0096/h`)
  - **IPv6-only (sem IPv4):** **$5,00 / mês**
- **Snapshots do Lightsail:** `$0,05 / GB-mês` sobre os dados usados (ou `$0,05 / mês` usando
  `pg_dump` para S3).

### 1.3 Tabela Comparativa de Custo Mensal Total por Máquina (1 instância completa)

*(Considerando 10 GB EBS `gp3` nas EC2, 1 IPv4 público ativo, tráfego < 100 GB/mês = $0 e snapshot
EBS de 5 GB usados; valores com backup via `pg_dump` no S3 reduzem ~$0,20–$0,45 do snapshot).*

| Opção | Arquitetura / RAM | Região | Instância | Disco | IPv4 Público | Tráfego (<100 GB) | Snapshot (5 GB) | **Total Mensal (com IPv4)** | **Total Mensal (IPv6-only)** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Lightsail Nano** | x86_64 · 0,5 GB | `sa-east-1` ou `us-east-1` | $5,00 | Incluso (20 GB) | Incluso | Incluso (1 TB) | $0,25 *(ou $0,05 S3)* | **$5,25** *($5,05 c/ S3)* | **$3,75** *($3,55 c/ S3)* |
| **Lightsail Micro** | x86_64 · 1,0 GB | `sa-east-1` ou `us-east-1` | $7,00 | Incluso (40 GB) | Incluso | Incluso (2 TB) | $0,25 *(ou $0,05 S3)* | **$7,25** *($7,05 c/ S3)* | **$5,25** *($5,05 c/ S3)* |
| **EC2 `t4g.nano`** | ARM64 · 0,5 GB | `us-east-1` | $3,07 | $0,80 (10 GB) | $3,65 | $0,00 | $0,25 | **$7,77** *($7,57 c/ S3)* | **$4,12** *($3,92 c/ S3)* |
| **EC2 `t3a.nano`** | x86_64 · 0,5 GB | `us-east-1` | $3,43 | $0,80 (10 GB) | $3,65 | $0,00 | $0,25 | **$8,13** *($7,93 c/ S3)* | **$4,48** *($4,28 c/ S3)* |
| **EC2 `t4g.micro`** | ARM64 · 1,0 GB | `us-east-1` | $6,13 | $0,80 (10 GB) | $3,65 | $0,00 | $0,25 | **$10,83** *($10,63 c/ S3)* | **$7,18** *($6,98 c/ S3)* |
| **EC2 `t4g.nano`** | ARM64 · 0,5 GB | `sa-east-1` | $4,89 | $1,52 (10 GB) | $3,65 | $0,00 | $0,50 | **$10,56** *($10,11 c/ S3)* | **$6,91** *($6,46 c/ S3)* |
| **EC2 `t3a.nano`** | x86_64 · 0,5 GB | `sa-east-1` | $5,55 | $1,52 (10 GB) | $3,65 | $0,00 | $0,50 | **$11,22** *($10,77 c/ S3)* | **$7,57** *($7,12 c/ S3)* |
| **EC2 `t4g.micro`** | ARM64 · 1,0 GB | `sa-east-1` | $9,78 | $1,52 (10 GB) | $3,65 | $0,00 | $0,50 | **$15,45** *($15,00 c/ S3)* | **$11,80** *($11,35 c/ S3)* |

### 1.4 Nível Gratuito (AWS Free Tier) aplicável hoje

- **Contas AWS novas (criadas a partir de 15 de julho de 2025):** a AWS substituiu o modelo antigo
  de 750 horas/mês por um **pool flexível de créditos válido por 6 meses**:
  - **$100 USD imediatos** no cadastro + **$100 USD extras** ($20 por cada uma das 5 atividades
    rápidas no console: lançar/terminar uma EC2, criar um RDS, criar uma função Lambda, testar um
    prompt no Bedrock e configurar um alerta no AWS Budgets) = **até $200 USD em créditos**.
  - As famílias `t4g.micro`, `t3.micro`, EBS `gp3` e IPv4 público abatem diretamente desse saldo.
    Na prática, os primeiros **6 meses saem por $0,00**.
- **Contas AWS criadas antes de 15 de julho de 2025 (dentro dos primeiros 12 meses):** seguem no
  *Legacy Free Tier* com **750 horas/mês** de `t4g.micro` (ou `t3.micro`), **30 GB de EBS** e
  **750 horas/mês de 1 IPv4 público** associado a instância EC2 (cobre 1 máquina inteira grátis por
  1 ano, mas se subir 2 máquinas com IPv4 soma 1.460 h e cobra as 710 h excedentes).
- **Amazon Lightsail Free Tier:** oferece **3 meses (90 dias) gratuitos** nos bundles de entrada
  (`$3,50`, `$5,00` e `$7,00/mês`, até 750 h/mês) para novos usuários do Lightsail.

---

## 2. Cabe? API + Postgres na Mesma Máquina (0,5 GB vs. 1 GB de RAM)

**Sim, cabe com folga em 0,5 GB (512 MB) de RAM**, desde que:
1. O Postgres seja ajustado para memória baixa (os defaults do Postgres 18 assumem `shared_buffers =
   128MB` e `max_connections = 100`, que sozinhos tomam > 250 MB sob carga);
2. Os serviços rodem **direto via `systemd` no host Debian/Ubuntu** (sem `dockerd` + `containerd`,
   que consomem ~80–110 MB de RAM fixos só para existir);
3. Haja um arquivo de **swap** configurado no SSD para absorver picos de `apt upgrade` ou `VACUUM`.

### 2.1 Orçamento de Memória (RSS real em produção)

| Processo | Em 0,5 GB (512 MiB) — Ajustado | Em 1,0 GB (1024 MiB) — Ajustado |
| --- | --- | --- |
| Kernel Linux + `systemd` + `journald` + `sshd`/`ssm-agent` | ~95 MiB | ~110 MiB |
| Reverse Proxy TLS (**Nginx** leve; evite Caddy em 0,5 GB pois Go usa ~40 MiB) | ~8 MiB | ~12 MiB |
| **PostgreSQL 17/18** (processo mestre + walwriter + autovacuum + 8 conexões ativas) | ~85 MiB (`shared_buffers = 64MB`) | ~220 MiB (`shared_buffers = 192MB`) |
| **`keyflow-api` (Produção)** (binário Rust nativo, `mimalloc`/system, pool = 5) | ~18 MiB | ~25 MiB |
| **`keyflow-api` (Staging)** (binário Rust nativo, pool = 3, na mesma máquina) | ~14 MiB | ~20 MiB |
| **Soma ocupada ( processos )** | **~220 MiB** | **~387 MiB** |
| **Memória livre para Page Cache do SO (arquivos/índices Postgres)** | **~292 MiB (57% livre)** | **~637 MiB (62% livre)** |

### 2.2 Ajustes Obrigatórios do PostgreSQL (`postgresql.conf`)

Para máquina de **0,5 GB de RAM** (entre parênteses os valores para **1,0 GB de RAM**):

```ini
# Memória
shared_buffers = 64MB                  # (192MB em 1 GB RAM)
effective_cache_size = 192MB           # (512MB em 1 GB RAM) — dica pro planner, não aloca RAM
work_mem = 2MB                         # (4MB em 1 GB RAM) — queries da API são PK/index lookups
maintenance_work_mem = 16MB            # (32MB em 1 GB RAM) — usado por VACUUM / CREATE INDEX
wal_buffers = 2MB                      # (4MB em 1 GB RAM)
huge_pages = off

# Conexões e Processos Filhos
max_connections = 20                   # (25 em 1 GB RAM) — 5 prod + 3 staging + folga p/ psql/backup
max_worker_processes = 2               # Desliga workers extras desnecessários para OLTP curto
max_parallel_workers_per_gather = 0    # Desliga paralelismo de query (economiza RAM e CPU)
max_parallel_workers = 0
max_parallel_maintenance_workers = 1

# I/O em SSD (EBS gp3 / SSD Lightsail)
random_page_cost = 1.1
effective_io_concurrency = 200
synchronous_commit = on                # Manter 'on' no ledger financeiro (OPS-01)!
```

### 2.3 Swap e Limites do Kernel (`sysctl`)

Em máquinas de 0,5 GB ou 1 GB sem swap, qualquer pico momentâneo aciona o OOM Killer do Linux, que
costuma matar o processo de maior RSS (o `postgres`). Configurar **1 GB de swap em arquivo**:

```bash
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

E em `/etc/sysctl.d/99-keyflow.conf`:
```ini
vm.swappiness = 10
vm.vfs_cache_pressure = 50
```
(`vm.swappiness = 10` garante que o kernel só use o swap em emergência, mantendo o Postgres em RAM).

### 2.4 Tamanho do Pool de Conexões (`sqlx::PgPool`)

- Hoje o código abre `max_connections(10)` (`api/src/db/mod.rs:11`).
- Na arquitetura atual da KeyFlow API, as chamadas lentas (Play Integrity e Play Developer API no
  Google, ~40–100 ms) acontecem **fora** da transação do banco (`api/src/routes/register.rs:61-68`
  e `api/src/routes/redeem.rs:86-102`). A transação no Postgres dura **< 2 ms** em loopback/socket
  Unix.
- Com 2 ms por transação, **1 única conexão** é capaz de serializar até 500 transações/s. Portanto:
  - **Produção:** `DB_MAX_CONNECTIONS = 5` (`min_connections = 0`, `idle_timeout = 300s`).
  - **Staging:** `DB_MAX_CONNECTIONS = 3` (`min_connections = 0`, `idle_timeout = 60s`).
  - **Soma (8 conexões)** fica muito abaixo de `max_connections = 20` do Postgres.

### 2.5 Quanto custa o Amazon RDS a mais?

A menor instância de RDS para PostgreSQL disponível é a **`db.t4g.micro`** (Single-AZ, com mínimo
obrigatório de 20 GB de armazenamento `gp3`):

- Em **`us-east-1`**: `$11,68 / mês` (`$0,016/h`) + `$2,30 / mês` (20 GB `gp3` a `$0,115/GB`) =
  **`+$13,98 / mês`** por instância RDS.
- Em **`sa-east-1`**: `~$16,79 / mês` (`$0,023/h`) + `$4,38 / mês` (20 GB `gp3` a `$0,219/GB`) =
  **`+$21,17 / mês`** por instância RDS.

**Conclusão:** o RDS custa **3× a 4× o valor da máquina inteira**. Para a v1, rodar o Postgres na
própria instância com backup diário automatizado (`pg_dump` comprimido enviado ao S3 + retenção de
30 dias por `< $0,05/mês`) economiza de **$168 a $254 USD por ano**.

---

## 3. Staging e Produção: Mesma Máquina ou Duas Máquinas?

### Comparativo dos dois caminhos (em `sa-east-1`, São Paulo)

| Critério | Caminho A: Mesma Máquina (1× Lightsail Nano/Micro) | Caminho B: Duas Máquinas (2× Lightsail Nano) |
| --- | --- | --- |
| **Custo Mensal** | **$5,05 / mês** (0,5 GB) ou **$7,05 / mês** (1 GB) | **$10,05 / mês** ($5,00 staging + $5,05 prod) |
| **Isolamento de Dados** | Total a nível lógico: 2 bancos (`keyflow_prod` e `keyflow_staging`) e 2 roles Postgres sem permissão cruzada | Total a nível físico (instâncias e discos independentes) |
| **Isolamento de Processo** | 2 units `systemd` (`keyflow-api-prod` em `:8080`, `keyflow-api-staging` em `:8081`) com fatias de CPU/RAM limitadas por cgroups v2 (`MemoryMax=64M`, `CPUQuota=30%` no staging) | 100% isolado (um panic/OOM ou teste de carga pesado em staging jamais afeta produção) |
| **Manutenção / OS** | 1 único servidor para atualizar pacotes e renovar certificados | 2 servidores para manter |

### Recomendação

- **Para o lançamento da v1 com custo mínimo absoluto:** recomendamos o **Caminho A (Mesma Máquina
  em `sa-east-1` — Lightsail Nano `$5,05/mês` ou Micro 1 GB `$7,05/mês`)**, isolando o serviço de
  staging com limites rígidos do `systemd` (`MemoryMax=64M`, `CPUQuota=25%`, `Nice=10`) e banco/role
  separados (`keyflow_staging`).
- **Regra importante:** os **testes de carga destrutivos** (item 6) devem ser executados **localmente
  em Docker** (com `--cpus` e `--memory`), nunca contra o staging se ele dividir máquina com a
  produção. Se o Yan quiser rodar testes de estresse na nuvem, pode subir uma instância de staging
  temporária por algumas horas (o Lightsail cobra `$0,0068/hora` = menos de `$0,05` por uma tarde de
  testes, deletando em seguida).

---

## 4. Rede, Cloudflare, Segurança e Restrição do Staging

### 4.1 DNS e TLS pela Cloudflare

1. **Domínio:** usar o domínio do Yan já gerenciado na Cloudflare (ex.: `api.keyflow...` para
   produção e `staging-api.keyflow...` para staging). Se optar por um domínio registrado na GoDaddy,
   basta apontar os 2 *nameservers* na GoDaddy para a Cloudflare (plano Free da Cloudflare).
2. **Modo SSL/TLS:** configurar como **Full (Strict)**.
3. **Certificado na origem:** gerar um **Cloudflare Origin CA Certificate** (gratuito, validade de
   **15 anos**, curinga `*.keyflow...`) e instalá-lo no Nginx da máquina. Assim não é preciso abrir
   a porta 80 para renovação HTTP-01 do Let's Encrypt.
4. **Preservação do IP real do cliente (crítico para o Rate Limit de `/v1/register`):**
   - `api/src/routes/register.rs:56` usa `ConnectInfo<SocketAddr>` para aplicar o limite de
     `5 req/min por IP`. Quando há Nginx + Cloudflare na frente, `ConnectInfo` enxerga `127.0.0.1`
     se não for tratado, fazendo **todos os usuários dividirem a mesma cota de 5 registros/minuto**!
   - Solução dupla: configurar `set_real_ip_from` (IPs da Cloudflare) + `real_ip_header
     CF-Connecting-IP;` no Nginx (que também pode aplicar `limit_req_zone` por `$binary_remote_addr`
     na borda) e/ou ler `CF-Connecting-IP` no middleware quando a requisição vem do loopback.

### 4.2 Firewall (Porta 443 restrita à Cloudflare e Administração)

- **Porta 443/tcp (HTTPS):** aberta **exclusivamente** para os blocos CIDR oficiais da Cloudflare:
  - IPv4: `https://www.cloudflare.com/ips-v4` (15 blocos CIDR)
  - IPv6: `https://www.cloudflare.com/ips-v6` (7 blocos CIDR)
  - Qualquer scan direto ao IP público da AWS é descartado no firewall da nuvem antes mesmo de tocar
    a VM.
- **Porta 80/tcp (HTTP):** **fechada** no firewall da AWS (o redirecionamento HTTP→HTTPS é feito na
  borda da Cloudflare com a chave *Always Use HTTPS*).
- **Acesso de Administração:**
  - **Em EC2 (`t4g.nano`):** usar **AWS Systems Manager (SSM) Session Manager** (gratuito, agente já
    vem instalado nas AMIs Ubuntu/Debian/AL2023 da AWS). Permite abrir terminal pela AWS CLI/Console
    com **zero portas de entrada abertas** (porta 22 fechada para o mundo).
  - **No Lightsail:** porta 22/tcp aberta apenas para o IP do Yan (ou fechada no firewall público do
    Lightsail, acessando via **Tailscale SSH**).

### 4.3 Staging acessível só ao Yan (Anotado, sem configurar) e efeito no App Android

| Opção (Gratuita) | Como funciona no Staging | Efeito no App Android de teste do Yan |
| --- | --- | --- |
| **1. Tailscale (Recomendada — Plano Personal Free: até 3 usuários e 100 devices)** | Instala `tailscaled` na VM da AWS. O Nginx escuta o vhost do staging apenas no IP privado da Tailnet (`100.x.y.z`, interface `tailscale0`), com certificado TLS válido via `tailscale cert` (ou DNS `staging-api` na Cloudflare apontando para `100.x.y.z` com nuvem cinza *DNS Only*). Zero exposição na internet pública. | **Zero alteração no código do APK.** O Yan só liga o app Tailscale no Android (`RX8T10DERKW`) ao testar o staging. **Atenção (Android):** o Android permite apenas **1 `VpnService` ativo por vez**; se o Yan usar outro app que ocupe o slot de VPN (ex.: bloqueador de DNS local tipo RethinkDNS/AdGuard VPN), precisará pausá-lo durante o teste. O KeyFlow é um teclado (`InputMethodService`), não usa `VpnService`, então funciona normalmente com o Tailscale ligado. |
| **2. Cloudflare Zero Trust (Access + WARP ou WAF Rule — Free até 50 usuários)** | Protege `staging-api.keyflow...` na borda da Cloudflare. **Cuidado:** se configurar uma tela de login HTML (Google/OTP), **quebra o app Android**, pois o cliente HTTP do app receberá `302 Found` (HTML) em vez do JSON esperado! Para não quebrar o app, a política deve usar o cliente **Cloudflare WARP** no celular do Yan (*Gateway/WARP enrollment*) **ou** uma regra de **WAF Custom Rule** bloqueando qualquer acesso a `staging-api` que não venha do IP/WARP do Yan. | Com **Cloudflare WARP** no celular (ou liberação de IP no WAF da Cloudflare), o app Android de teste fala com `https://staging-api...` de forma **100% transparente**, sem alterar headers do APK. (Assim como no Tailscale, o app WARP usa o slot de VPN do Android). |
| **3. WireGuard nativo na VM (Gratuito, no kernel Linux)** | Interface `wg0` na VM (gasta < 5 MB de RAM), porta `51820/udp` aberta no firewall. No app WireGuard do Android, configura-se `AllowedIPs = <IP privado do staging>/32` (*split-tunnel* cirúrgico: só o tráfego do staging passa pelo túnel). | Transparente para o APK do KeyFlow; por usar *split-tunnel* `/32`, não altera a rota de internet nem o IP público do restante do celular do Yan. |

---

## 5. Eficiência do Servidor para Máquina Pequena (Auditoria de `api/`)

Leitura completa de `api/` com indicação de **arquivo e linha** do que ajustar na próxima etapa para
reduzir consumo de RAM, CPU e disco (apenas mapeamento; nenhum arquivo de código foi alterado nesta
tarefa):

1. **Perfil de compilação ausente — `api/Cargo.toml` (linhas 1–31):**
   - Não existe bloco `[profile.release]`. O default do Cargo mantém símbolos, usa `codegen-units =
     16` e sem LTO. Adicionar ao final de `api/Cargo.toml`:
     ```toml
     [profile.release]
     lto = "thin"
     codegen-units = 1
     strip = "symbols"
     panic = "abort"
     opt-level = 3
     ```
   - **Ganho:** reduz o binário de release em ~45–60%, elimina tabelas de *stack unwinding* da RAM e
     melhora o *inlining* entre crates (`axum`, `sqlx`, `serde`).
2. **Runtime Tokio multi-thread com defaults de servidor grande — `api/Cargo.toml` (linha 22) e
   `api/src/main.rs` (linha 12):**
   - `#[tokio::main]` sem argumentos em `api/src/main.rs:12` inicializa o agendador `rt-multi-thread`
     e permite até 512 threads no pool de bloqueio (`max_blocking_threads`), cada uma reservando
     2 MiB de stack. Em máquina de 2 vCPUs burstable e 0,5 GB de RAM, fixar `#[tokio::main(flavor =
     "current_thread")]` (ou limitar com `tokio::runtime::Builder::new_multi_thread().worker_threads(2).max_blocking_threads(8)`)
     reduz trocas de contexto e reservas de stack.
3. **Alocador global para baixa retenção de RSS — `api/Cargo.toml` (linhas 6–27) e
   `api/src/main.rs` (linha 1):**
   - O `ptmalloc2` da `glibc` (no `debian:bookworm-slim`) cria múltiplas arenas por thread e retém
     páginas desalocadas, inflando o RSS ao longo de dias. Usar `mimalloc` (`#[global_allocator]
     static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;`) ou definir `MALLOC_ARENA_MAX=2` na
     unit do `systemd` / container evita fragmentação de heap em 512 MB de RAM.
4. **Pool do Postgres superdimensionado e sem fechamento de conexões ociosas —
   `api/src/db/mod.rs` (linhas 9–14):**
   - `PgPoolOptions::new().max_connections(10).connect(database_url)` (`linha 10–12`) fixa 10
     conexões sem `idle_timeout` curto. Cada processo filho do Postgres consome ~5–10 MB de RAM.
   - **Ajuste:** ler `DB_MAX_CONNECTIONS` do ambiente (default `5` em produção, `2` em staging) e
     configurar `.min_connections(0)`, `.idle_timeout(Duration::from_secs(300))` e
     `.acquire_timeout(Duration::from_secs(5))`.
5. **5 instâncias separadas de `reqwest::Client::new()` — `api/src/main.rs` (linhas 30, 36, 44, 59)
   e `api/src/lib.rs` (linha 82):**
   - O código instancia **cinco** clientes HTTP independentes:
     - `api/src/main.rs:30` (para `IntegrityClient::new`)
     - `api/src/main.rs:36` (para `PublisherClient::new`)
     - `api/src/main.rs:44` (para `job_publisher`)
     - `api/src/main.rs:59` (para `AppState::new`)
     - `api/src/lib.rs:82` (dentro de `AppState::new`, para `AdmobKeysCache::new`)
   - Cada `reqwest::Client::new()` carrega do sistema operacional sua própria cópia dos certificados
     raiz TLS (`rustls-platform-verifier`), seu próprio pool de conexões HTTP/2 e buffers de sessão
     TLS!
   - **Ajuste:** criar **um único** `reqwest::Client::builder().pool_max_idle_per_host(2).pool_idle_timeout(Duration::from_secs(60)).build()`
     em `api/src/main.rs` e passá-lo via `.clone()` (que é apenas um `Arc` interno) para todos os
     clientes.
6. **`PublisherClient` e `TokenCache` duplicados — `api/src/main.rs` (linhas 35–48) e
   `api/src/google/publisher.rs` (linhas 45–67):**
   - `main.rs` cria dois `PublisherClient` separados (`publisher` na linha 35 e `job_publisher` na
     linha 43), cada um com sua própria `TokenCache`, assinando JWT RSA-2048 e renovando token OAuth
     em duplicidade para o mesmo escopo `androidpublisher`.
   - **Ajuste:** envolver `PublisherClient` em `Arc` (ou derivar `Clone` colocando `Arc<TokenCache>`)
     para que a rota `/v1/purchases/redeem` e o job de `voidedpurchases` compartilhem o mesmo token
     OAuth cacheado.
7. **Parse repetido da chave privada RSA — `api/src/google/auth.rs` (linhas 13–26 e 113):**
   - Em `api/src/google/auth.rs:113`, `EncodingKey::from_rsa_pem(key.private_key.as_bytes())` faz o
     parse do PEM RSA toda vez que o token OAuth é renovado. Pré-parsear `EncodingKey` uma única vez
     dentro de `ServiceAccountKey::from_json_file` (`linhas 20–25`) elimina alocações e CPU a cada
     renovação.
8. **Vazamento lento de memória nos mapas de Rate Limit — `api/src/middleware/ratelimit.rs` (linhas
   12–16 e 32–46):**
   - `register_by_ip: DefaultKeyedRateLimiter<IpAddr>` e `account: DefaultKeyedRateLimiter<Uuid>`
     guardam estado em um `DashMap` que **nunca remove chaves antigas** porque `retain_recent()` e
     `shrink_to_fit()` não são chamados em lugar nenhum. Se a API sofrer um scan de milhares de IPs
     ou acumular contas ao longo de meses, o mapa cresce indefinidamente na RAM de 0,5 GB.
   - **Ajuste:** adicionar um método `RateLimiters::cleanup(&self)` que chama
     `self.register_by_ip.retain_recent(); self.register_by_ip.shrink_to_fit();` (e o mesmo para
     `self.account`) e invocá-lo a cada 5 minutos num `tokio::spawn`.
9. **Query ao Postgres + serialização JSON + SHA-256 em toda requisição de `/v1/config` e
   `/v1/models` — `api/src/routes/content.rs` (linhas 18–45):**
   - Em toda chamada a `GET /v1/config` e `GET /v1/models` — mesmo quando o app envia
     `If-None-Match` e só precisa de um `304 Not Modified` —, `serve_document` executa `SELECT body
     FROM documents` (`linhas 20–23`), aloca um `Vec<u8>` com `serde_json::to_vec(&body)` (`linha
     35`) e calcula `Sha256::digest(&bytes)` (`linha 39` e `57`) antes de checar `If-None-Match`
     (`linha 41`).
   - **Ajuste:** como `config` e `models` são estáticos entre deploys/updates no MVP, cachear
     `(Bytes, String /* etag */)` em memória com TTL de 60 segundos (ou ler `etag` pré-calculado).
     Com isso, um `304 Not Modified` responde em microssegundos sem tocar no Postgres nem alocar
     memória.
10. **Rotas fora de escopo na v1 — feito, uma config por rota, padrão desligado:**
    - `POST /v1/transcribe` segue `TRANSCRIBE_ENABLED` e `GET /v1/ssv/admob` segue
      `SSV_ADMOB_ENABLED` (`true`/`1` ligam; ausente ou vazio fica desligado). Ligar uma não liga
      a outra.
    - Rota desligada responde **404** numa camada anterior ao handler: o extractor de conta (que
      consulta o banco) e a verificação SSV não rodam.
    - Com a transcrição desligada, `TRANSCRIPTION_PROVIDERS_JSON` e a env da chave do provedor
      não são exigidas no boot. Ligada, as duas voltam a ser obrigatórias. A SSV desligada
      também não exige variável própria (`ADS_REWARD_SECONDS` e `ADS_DAILY_CAP` já têm padrão;
      a URL das chaves do AdMob não é env). `AdmobKeysCache` ainda é criado no `AppState`, mas
      não lê env nem busca a rede no boot.
11. **Compilação para ARM64 (Graviton) e imagem/deploy leve — `api/Dockerfile` (linhas 3, 11 e 13):**
    - **Nunca compilar Rust dentro da máquina de 0,5 GB ou 1 GB da AWS** (`rustc` + `sqlx` + `aws-lc-rs`
      passam de 1,5 GB de RAM no build). Compilar na máquina de dev/CI:
      - Para `x86_64` (Lightsail / `t3a.nano`): `cargo build --release --locked --target x86_64-unknown-linux-gnu`
      - Para `ARM64` (EC2 `t4g.nano` / `t4g.micro` Graviton2): `docker buildx build --platform linux/arm64`
        ou `cargo zigbuild --release --locked --target aarch64-unknown-linux-gnu`.
    - Em máquina de 0,5 GB, copiar apenas o binário `/usr/local/bin/keyflow-api` (~10 MB após
      `strip`) e rodá-lo via `systemd` direto no Debian Bookworm do host economiza os ~100 MB de RAM
      do daemon do Docker. Caso use Docker (`api/Dockerfile:13`), trocar `debian:bookworm-slim` por
      `gcr.io/distroless/cc-debian12:nonroot` reduz a imagem de 101 MB para ~28 MB.

---

## 6. Plano do Teste de Carga (Para a Próxima Tarefa Executar)

### 6.1 Ferramentas Recomendadas

1. **`oha`** (CLI em Rust, baixíssimo overhead de CPU/RAM): para benchmarks isolados de cada rota,
   validação de `ETag`/`304`, teste de rate-limit (`429`) e coleta de percentis exatos (`p50`,
   `p90`, `p99`, `p99.9`) em JSON (`oha --json`).
2. **`k6`** (ou **`goose`** em Rust): para o cenário misto realista em que cada usuário virtual (VU)
   executa a jornada com estado e headers dinâmicos.

### 6.2 Como Limitar CPU e Memória Localmente (Docker) para Imitar a Máquina Escolhida

Tanto o Lightsail Nano (`$5`) quanto a EC2 `t4g.nano` têm **0,5 GB (512 MiB) de RAM** e **2 vCPUs
burstable** (cujo baseline contínuo sem créditos de CPU é de **5% a 10% por vCPU = 0,10 a 0,20 vCPU
totais**, e cujo pico com créditos de CPU é de **2,0 vCPUs**).

Para simular fielmente os **512 MiB de RAM** (descontando ~110 MiB que o kernel Linux + SO + Nginx
ocupam na VM real, restando **~400 MiB** para Postgres + API), a próxima tarefa deve subir os
containers na mesma rede Docker com os seguintes limites rígidos:

- **Perfil 1 — Burst (Pico com créditos de CPU: 2,0 vCPUs e 400 MiB úteis para DB + API):**
  ```bash
  # Postgres 18 tunado para máquina de 0,5 GB:
  docker run -d --name kf-pg --network kf-net \
    --cpus="1.0" --memory="256m" --memory-swap="512m" \
    -e POSTGRES_USER=keyflow -e POSTGRES_PASSWORD=keyflow -e POSTGRES_DB=keyflow_api \
    postgres:18-alpine \
    postgres -c shared_buffers=64MB -c effective_cache_size=192MB \
             -c work_mem=2MB -c maintenance_work_mem=16MB \
             -c max_connections=20 -c max_parallel_workers_per_gather=0

  # KeyFlow API (release):
  docker run -d --name kf-api --network kf-net -p 8080:8080 \
    --cpus="1.0" --memory="128m" --memory-swap="256m" \
    ... keyflow-api:latest
  ```
- **Perfil 2 — Baseline Sustentado (Créditos de CPU esgotados: 0,20 vCPU total e 400 MiB úteis):**
  - Mesmo comando acima, reduzindo `--cpus="0.10"` no `kf-pg` e `--cpus="0.10"` no `kf-api`. É o
    teste de "pior caso absoluto" (se a máquina ficar sob ataque ou carga 24/7 até zerar os créditos
    de CPU burstable).
- **Perfil 3 — Máquina de 1 GB (`t4g.micro` / Lightsail Micro $7):**
  - `--cpus="1.0" --memory="512m"` no `kf-pg` (`shared_buffers=192MB`) e `--cpus="1.0"
    --memory="256m"` no `kf-api`.

### 6.3 Simulação do Google (Play Integrity + Play Developer API)

- As rotas `POST /v1/register` e `POST /v1/purchases/redeem` chamam `oauth2.googleapis.com`,
  `playintegrity.googleapis.com` e `androidpublisher.googleapis.com`.
- **Detalhe técnico importante do código atual:** em `api/src/main.rs:8-9`, as constantes
  `PLAY_INTEGRITY_BASE_URL` e `ANDROID_PUBLISHER_BASE_URL` estão fixas no binário (embora
  `IntegrityClient::new` e `PublisherClient::new` já recebam `base_url: String`). Na tarefa do teste
  de carga, basta permitir sobrescrevê-las por variável de ambiente (com fallback para as URLs
  oficiais do Google) e apontá-las para um container mock na mesma rede `kf-net` com **latência
  artificial de 50 ms** (simulando o RTT real São Paulo ↔ Google) e respostas JSON válidas.

### 6.4 Cenários de Teste com as Rotas Reais da v1

1. **Cenário C1 — Cache de Conteúdo (`GET /v1/config` e `GET /v1/models` com `ETag`):**
   - 1ª chamada captura o header `ETag`; chamadas seguintes enviam80% com `If-None-Match: "<etag>"`
     (esperando `304 Not Modified`) e 20% sem header (esperando `200 OK`, simulando novos clientes).
   - **Objetivo:** medir RPS máximo e confirmar latência `p99 < 20 ms`.
2. **Cenário C2 — Consulta de Conta e Entitlements (`GET /v1/account`):**
   - Pré-semear **2.000 contas** no Postgres com tokens conhecidos. Disparar requisições distribuídas
     entre as 2.000 contas (para não esbarrar no `ACCOUNT_RATE_LIMIT_PER_MINUTE = 60` por conta).
   - **Objetivo:** validar o throughput de `SELECT ... WHERE token_hash = $1` no índice/tabela.
     *(Nota: a coluna `accounts.token_hash` em `api/migrations/0001_core_schema.sql:8` **não tem
     índice** hoje! Com 2.000+ contas na tabela, toda chamada autenticada faz **Sequential Scan** em
     `accounts`! O teste de carga vai evidenciar isso imediatamente; criar `CREATE INDEX ON accounts
     (token_hash)` será um ganho enorme).*
3. **Cenário C3 — Registro de Dispositivos (`POST /v1/register`):**
   - Enviar `{"deviceKey": "<uuid>", "integrityToken": "valid-mock"}` (50% inéditos `INSERT`, 50%
     re-registros `UPDATE` rotacionando token), com o mock do Google aplicando 50 ms de delay.
   - *(Para testar capacidade do banco/servidor além do limite por IP de `5/min`, configurar
     `REGISTER_RATE_LIMIT_PER_MINUTE=100000` na rodada de throughput, e `5` na rodada de validação
     de rate-limit).*
4. **Cenário C4 — Resgate da Compra Única de R$ 15 (`POST /v1/purchases/redeem`):**
   - Enviar `{"productId": "unlock_forever", "purchaseToken": "<tok>", "integrityToken": "valid"}`.
   - Sub-cenário **4a (Throughput):** tokens inéditos concorrentes validando a transação
     (`purchases` + `ledger` + `accounts.ads_off = true, skins_unlocked = true` + `acknowledge`).
   - Sub-cenário **4b (Idempotência sob corrida):** 20 requisições simultâneas com o **mesmo**
     `purchaseToken`, verificando que apenas 1 insere/faz acknowledge e todas as 20 retornam `200
     {"status":"credited"}` sem erro `500` nem deadlock.
5. **Cenário C5 — Mix Realista de Produção v1 (15 minutos contínuos no Perfil Baseline e Burst):**
   - Proporção de tráfego real do app:
     - **45%** `GET /v1/config` (85% com `If-None-Match` → `304`, 15% sem → `200`)
     - **35%** `GET /v1/account` (autenticado com Bearer)
     - **10%** `GET /v1/models` (85% com `If-None-Match` → `304`, 15% sem → `200`)
     - **8%** `POST /v1/register`
     - **2%** `POST /v1/purchases/redeem` (`unlock_forever`)

### 6.5 Métricas e Como Traduzir em "Quantos Usuários Suportamos"

- **Métricas obrigatórias no relatório do teste de carga:**
  1. **RPS sustentado** (requisições/s sem degradação) nos Perfis Burst (2 vCPU) e Baseline (0,2 vCPU);
  2. **Latência `p50`, `p95`, `p99`** por rota (metas: `config`/`models` `p95 < 50 ms`, `account`
     `p95 < 50 ms`, `register`/`redeem` `p95 < 250 ms` incluindo 50 ms do Google simulado);
  3. **Pico de memória RSS** (`docker stats` / `memory.peak` de `kf-api` e `kf-pg`), confirmando
     **0 kills por OOM** dentro de 128 MiB (API) + 256 MiB (Postgres);
  4. **Taxa de erro HTTP 5xx:** deve ser **0,00%**.

- **Fórmula de conversão de RPS para Usuários Ativos Diários (DAU):**
  - Na v1 (sem proxy de transcrição nem SSV de rewarded ads), o uso do teclado no dia a dia **não
    chama a API** — a transcrição roda no aparelho (`sherpa-onnx`) ou direto na chave do usuário.
  - Um usuário ativo gera em média **$\approx 3$ requisições/dia** à API (1 `GET /v1/config`, 1
    `GET /v1/account` ao abrir o app/sincronizar, e eventual `GET /v1/models`, `register` ou
    `redeem`).
  - Aplicando um **fator de pico de $10\times$** (concentração do tráfego diário nos minutos de
    maior movimento ou após um push/atualização na Play Store):
    $$\text{DAU suportado} = \frac{\text{RPS}_{\text{sustentado}} \times 86.400\text{ s/dia}}{3\text{ req/usuário/dia} \times 10\text{ (fator de pico)}} = \text{RPS}_{\text{sustentado}} \times 2.880$$
  - **Tabela de referência rápida para leitura do resultado:**
    - Se sustentar **20 req/s** no baseline estrangulado (0,2 vCPU) → suporta **~57.600 DAU** (~200 mil MAU);
    - Se sustentar **100 req/s** → suporta **~288.000 DAU** (~1 milhão de MAU);
    - Se sustentar **500 req/s** em burst → suporta **~1,44 milhão de DAU**.

---

## Resultados do teste de carga

Medição em 2026-10-23, no binário de release já com a Parte A (índice único em
`accounts.token_hash`, limpeza do rate limit, `CF-Connecting-IP` só com proxy confiável, perfil de
release, pool e threads do Tokio configuráveis). Sem `panic = "abort"`. Postgres **17.10** (o CI
usa 18). Google falso na mesma rede, com **50 ms** de atraso, fora da cota de RAM. O gerador também
fica fora dessa cota.

**Como a máquina foi imitada.** Memória total **512 MiB** para API + Postgres, **256 MiB** cada,
com `--memory-swap` igual a `--memory` (sem swap extra). Rajada: **1,0 + 1,0 vCPU** (os 2 vCPUs do
plano, repartidos como no §6.2). Linha de base: **0,05 + 0,05 vCPU = 0,10 vCPU** no total — os 5%
por vCPU que o §1 dá para o `t4g.nano` / `t3a.nano`. O §1 não publica o percentual do Lightsail
Nano; o §6.2 pedia 0,10 + 0,10. `docker inspect` confirmou `NanoCpus` 1000000000 / 50000000 e
`Memory` 268435456. O kernel, o nginx e o staging da VM real não entram nessa conta: os 512 MiB
foram só da API e do Postgres.

**Como o número foi escolhido.** Laço fechado, degraus de 12 s, concorrência 1, 2, 4, 8, 16, 32,
64. Para quando o erro passa de 1% ou o ganho de req/s fica abaixo de 8%. A tabela traz o último
ponto antes de parar. p50/p99 são só das respostas com o status esperado. Em todo ponto abaixo a
taxa de erro foi **0** (só 200 e 304; nenhum 5xx). OOM: **não** (`OOMKilled=false`).

| Rota | Rajada req/s | p50 | p99 | Base req/s | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| `GET /v1/config` (80% `If-None-Match`) | 2.957 (c=4) | 0,8 ms | 6,4 ms | 177 (c=1) | 2,2 ms | 64 ms |
| `GET /v1/models` (80% `If-None-Match`) | 3.950 (c=8) | 1,4 ms | 8,5 ms | 373 (c=64) | 182 ms | 298 ms |
| `GET /v1/account` (2.000 contas) | 1.971 (c=4) | 1,5 ms | 7,7 ms | 316 (c=8) | 10 ms | 97 ms |
| `POST /v1/register` (50% novo / 50% rotação) | 174 (c=16) | 88 ms | 175 ms | 77 (c=16) | 201 ms | 416 ms |
| `POST /v1/purchases/redeem` (token inédito) | 79 (c=16) | 195 ms | 439 ms | 34 (c=8) | 222 ms | 361 ms |
| Mix v1, 180 s | 942 (c=32) | 22 ms | 165 ms | 172 (c=4) | 3,5 ms | 304 ms |

O mix é 45% config (85% com ETag), 35% account, 10% models (85% com ETag), 8% register, 2% redeem.
O plano pedia 15 min; a corrida longa foi **180 s** depois da rampa. Esses 180 s ficam abaixo do
intervalo de limpeza do rate limit (300 s), então o mix não exercita o `retain_recent`. Na base, `models` ainda subia
~8% no último degrau (c=64): 373 req/s é o topo da escada, com p95 de 231 ms. Na rajada, uma sonda
de 12 s em c=64 no mix deu 902 req/s, abaixo dos 942 do ponto de 180 s.

Metas de p95 do §6.5 (config, models e account abaixo de 50 ms; register e redeem abaixo de 250 ms,
já com os 50 ms do Google). Na rajada, o ponto da tabela fica dentro: config 3,8 ms, models 5,3 ms, account 5,2 ms,
register 125 ms, redeem 244 ms. Na base, o ponto de maior req/s estoura a meta em models, account,
register e redeem. Dentro da meta: config 177 req/s (p95 33 ms); models 196 req/s no c=1 (p95 15
ms); account 192 req/s no c=1 (p95 18 ms); register 68 req/s no c=8 (p95 199 ms); redeem 11 req/s
no c=2 (p95 195 ms).

**Resgate.** Vinte chamadas simultâneas com o mesmo `purchaseToken` deram as vinte `200
{"status":"credited"}`, **1** linha em `purchases` e **1** acknowledge, na rajada e na base. No
throughput da rajada os 2.000 tokens inéditos acabaram no c=16 (79 req/s, zero replay). Os degraus
seguintes foram replay e a regra de parada foi parar em c=64 com 239 req/s, p50 255 ms, p99 523 ms,
100% replay. Uma sonda com 12.000 tokens novos, tabela já grande, deu 113 req/s no c=32 (p99 474
ms) e 131 req/s no c=64 (p99 1,6 s), ainda sem erro e sem replay. Na base o ponto da tabela é só
token inédito (zero replay).

**Índice, na mesma rajada, tabela com ~22 mil contas, c=8.** Com `accounts_token_hash_idx`: Index
Scan, `EXPLAIN ANALYZE` 0,091 ms, 1.161 req/s, p50 5,2 ms, p99 29 ms. Sem o índice: Seq Scan, 7,4
ms, 21.891 linhas filtradas, 208 req/s, p50 36 ms, p99 85 ms. Cerca de 5,6×. Não houve segundo
binário da árvore anterior: o antes/depois é este `DROP INDEX` no binário da Parte A.

**Memória no pico** (`memory.peak` do cgroup; inclui cache de página):

| Regime | API | Postgres |
| --- | --- | --- |
| Rajada | 22,8 MiB | 97,9 MiB |
| Base | 22,0 MiB | 111,1 MiB |

`docker stats` na rajada viu RSS menor (API até ~17 MiB, Postgres até ~77 MiB). Os dois cabem
folgados em 256 + 256 MiB.

**Cota de registro.** A tabela acima usa `REGISTER_RATE_LIMIT_PER_MINUTE=100000` e
`ACCOUNT_RATE_LIMIT_PER_MINUTE=100000`, senão um único IP do gerador esbarra nos 5/min. Com o
limite de produção em 5, oito `POST /v1/register` seguidos responderam
`200, 200, 200, 200, 200, 429, 429, 429`.

**Usuários.** Premissa do §6.5, batida com o mix: um usuário ativo faz **~3 chamadas/dia** (abre o
app: config com ETag e account; models de vez em quando; register só em aparelho novo ou rotação;
redeem é a compra única, 2% do mix). A transcrição do teclado não passa pela API. Fator de pico
**10×**. DAU ≈ req/s do mix × 2.880.

- Rajada: **942 req/s → ~2,7 milhões de usuários ativos/dia**. No ponto medido havia **32
  requisições em voo**. Como cada chamada volta em dezenas de milissegundos, usuários que disparam
  uma chamada no mesmo segundo cabem até **~942**.
- Base (0,10 vCPU contínuo): **172 req/s × 2.880 = 495.360 → ~495 mil usuários ativos/dia**,
  **4 requisições em voo**, até **~172** disparando no mesmo segundo.

Esses tetos são o cgroup num servidor x86_64 de desenvolvimento, com os 512 MiB inteiros para um
par API+Postgres. Na VM real sobram menos (SO, nginx e o staging dividem os mesmos 512 MiB) e o
crédito de CPU do Lightsail não é um `--cpus` fixo. O número da base é o piso se o crédito acabar;
o da rajada é o pico enquanto o crédito existe.

---

## 7. Passo a Passo do Yan vs. O Que a Equipe Faz Sem Credenciais

### 7.1 O que a equipe pode fazer agora (sem credenciais da AWS/Cloudflare/Google)

1. **No código da API (`api/`):**
   - Aplicar os ajustes da Seção 5 (`[profile.release]`, `reqwest::Client` compartilhado,
     `PublisherClient` compartilhado, limpeza periódica do `governor` rate-limiter, cache em memória
     de `/v1/config` e `/v1/models`, `DB_MAX_CONNECTIONS` configurável). `/v1/transcribe` e
     `/v1/ssv/admob` já nascem desligadas (`TRANSCRIBE_ENABLED`, `SSV_ADMOB_ENABLED`).
   - Adicionar migration com índice em `accounts(token_hash)` (descoberto na análise da Seção 6.4)
     e seed real do catálogo de modelos (`native-voice-models`) e parâmetros de anúncios da v1.
   - Permitir sobrescrever `PLAY_INTEGRITY_BASE_URL` e `ANDROID_PUBLISHER_BASE_URL` via env var para
     o teste de carga.
2. **Na suíte de teste de carga (próxima tarefa):**
   - Escrever o mock do Google, os scripts `k6`/`oha` e o `docker-compose.loadtest.yml` com os
     limites de `--cpus` e `--memory` da Seção 6.2 e medir os números reais.
3. **Nos scripts de infraestrutura (`api/deploy/`):**
   - Entregar prontos no repositório:
     - `provision-host.sh` (cria swap de 1 GB, instala Postgres + Nginx, aplica o `postgresql.conf`
       de 0,5 GB/1 GB, cria os bancos `keyflow_prod` e `keyflow_staging`);
     - `keyflow-api-prod.service` e `keyflow-api-staging.service` (units do `systemd` com
       hardening e limites `MemoryMax`/`CPUQuota`);
     - `nginx-keyflow.conf` (configuração TLS Origin CA, restauração de `CF-Connecting-IP` e proxy
       reverso para `:8080` e `:8081`);
     - `backup-postgres.sh` + `keyflow-backup.timer` (`pg_dump` diário para S3).

### 7.2 Passo a passo do Yan nos Consoles (em ordem)

#### Etapa A — Console da AWS
1. **Conta e Proteção de Custo:**
   - Criar/acessar a conta AWS, ativar MFA no usuário root e, em **AWS Budgets**, criar um orçamento
     mensal de **$10,00 USD** com alerta por e-mail em 50% ($5) e 80% ($8). *(Em conta nova pós-julho
     de 2025, isso já libera +$20 de crédito extra do Free Tier).*
2. **Criar a Máquina em `sa-east-1` (São Paulo):**
   - Abrir o console do **Amazon Lightsail** → selecionar a região **South America (São Paulo)
     (`sa-east-1`)** → **Create instance**:
     - Plataforma: **Linux/Unix** → **OS Only** → **Debian 12** (ou Ubuntu 24.04 LTS);
     - Rede: **Dual-stack** (IPv4 + IPv6);
     - Plano: **$5 USD/mês** (512 MB RAM, 2 vCPUs, 20 GB SSD) — ou **$7 USD/mês** (1 GB RAM);
     - Nome: `keyflow-v1`.
   - Na aba **Networking** da instância no Lightsail:
     - Clicar em **Create static IP** e anexar à instância `keyflow-v1` (gratuito enquanto anexado);
     - No **IPv4 Firewall** (e IPv6 Firewall): manter SSH (`22/tcp`) restrito ao IP do Yan, remover
       a porta `80/tcp` e adicionar `443/tcp` restrita aos IPs da Cloudflare.
3. **Bucket S3 para Backups do Banco:**
   - No console do **Amazon S3**, criar um bucket privado `keyflow-db-backups-prod` em `sa-east-1`
     com *Lifecycle Rule* para expirar objetos após **30 dias**, e criar uma IAM Policy/User restrita
     só a `s3:PutObject` nesse bucket.

#### Etapa B — Console do Google Cloud / Play Console (se ainda não criado na API-T13)
4. No **Google Cloud Console**, habilitar a **Play Integrity API** e a **Google Play Android
   Developer API**, criar uma Service Account, baixar o JSON da chave privada e vinculá-la ao app
   `com.arateki.keyflow` no **Google Play Console** (com permissão de visualizar dados financeiros/gerenciar
   pedidos para validar compras e ler `voidedpurchases`).

#### Etapa C — Console da Cloudflare e Servidor
5. **DNS e TLS na Cloudflare:**
   - Criar registro `A` para `api.<dominio>` apontando para o Static IPv4 da instância com **Proxy
     ativado (nuvem laranja)**;
   - Em **SSL/TLS → Overview**, selecionar **Full (strict)**; em **Edge Certificates**, ligar
     **Always Use HTTPS**;
   - Em **SSL/TLS → Origin Server**, clicar em **Create Certificate** (validade 15 anos) e salvar o
     certificado `.pem` e a chave `.key` em `/etc/nginx/ssl/` no servidor.
6. **Acesso Restrito do Staging:**
   - Instalar o **Tailscale** no servidor (`curl -fsSL https://tailscale.com/install.sh | sh && sudo
     tailscale up`) e no celular/PC do Yan, apontando o endereço de staging para o IP `100.x.y.z`
     da Tailnet (ou criar o registro `staging-api.<dominio>` protegido por Cloudflare Zero Trust /
     WAF Rule para o IP do Yan).
7. **Subir os Serviços:**
   - Copiar o JSON da Service Account do Google para `/etc/keyflow/google-sa.json` (`chmod 600`),
     preencher `/etc/keyflow/prod.env` e `/etc/keyflow/staging.env` e iniciar `systemctl enable --now
     keyflow-api-prod keyflow-api-staging`.

---

## Fontes Oficiais Consultadas (Leitura em 2026-10-23)

1. **Amazon EC2 On-Demand Pricing (`t4g.nano`, `t4g.micro`, `t3a.nano`, Data Transfer Out):**
   - <https://aws.amazon.com/ec2/pricing/on-demand/> (consultado em 2026-10-23)
2. **Amazon Lightsail Pricing (Planos Linux Dual-Stack $5/$7, IPv6-only $3.50/$5, Snapshots $0.05/GB):**
   - <https://aws.amazon.com/lightsail/pricing/> (consultado em 2026-10-23)
3. **Amazon Lightsail Supported Regions (`sa-east-1` São Paulo e `us-east-1` N. Virginia):**
   - <https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-regions-and-availability-zones-in-amazon-lightsail.html> (consultado em 2026-10-23)
4. **Amazon EBS Pricing (`gp3` Storage e Snapshots em `us-east-1` e `sa-east-1`):**
   - <https://aws.amazon.com/ebs/pricing/> (consultado em 2026-10-23)
5. **Amazon VPC Pricing (Cobrança de IPv4 Público `$0.005/hora`):**
   - <https://aws.amazon.com/vpc/pricing/> (consultado em 2026-10-23)
6. **Amazon RDS for PostgreSQL Pricing (`db.t4g.micro` e armazenamento `gp3`):**
   - <https://aws.amazon.com/rds/postgresql/pricing/> (consultado em 2026-10-23)
7. **AWS Free Tier (Novo modelo de até $200 USD em créditos por 6 meses desde julho/2025 e 100 GB/mês DTO):**
   - <https://aws.amazon.com/free/> (consultado em 2026-10-23)
8. **Cloudflare Official IP Ranges (Firewall porta 443):**
   - <https://www.cloudflare.com/ips/> (consultado em 2026-10-23)

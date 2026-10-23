#!/usr/bin/env bash
# Script idempotente de provisionamento da máquina da KeyFlow API (Amazon Lightsail Debian 12 x86_64, 447 MB RAM).
# Execução remota pelo coordenador:
#   ssh admin@<IP_DA_MAQUINA> 'sudo bash -s' < api/deploy/provisionar.sh
# Ou informando os domínios / chaves públicas Ed25519 na linha de comando:
#   ssh admin@<IP_DA_MAQUINA> 'sudo KEYFLOW_DOMAIN=keyflow-api.arateki.com KEYFLOW_STAGING_DOMAIN=keyflow-api-staging.arateki.com bash -s' < api/deploy/provisionar.sh
#
# Regras de segurança:
# - Nunca usa `set -x` e nunca imprime senhas ou chaves no terminal/log.
# - Preserva senhas já geradas em /etc/keyflow/{prod,staging}.env em execuções subsequentes; se o .env
#   existir mas estiver corrompido/sem senha legível, aborta (exit 1) para não dessincronizar o banco.

set -euo pipefail
set +x
umask 022

# ==============================================================================
# 0. Domínios (produção e staging) e parâmetros globais
# ==============================================================================
CONF_DIR="/etc/keyflow"
TLS_DIR="${CONF_DIR}/tls"
DOMAIN_CONF="${CONF_DIR}/domain.conf"
DEPLOY_CONF="${CONF_DIR}/deploy.conf"

# O que vier no ambiente vence o arquivo. O que não vier é reaproveitado de domain.conf
# (execuções antigas só tinham KEYFLOW_DOMAIN; o staging cai no padrão e passa a ser gravado).
_dominio_env="${KEYFLOW_DOMAIN:-}"
_staging_env="${KEYFLOW_STAGING_DOMAIN:-}"
if [[ -f "${DOMAIN_CONF}" ]]; then
    # shellcheck source=/dev/null
    source "${DOMAIN_CONF}"
fi
if [[ -n "${_dominio_env}" ]]; then
    KEYFLOW_DOMAIN="${_dominio_env}"
fi
if [[ -n "${_staging_env}" ]]; then
    KEYFLOW_STAGING_DOMAIN="${_staging_env}"
fi
unset _dominio_env _staging_env
KEYFLOW_DOMAIN="${KEYFLOW_DOMAIN:-keyflow-api.arateki.com}"
KEYFLOW_STAGING_DOMAIN="${KEYFLOW_STAGING_DOMAIN:-keyflow-api-staging.arateki.com}"
GITHUB_REPO="${GITHUB_REPO:-Arateki/Keyflow}"
SWAP_FILE="${SWAP_FILE:-/swapfile}"
SWAP_SIZE_MB="${SWAP_SIZE_MB:-1024}"

log() {
    printf '[provisionar] %s\n' "$*"
}

aviso() {
    printf '[provisionar] AVISO: %s\n' "$*" >&2
}

erro() {
    printf '[provisionar] ERRO: %s\n' "$*" >&2
    exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
    erro "Este script precisa rodar como root (use: sudo bash -s < provisionar.sh)."
fi

validar_nome_de_dominio() {
    local nome="$1"
    local valor="$2"
    # Hostname DNS (letras, dígitos e hífen). Recusa espaço, aspas e '$', que quebrariam o nginx.
    if [[ ! "${valor}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$ ]]; then
        erro "Valor inválido para ${nome} (${valor}). Use um hostname como keyflow-api.arateki.com."
    fi
}

validar_nome_de_dominio "KEYFLOW_DOMAIN" "${KEYFLOW_DOMAIN}"
validar_nome_de_dominio "KEYFLOW_STAGING_DOMAIN" "${KEYFLOW_STAGING_DOMAIN}"
if [[ "${KEYFLOW_DOMAIN}" == "${KEYFLOW_STAGING_DOMAIN}" ]]; then
    erro "KEYFLOW_DOMAIN e KEYFLOW_STAGING_DOMAIN são iguais (${KEYFLOW_DOMAIN}); os vhosts ficariam ambíguos."
fi

tem_systemd() {
    [[ -d /run/systemd/system ]]
}

reiniciar_servico_sistema() {
    local svc="$1"
    if tem_systemd; then
        systemctl enable "${svc}" >/dev/null 2>&1 || true
        systemctl restart "${svc}"
    else
        service "${svc}" restart || /etc/init.d/"${svc}" restart
    fi
}

recarregar_servico_sistema() {
    local svc="$1"
    if tem_systemd; then
        systemctl reload "${svc}"
    else
        service "${svc}" reload || /etc/init.d/"${svc}" reload
    fi
}

# nginx -t antes de qualquer reload. Se o processo ainda não existe, inicia.
recarregar_ou_iniciar_nginx() {
    nginx -t
    if tem_systemd && systemctl is-active --quiet nginx; then
        log "Configuração do nginx válida; recarregando o serviço."
        recarregar_servico_sistema nginx
    elif command -v pgrep >/dev/null 2>&1 && pgrep -x nginx >/dev/null 2>&1; then
        log "Configuração do nginx válida; recarregando o processo."
        recarregar_servico_sistema nginx
    else
        log "Configuração do nginx válida; iniciando o serviço."
        reiniciar_servico_sistema nginx
    fi
}

# ==============================================================================
# 1. Swap de 1 GB e sysctl (aws.md §2.3)
# ==============================================================================
configurar_swap_e_sysctl() {
    log "Verificando swap em ${SWAP_FILE} (${SWAP_SIZE_MB} MiB)..."
    if ! swapon --show=NAME --noheadings 2>/dev/null | grep -qx "${SWAP_FILE}"; then
        if [[ ! -f "${SWAP_FILE}" ]]; then
            log "Criando arquivo de swap ${SWAP_FILE} (${SWAP_SIZE_MB} MiB)..."
            if ! fallocate -l "${SWAP_SIZE_MB}M" "${SWAP_FILE}" 2>/dev/null; then
                dd if=/dev/zero of="${SWAP_FILE}" bs=1M count="${SWAP_SIZE_MB}" status=none
            fi
        fi
        chmod 600 "${SWAP_FILE}"
        chown root:root "${SWAP_FILE}"
        mkswap "${SWAP_FILE}" >/dev/null
        if ! swapon "${SWAP_FILE}"; then
            if [[ "${ALLOW_NO_SWAP_FOR_CONTAINER_TEST:-0}" == "1" ]]; then
                aviso "Swap não pôde ser ativado neste container de teste (ALLOW_NO_SWAP_FOR_CONTAINER_TEST=1); arquivo preparado."
            else
                erro "Falha ao ativar swap em ${SWAP_FILE}! Abortando antes do apt para não estourar os 447 MB de RAM."
            fi
        fi
    else
        chmod 600 "${SWAP_FILE}"
        log "Swap ${SWAP_FILE} já está ativo."
    fi

    if [[ -f /etc/fstab ]] && ! grep -qE "^${SWAP_FILE}[[:space:]]" /etc/fstab; then
        printf '%s none swap sw 0 0\n' "${SWAP_FILE}" >> /etc/fstab
    fi

    log "Configurando /etc/sysctl.d/99-keyflow.conf (vm.swappiness=10, vm.vfs_cache_pressure=50)..."
    mkdir -p /etc/sysctl.d
    cat > /etc/sysctl.d/99-keyflow.conf <<'EOF'
# Ajustes de memória para máquina de 0,5 GB (api/docs/aws.md §2.3)
vm.swappiness = 10
vm.vfs_cache_pressure = 50
EOF
    if ! sysctl -p /etc/sysctl.d/99-keyflow.conf >/dev/null 2>&1; then
        if [[ "${ALLOW_NO_SWAP_FOR_CONTAINER_TEST:-0}" == "1" ]]; then
            aviso "sysctl não pôde aplicar parâmetros de VM neste container de teste (ALLOW_NO_SWAP_FOR_CONTAINER_TEST=1)."
        else
            erro "Falha ao aplicar parâmetros de VM com sysctl -p /etc/sysctl.d/99-keyflow.conf."
        fi
    fi
}

# ==============================================================================
# 2. Pacotes do Debian 12 + unattended-upgrades (sem AWS CLI)
#    Usa /usr/sbin/policy-rc.d (exit 101) para impedir que o apt inicie o
#    PostgreSQL com shared_buffers=128MB antes de gravarmos o 99-keyflow.conf.
# ==============================================================================
instalar_pacotes_e_atualizacoes() {
    log "Instalando pacotes base (postgresql, nginx, unattended-upgrades, curl, jq, openssl, procps)..."
    export DEBIAN_FRONTEND=noninteractive

    local policy_rc="/usr/sbin/policy-rc.d"
    cat > "${policy_rc}" <<'EOF'
#!/bin/sh
exit 101
EOF
    chmod 0755 "${policy_rc}"
    trap 'rm -f /usr/sbin/policy-rc.d' EXIT

    apt-get update -qq
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        jq \
        openssl \
        procps \
        nginx \
        postgresql \
        postgresql-client \
        unattended-upgrades \
        apt-listchanges

    rm -f "${policy_rc}"
    trap - EXIT

    log "Configurando unattended-upgrades (atualizações de segurança sem reboot automático)..."
    cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF

    cat > /etc/apt/apt.conf.d/52keyflow-unattended-upgrades <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
EOF
}

# ==============================================================================
# 3. Usuários de sistema isolados (`keyflow-prod` e `keyflow-staging`) e diretórios
# ==============================================================================
instalar_chave_publica_canal() {
    local arquivo="$1"
    local conteudo_pem="$2"
    local descricao="$3"

    if [[ -n "${conteudo_pem}" ]]; then
        printf '%s\n' "${conteudo_pem}" > "${arquivo}"
        chown root:root "${arquivo}"
        chmod 0644 "${arquivo}"
        log "Chave pública Ed25519 (${descricao}) gravada em ${arquivo}."
    elif [[ ! -f "${arquivo}" ]]; then
        cat > "${arquivo}" <<'EOF'
-----BEGIN PUBLIC KEY-----
SUBSTITUIR_PELA_CHAVE_PUBLICA_ED25519_DO_COORDENADOR
-----END PUBLIC KEY-----
EOF
        chown root:root "${arquivo}"
        chmod 0644 "${arquivo}"
    fi
}

configurar_usuario_e_diretorios() {
    for usr in keyflow-prod keyflow-staging; do
        if ! id -u "${usr}" >/dev/null 2>&1; then
            log "Criando usuário de sistema isolado '${usr}'..."
            useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin "${usr}"
        fi
    done

    # /etc/keyflow é 0755 root:root para permitir travessia por keyflow-prod e keyflow-staging;
    # cada segredo lá dentro tem dono/grupo e modo estritos (prod.env/staging.env 0600 root:root;
    # google-sa-prod.json 0640 root:keyflow-prod; google-sa-staging.json 0640 root:keyflow-staging).
    install -d -m 0755 -o root -g root "${CONF_DIR}"
    install -d -m 0750 -o root -g root "${TLS_DIR}"
    install -d -m 0755 -o root -g root \
        /opt/keyflow/state \
        /opt/keyflow/prod/bin \
        /opt/keyflow/prod/state \
        /opt/keyflow/staging/bin \
        /opt/keyflow/staging/state

    {
        printf '%s\n' '# Domínios do Nginx. Produção e staging dividem esta máquina.'
        printf 'KEYFLOW_DOMAIN="%s"\n' "${KEYFLOW_DOMAIN}"
        printf 'KEYFLOW_STAGING_DOMAIN="%s"\n' "${KEYFLOW_STAGING_DOMAIN}"
    } > "${DOMAIN_CONF}"
    chmod 0644 "${DOMAIN_CONF}"

    if [[ ! -f "${DEPLOY_CONF}" ]]; then
        cat > "${DEPLOY_CONF}" <<EOF
# Configuração do agente de pull de releases (keyflow-atualizar.sh)
GITHUB_REPO="${GITHUB_REPO}"
EOF
        chmod 0644 "${DEPLOY_CONF}"
    fi

    # Chaves públicas Ed25519 separadas por canal (staging e produção nunca compartilham a mesma chave):
    instalar_chave_publica_canal \
        "${CONF_DIR}/release-signing-staging-pub.pem" \
        "${KEYFLOW_RELEASE_STAGING_PUBKEY_PEM:-}" \
        "staging"
    instalar_chave_publica_canal \
        "${CONF_DIR}/release-signing-prod-pub.pem" \
        "${KEYFLOW_RELEASE_PROD_PUBKEY_PEM:-}" \
        "producao"
}

# ==============================================================================
# 4. PostgreSQL (aws.md §2.2) + bancos e papéis separados (prod e staging)
# ==============================================================================
configurar_postgres() {
    log "Ajustando configuração do PostgreSQL para 0,5 GB de RAM (aws.md §2.2) antes de iniciar o serviço..."
    local pg_conf_dir=""
    for dir in /etc/postgresql/*/main/conf.d; do
        if [[ -d "${dir}" ]]; then
            pg_conf_dir="${dir}"
            break
        fi
    done

    if [[ -z "${pg_conf_dir}" ]]; then
        erro "Diretório /etc/postgresql/*/main/conf.d não encontrado após instalar postgresql."
    fi

    cat > "${pg_conf_dir}/99-keyflow.conf" <<'EOF'
# Ajuste para máquina de 447 MB de RAM (api/docs/aws.md §2.2)
listen_addresses = '127.0.0.1'
port = 5432

# Memória
shared_buffers = 64MB
effective_cache_size = 192MB
work_mem = 2MB
maintenance_work_mem = 16MB
wal_buffers = 2MB
huge_pages = off

# Conexões e workers
max_connections = 20
max_worker_processes = 2
max_parallel_workers_per_gather = 0
max_parallel_workers = 0
max_parallel_maintenance_workers = 1

# I/O em SSD e integridade do ledger (OPS-01)
random_page_cost = 1.1
effective_io_concurrency = 200
synchronous_commit = on
EOF
    chmod 0644 "${pg_conf_dir}/99-keyflow.conf"

    reiniciar_servico_sistema postgresql
}

extrair_senha_existente() {
    local env_file="$1"
    local usuario="$2"
    if [[ -f "${env_file}" ]]; then
        sed -n "s|^DATABASE_URL=postgres://${usuario}:\([0-9a-fA-F]\{16,128\}\)@127\.0\.0\.1:5432/.*|\1|p" "${env_file}" | head -n 1
    fi
}

garantir_placeholder_sa() {
    local sa_file="$1"
    local grupo_servico="$2"

    if [[ ! -s "${sa_file}" ]]; then
        log "Criando placeholder inicial em ${sa_file} (grupo ${grupo_servico})..."
        local rsa_key
        rsa_key="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
        jq -n \
            --arg email "placeholder-substituir-pela-service-account-do-yan@keyflow.invalid" \
            --arg key "${rsa_key}" \
            --arg uri "https://oauth2.googleapis.com/token" \
            '{type: "service_account", client_email: $email, private_key: $key, token_uri: $uri}' > "${sa_file}"
    fi
    chown "root:${grupo_servico}" "${sa_file}"
    chmod 0640 "${sa_file}"
}

provisionar_bancos_e_envs() {
    log "Configurando papéis e bancos isolados keyflow_prod e keyflow_staging..."
    umask 077

    local prod_env="${CONF_DIR}/prod.env"
    local staging_env="${CONF_DIR}/staging.env"
    local sa_prod="${CONF_DIR}/google-sa-prod.json"
    local sa_staging="${CONF_DIR}/google-sa-staging.json"

    local pass_prod=""
    if [[ -f "${prod_env}" ]]; then
        pass_prod="$(extrair_senha_existente "${prod_env}" "keyflow_prod")"
        if [[ -z "${pass_prod}" ]]; then
            erro "Arquivo ${prod_env} já existe, mas não foi possível extrair a senha de DATABASE_URL! Abortando para não dessincronizar o banco do .env."
        fi
    else
        pass_prod="$(openssl rand -hex 24)"
    fi

    local pass_staging=""
    if [[ -f "${staging_env}" ]]; then
        pass_staging="$(extrair_senha_existente "${staging_env}" "keyflow_staging")"
        if [[ -z "${pass_staging}" ]]; then
            erro "Arquivo ${staging_env} já existe, mas não foi possível extrair a senha de DATABASE_URL! Abortando para não dessincronizar o banco do .env."
        fi
    else
        pass_staging="$(openssl rand -hex 24)"
    fi

    if [[ ! "${pass_prod}" =~ ^[0-9a-fA-F]{16,128}$ || ! "${pass_staging}" =~ ^[0-9a-fA-F]{16,128}$ ]]; then
        erro "Formato inesperado de senha do banco; abortando."
    fi

    # Nunca passa senhas via `psql -v` na linha de comando (/proc/PID/cmdline / ps); usa stdin.
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -q >/dev/null <<EOSQL
\set ON_ERROR_STOP on
\set QUIET on
\set pass_prod '${pass_prod}'
\set pass_staging '${pass_staging}'

SELECT format('CREATE ROLE keyflow_prod LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'pass_prod')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keyflow_prod') \gexec

ALTER ROLE keyflow_prod WITH LOGIN PASSWORD :'pass_prod' NOSUPERUSER NOCREATEDB NOCREATEROLE;

SELECT format('CREATE ROLE keyflow_staging LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'pass_staging')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keyflow_staging') \gexec

ALTER ROLE keyflow_staging WITH LOGIN PASSWORD :'pass_staging' NOSUPERUSER NOCREATEDB NOCREATEROLE;

SELECT 'CREATE DATABASE keyflow_prod OWNER keyflow_prod ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keyflow_prod') \gexec

SELECT 'CREATE DATABASE keyflow_staging OWNER keyflow_staging ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keyflow_staging') \gexec

REVOKE ALL ON DATABASE keyflow_prod FROM PUBLIC;
REVOKE ALL ON DATABASE keyflow_prod FROM keyflow_staging;
GRANT CONNECT, TEMPORARY ON DATABASE keyflow_prod TO keyflow_prod;

REVOKE ALL ON DATABASE keyflow_staging FROM PUBLIC;
REVOKE ALL ON DATABASE keyflow_staging FROM keyflow_prod;
GRANT CONNECT, TEMPORARY ON DATABASE keyflow_staging TO keyflow_staging;
EOSQL

    # Pré-instala pgcrypto como superuser nos dois bancos para que as migrations de sqlx::migrate!()
    # rodem com o role comum (sem superuser) e garante isolamento do schema public.
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -q -d keyflow_prod <<'EOSQL' >/dev/null
CREATE EXTENSION IF NOT EXISTS pgcrypto;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO keyflow_prod;
EOSQL

    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -q -d keyflow_staging <<'EOSQL' >/dev/null
CREATE EXTENSION IF NOT EXISTS pgcrypto;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO keyflow_staging;
EOSQL

    # Credenciais Google Service Account isoladas por usuário/grupo (staging jamais lê a de produção):
    rm -f "${CONF_DIR}/google-sa.json"
    garantir_placeholder_sa "${sa_prod}" "keyflow-prod"
    garantir_placeholder_sa "${sa_staging}" "keyflow-staging"

    if [[ ! -f "${prod_env}" ]]; then
        log "Gerando ${prod_env} (modo 0600, root:root)..."
        cat > "${prod_env}" <<EOF
# Ambiente de PRODUÇÃO da KeyFlow API (gerado por provisionar.sh — modo 0600, nunca imprimir).
DATABASE_URL=postgres://keyflow_prod:${pass_prod}@127.0.0.1:5432/keyflow_prod
BIND_ADDR=127.0.0.1:8080
PACKAGE_NAME=com.keyflow
GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa-prod.json
INTEGRITY_RELAXED=false
REGISTER_RATE_LIMIT_PER_MINUTE=5
ACCOUNT_RATE_LIMIT_PER_MINUTE=60
VOIDED_PURCHASES_JOB_INTERVAL_SECS=3600

# Proxy reverso local confiável (branch equipe/api-carga): aceita CF-Connecting-IP vindo só do nginx em loopback.
TRUSTED_PROXIES=127.0.0.1, ::1

# Limites de pool e runtime para 0,5 GB de RAM (branch equipe/api-carga; obrigatória na main antes do 1º deploy).
DB_MAX_CONNECTIONS=5
DB_MIN_CONNECTIONS=0
DB_IDLE_TIMEOUT_SECS=300
DB_ACQUIRE_TIMEOUT_SECS=5
TOKIO_WORKER_THREADS=2
TOKIO_MAX_BLOCKING_THREADS=8

# Na v1 créditos estão desligados (decisão 2026-10-23), mas Config::from_env() exige ao menos 1 item válido:
TRANSCRIPTION_PROVIDERS_JSON=[{"name":"disabled-v1","url":"http://127.0.0.1:9/unused","authHeaderName":"Authorization","authValueEnv":"TRANSCRIPTION_DISABLED_KEY","model":"unused"}]
TRANSCRIPTION_DISABLED_KEY=unused-v1
RUST_LOG=info,tower_http=info
EOF
    else
        sed -i 's|^GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa\.json$|GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa-prod.json|' "${prod_env}"
    fi
    chown root:root "${prod_env}"
    chmod 0600 "${prod_env}"

    if [[ ! -f "${staging_env}" ]]; then
        log "Gerando ${staging_env} (modo 0600, root:root)..."
        cat > "${staging_env}" <<EOF
# Ambiente de STAGING da KeyFlow API (gerado por provisionar.sh — modo 0600, nunca imprimir).
DATABASE_URL=postgres://keyflow_staging:${pass_staging}@127.0.0.1:5432/keyflow_staging
BIND_ADDR=127.0.0.1:8081
PACKAGE_NAME=com.keyflow
GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa-staging.json
INTEGRITY_RELAXED=true
REGISTER_RATE_LIMIT_PER_MINUTE=10
ACCOUNT_RATE_LIMIT_PER_MINUTE=60
VOIDED_PURCHASES_JOB_INTERVAL_SECS=3600

# Proxy reverso local confiável (branch equipe/api-carga): aceita CF-Connecting-IP vindo só do loopback.
TRUSTED_PROXIES=127.0.0.1, ::1

# Limites enxutos para o staging (aws.md §2.4 / §3; branch equipe/api-carga):
DB_MAX_CONNECTIONS=3
DB_MIN_CONNECTIONS=0
DB_IDLE_TIMEOUT_SECS=60
DB_ACQUIRE_TIMEOUT_SECS=5
TOKIO_WORKER_THREADS=1
TOKIO_MAX_BLOCKING_THREADS=4

# Na v1 créditos estão desligados (decisão 2026-10-23), mas Config::from_env() exige ao menos 1 item válido:
TRANSCRIPTION_PROVIDERS_JSON=[{"name":"disabled-v1","url":"http://127.0.0.1:9/unused","authHeaderName":"Authorization","authValueEnv":"TRANSCRIPTION_DISABLED_KEY","model":"unused"}]
TRANSCRIPTION_DISABLED_KEY=unused-v1
RUST_LOG=info,tower_http=debug
EOF
    else
        sed -i 's|^GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa\.json$|GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/etc/keyflow/google-sa-staging.json|' "${staging_env}"
    fi
    chown root:root "${staging_env}"
    chmod 0600 "${staging_env}"

    unset pass_prod pass_staging
    umask 022
}

# ==============================================================================
# 5. Nginx com TLS de origem e repasse de CF-Connecting-IP (aws.md §4)
#    Produção e staging dividem a máquina. O arquivo é reescrito por inteiro
#    a cada execução (rodar de novo não duplica server).
# ==============================================================================
configurar_nginx() {
    log "Configurando Nginx: produção ${KEYFLOW_DOMAIN} → :8080; staging ${KEYFLOW_STAGING_DOMAIN} → :8081..."
    local site_avail="/etc/nginx/sites-available/keyflow-api.conf"
    local site_enab="/etc/nginx/sites-enabled/keyflow-api.conf"
    local cert_pem="${TLS_DIR}/origin.pem"
    local cert_key="${TLS_DIR}/origin.key"

    # Um único heredoc: a extração para o teste do nginx -t renderiza os três server.
    cat > "${site_avail}" <<EONGINX
# Configuração gerada por provisionar.sh — domínios em ${DOMAIN_CONF}.
# Produção e staging dividem esta máquina e o mesmo certificado de origem (*.arateki.com).
# Host desconhecido cai no default_server (444) e não herda o vhost da produção.
# listen ... http2 é a sintaxe do nginx 1.22 (Debian 12). No 1.25+ só gera aviso.

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    listen 443 ssl http2 default_server;
    listen [::]:443 ssl http2 default_server;
    server_name _;

    ssl_certificate     ${cert_pem};
    ssl_certificate_key ${cert_key};
    ssl_protocols       TLSv1.2 TLSv1.3;

    return 444;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${KEYFLOW_DOMAIN};

    ssl_certificate     ${cert_pem};
    ssl_certificate_key ${cert_key};
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:4m;
    ssl_session_timeout 1d;

    # Faixas oficiais da Cloudflare (https://www.cloudflare.com/ips/) para extrair o IP real:
    set_real_ip_from 173.245.48.0/20;
    set_real_ip_from 103.21.244.0/22;
    set_real_ip_from 103.22.200.0/22;
    set_real_ip_from 103.31.4.0/22;
    set_real_ip_from 141.101.64.0/18;
    set_real_ip_from 108.162.192.0/18;
    set_real_ip_from 190.93.240.0/20;
    set_real_ip_from 188.114.96.0/20;
    set_real_ip_from 197.234.240.0/22;
    set_real_ip_from 198.41.128.0/17;
    set_real_ip_from 162.158.0.0/15;
    set_real_ip_from 104.16.0.0/13;
    set_real_ip_from 104.24.0.0/14;
    set_real_ip_from 172.64.0.0/13;
    set_real_ip_from 131.0.72.0/22;
    set_real_ip_from 2400:cb00::/32;
    set_real_ip_from 2606:4700::/32;
    set_real_ip_from 2803:f800::/32;
    set_real_ip_from 2405:b500::/32;
    set_real_ip_from 2405:8100::/32;
    set_real_ip_from 2a06:98c0::/29;
    set_real_ip_from 2c0f:f248::/32;
    real_ip_header CF-Connecting-IP;
    real_ip_recursive on;

    client_max_body_size 1m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        # Repassa o IP autenticado pela Cloudflare para a API (que confia em TRUSTED_PROXIES=127.0.0.1, ::1):
        proxy_set_header CF-Connecting-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 35s;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${KEYFLOW_STAGING_DOMAIN};

    ssl_certificate     ${cert_pem};
    ssl_certificate_key ${cert_key};
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:4m;
    ssl_session_timeout 1d;

    # Faixas oficiais da Cloudflare (https://www.cloudflare.com/ips/) para extrair o IP real:
    set_real_ip_from 173.245.48.0/20;
    set_real_ip_from 103.21.244.0/22;
    set_real_ip_from 103.22.200.0/22;
    set_real_ip_from 103.31.4.0/22;
    set_real_ip_from 141.101.64.0/18;
    set_real_ip_from 108.162.192.0/18;
    set_real_ip_from 190.93.240.0/20;
    set_real_ip_from 188.114.96.0/20;
    set_real_ip_from 197.234.240.0/22;
    set_real_ip_from 198.41.128.0/17;
    set_real_ip_from 162.158.0.0/15;
    set_real_ip_from 104.16.0.0/13;
    set_real_ip_from 104.24.0.0/14;
    set_real_ip_from 172.64.0.0/13;
    set_real_ip_from 131.0.72.0/22;
    set_real_ip_from 2400:cb00::/32;
    set_real_ip_from 2606:4700::/32;
    set_real_ip_from 2803:f800::/32;
    set_real_ip_from 2405:b500::/32;
    set_real_ip_from 2405:8100::/32;
    set_real_ip_from 2a06:98c0::/29;
    set_real_ip_from 2c0f:f248::/32;
    real_ip_header CF-Connecting-IP;
    real_ip_recursive on;

    client_max_body_size 1m;

    # O Cloudflare Access injeta Cf-Access-Jwt-Assertion em todo pedido que deixou passar.
    # Não valida a assinatura: só recusa acesso direto à origem, sem o cabeçalho.
    if (\$http_cf_access_jwt_assertion = "") {
        return 403;
    }

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        # Repassa o IP autenticado pela Cloudflare para a API (que confia em TRUSTED_PROXIES=127.0.0.1, ::1):
        proxy_set_header CF-Connecting-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 35s;
    }
}
EONGINX
    chmod 0644 "${site_avail}"

    rm -f /etc/nginx/sites-enabled/default

    if [[ -s "${cert_pem}" && -s "${cert_key}" ]]; then
        chmod 0644 "${cert_pem}"
        chmod 0600 "${cert_key}"
        chown root:root "${cert_pem}" "${cert_key}"
        ln -sfn "${site_avail}" "${site_enab}"
        recarregar_ou_iniciar_nginx
        log "Certificado TLS encontrado em ${TLS_DIR}; sites '${KEYFLOW_DOMAIN}' e '${KEYFLOW_STAGING_DOMAIN}' ATIVADOS."
    else
        rm -f "${site_enab}"
        recarregar_ou_iniciar_nginx
        aviso "Certificado TLS ainda não existe em ${cert_pem} e ${cert_key}; site Nginx mantido DESLIGADO até os dois arquivos serem instalados."
    fi
}

# ==============================================================================
# 6. Scripts operacionais (/usr/local/sbin/keyflow-backup.sh e keyflow-atualizar.sh)
# ==============================================================================
instalar_scripts_operacionais() {
    log "Instalando /usr/local/sbin/keyflow-backup.sh e /usr/local/sbin/keyflow-atualizar.sh..."

    cat > /usr/local/sbin/keyflow-backup.sh <<'EOBACKUP'
#!/usr/bin/env bash
# Backup diário dos bancos keyflow_prod e keyflow_staging para o Amazon S3 usando curl --aws-sigv4.
# Não instala nem depende do AWS CLI (pesado para a máquina de 447 MB de RAM).
# As credenciais vêm exclusivamente de /etc/keyflow/backup.env (0600, root:root) e nunca são impressas.

set -euo pipefail
set +x
umask 077

BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/etc/keyflow/backup.env}"
DATABASES=("keyflow_prod" "keyflow_staging")

log() {
    printf '[keyflow-backup] %s\n' "$*"
}

erro() {
    printf '[keyflow-backup] ERRO: %s\n' "$*" >&2
    exit 1
}

if [[ ! -f "${BACKUP_ENV_FILE}" || -L "${BACKUP_ENV_FILE}" ]]; then
    erro "Arquivo de credenciais ${BACKUP_ENV_FILE} não encontrado ou é um link simbólico."
fi

modo_env="$(stat -c '%a' "${BACKUP_ENV_FILE}")"
dono_env="$(stat -c '%u:%g' "${BACKUP_ENV_FILE}")"
if [[ "${modo_env}" != "600" || "${dono_env}" != "0:0" ]]; then
    erro "Permissão ou dono inseguro em ${BACKUP_ENV_FILE} (encontrado: modo=${modo_env} dono=${dono_env}; exigido: modo=600 dono=0:0 root:root)."
fi

# Carrega AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION e BACKUP_BUCKET sem ecoar.
# shellcheck source=/dev/null
source "${BACKUP_ENV_FILE}"

if [[ -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" || -z "${AWS_DEFAULT_REGION:-}" || -z "${BACKUP_BUCKET:-}" ]]; then
    erro "Variáveis obrigatórias ausentes em ${BACKUP_ENV_FILE} (esperado: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION, BACKUP_BUCKET)."
fi

WORKDIR="$(mktemp -d /tmp/keyflow-backup.XXXXXX)"
CURL_CFG="${WORKDIR}/curl.cfg"
cleanup() {
    rm -rf "${WORKDIR}"
}
trap cleanup EXIT

# Passa credenciais via arquivo 0600 (--config) para nunca expor a chave na linha de comando (/proc/PID/cmdline).
printf 'user = "%s:%s"\n' "${AWS_ACCESS_KEY_ID}" "${AWS_SECRET_ACCESS_KEY}" > "${CURL_CFG}"
chmod 600 "${CURL_CFG}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
ANO="$(date -u +%Y)"
MES="$(date -u +%m)"
S3_ENDPOINT="${S3_ENDPOINT:-https://${BACKUP_BUCKET}.s3.${AWS_DEFAULT_REGION}.amazonaws.com}"

enviar_s3() {
    local arquivo="$1"
    local chave_s3="$2"
    local sha256="$3"

    curl --fail --silent --show-error \
        --max-time 180 \
        --retry 3 \
        --retry-delay 5 \
        --aws-sigv4 "aws:amz:${AWS_DEFAULT_REGION}:s3" \
        --config "${CURL_CFG}" \
        -H "x-amz-content-sha256: ${sha256}" \
        -H "x-amz-server-side-encryption: AES256" \
        -H "Content-Type: application/octet-stream" \
        --upload-file "${arquivo}" \
        "${S3_ENDPOINT}/${chave_s3}" >/dev/null
}

for db in "${DATABASES[@]}"; do
    dump_file="${WORKDIR}/${db}-${TS}.dump"
    log "Gerando dump comprimido do banco ${db}..."
    # O shell root abre "${dump_file}" dentro de WORKDIR (0700 root:root) antes do runuser trocar para postgres.
    runuser -u postgres -- pg_dump --format=custom --compress=9 "${db}" > "${dump_file}"

    if [[ ! -s "${dump_file}" ]]; then
        erro "Dump gerado para ${db} está vazio."
    fi

    sha256="$(sha256sum "${dump_file}" | awk '{print $1}')"
    tamanho="$(stat -c '%s' "${dump_file}")"
    chave_datada="${db}/${ANO}/${MES}/${db}-${TS}.dump"
    chave_latest="${db}/latest.dump"

    if [[ -n "${BACKUP_DUMP_OUT_DIR:-}" ]]; then
        cp -pf "${dump_file}" "${BACKUP_DUMP_OUT_DIR}/${db}-latest.dump"
    fi

    log "Enviando ${chave_datada} (${tamanho} bytes, sha256=${sha256:0:12}...) para s3://${BACKUP_BUCKET}..."
    enviar_s3 "${dump_file}" "${chave_datada}" "${sha256}"
    enviar_s3 "${dump_file}" "${chave_latest}" "${sha256}"

    rm -f "${dump_file}"
    log "Backup de ${db} concluído com sucesso."
done

log "Todos os backups diários foram enviados com sucesso."
EOBACKUP
    chmod 0750 /usr/local/sbin/keyflow-backup.sh
    chown root:root /usr/local/sbin/keyflow-backup.sh

    cat > /usr/local/sbin/keyflow-atualizar.sh <<'EOATUALIZAR'
#!/usr/bin/env bash
# Agente de deploy em modelo pull (sem SSH vindo do GitHub Actions).
# Faz UMA consulta paginada às GitHub Releases por execução (com cache ETag / If-None-Match para que 304 não
# consuma cota anônima), ignora tags fora do formato `^api-(staging|prod)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$`,
# exige avanço monotônico do timestamp sobre a current_tag instalada, valida a assinatura Ed25519 do manifesto
# `canal|tag|sha256(binário)` contra a chave pública exclusiva do canal, confere o SHA-256 do binário,
# reinicia o serviço exigindo código 0 e mudança real de MainPID/ExecMainStartTimestamp antes de validar /health,
# e executa rollback automático em caso de falha.

set -euo pipefail
set +x
umask 022

CONFIG_DEPLOY_FILE="${CONFIG_DEPLOY_FILE:-/etc/keyflow/deploy.conf}"
if [[ -f "${CONFIG_DEPLOY_FILE}" ]]; then
    # shellcheck source=/dev/null
    source "${CONFIG_DEPLOY_FILE}"
fi

GITHUB_REPO="${GITHUB_REPO:-Arateki/Keyflow}"
GITHUB_API_BASE="${GITHUB_API_BASE:-https://api.github.com/repos/${GITHUB_REPO}/releases}"
MAX_RELEASE_PAGES="${MAX_RELEASE_PAGES:-10}"
PUBKEY_STAGING_FILE="${PUBKEY_STAGING_FILE:-/etc/keyflow/release-signing-staging-pub.pem}"
PUBKEY_PROD_FILE="${PUBKEY_PROD_FILE:-/etc/keyflow/release-signing-prod-pub.pem}"
BASE_DIR="${BASE_DIR:-/opt/keyflow}"
STATE_GLOBAL_DIR="${STATE_GLOBAL_DIR:-${BASE_DIR}/state}"
SYSTEMCTL_BIN="${SYSTEMCTL_BIN:-systemctl}"
HEALTH_RETRIES="${HEALTH_RETRIES:-15}"
HEALTH_SLEEP_SECS="${HEALTH_SLEEP_SECS:-1}"

RELEASES_LIST_JSON=""

log() {
    printf '[keyflow-atualizar] %s\n' "$*"
}

aviso() {
    printf '[keyflow-atualizar] AVISO: %s\n' "$*" >&2
}

erro() {
    printf '[keyflow-atualizar] ERRO: %s\n' "$*" >&2
}

verificar_chave_publica() {
    local pubkey_file="$1"
    local canal="$2"

    if [[ ! -s "${pubkey_file}" ]]; then
        erro "Canal ${canal}: chave pública ${pubkey_file} ausente ou vazia."
        return 1
    fi
    if grep -q "SUBSTITUIR_PELA_CHAVE_PUBLICA" "${pubkey_file}" 2>/dev/null; then
        erro "Canal ${canal}: chave pública ${pubkey_file} ainda contém o placeholder; instale a chave Ed25519 real."
        return 1
    fi
    if ! openssl pkey -pubin -in "${pubkey_file}" -noout >/dev/null 2>&1; then
        erro "Canal ${canal}: chave pública ${pubkey_file} inválida para o OpenSSL."
        return 1
    fi
    return 0
}

# Extrai o timestamp UTC ISO-8601 compacto (YYYYMMDDTHHMMSSZ, 16 chars ordenáveis lexicograficamente)
# da tag no formato estrito `^api-(staging|prod)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$`.
extrair_timestamp_tag() {
    local canal="$1"
    local tag="$2"
    if [[ "${tag}" =~ ^api-${canal}-([0-9]{8}T[0-9]{6}Z)-[0-9a-f]+$ ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
        return 0
    fi
    return 1
}

# Faz UMA listagem de releases por execução (compartilhada entre staging e prod), usando ETag/If-None-Match
# na primeira página para que respostas 304 Not Modified não gastem cota da API pública do GitHub.
# Qualquer status HTTP diferente de 200/304 (ex.: 403 rate limit, 500) registra erro e retorna 1.
carregar_releases_github() {
    mkdir -p "${STATE_GLOBAL_DIR}"
    local etag_file="${STATE_GLOBAL_DIR}/releases.etag"
    local cache_file="${STATE_GLOBAL_DIR}/releases-cache.json"

    local tmp_http
    tmp_http="$(mktemp -d "${STATE_GLOBAL_DIR}/.http.XXXXXX")"
    trap 'rm -rf "${tmp_http:-}"' RETURN

    local pagina=1
    local acumulado="[]"
    local novo_etag=""

    while (( pagina <= MAX_RELEASE_PAGES )); do
        local url_pag
        if [[ -n "${GITHUB_API_URL:-}" ]]; then
            url_pag="${GITHUB_API_URL}"
        else
            url_pag="${GITHUB_API_BASE}?per_page=100&page=${pagina}"
        fi

        local hdr_file="${tmp_http}/headers-${pagina}.txt"
        local body_file="${tmp_http}/body-${pagina}.json"
        local -a curl_args=(
            --silent --show-error --location --max-time 30
            -D "${hdr_file}"
            -o "${body_file}"
            -w '%{http_code}'
            -H "Accept: application/vnd.github+json"
            -H "X-GitHub-Api-Version: 2022-11-28"
        )

        if (( pagina == 1 )) && [[ -s "${etag_file}" && -s "${cache_file}" ]]; then
            local etag_anterior
            etag_anterior="$(<"${etag_file}")"
            curl_args+=(-H "If-None-Match: ${etag_anterior}")
        fi

        local http_code=""
        if ! http_code="$(curl "${curl_args[@]}" "${url_pag}")"; then
            erro "Falha de rede ao consultar GitHub Releases em ${url_pag}."
            return 1
        fi

        if [[ "${url_pag}" == file://* && "${http_code}" == "000" ]]; then
            http_code="200"
        fi

        if (( pagina == 1 )) && [[ "${http_code}" == "304" ]]; then
            log "GitHub Releases retornou 304 Not Modified (ETag válido; zero cota consumida)."
            RELEASES_LIST_JSON="$(<"${cache_file}")"
            return 0
        fi

        if [[ "${http_code}" != "200" ]]; then
            erro "GitHub Releases retornou HTTP ${http_code} em ${url_pag}! Abortando sem alterar estado."
            return 1
        fi

        if ! jq -e 'type == "array"' "${body_file}" >/dev/null 2>&1; then
            erro "Resposta de ${url_pag} não é um array JSON válido."
            return 1
        fi

        if (( pagina == 1 )); then
            novo_etag="$(awk 'tolower($1) == "etag:" { sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }' "${hdr_file}")"
        fi

        local qtd
        qtd="$(jq 'length' "${body_file}")"
        acumulado="$(jq -c -s '.[0] + .[1]' <(printf '%s' "${acumulado}") "${body_file}")"

        if [[ -n "${GITHUB_API_URL:-}" ]] || (( qtd < 100 )); then
            break
        fi

        # Se a página 1 já contém ao menos uma release válida de cada canal, não precisa buscar mais páginas.
        local tem_stg tem_prod
        tem_stg="$(jq '[ .[] | select(.draft == false and (.tag_name | test("^api-staging-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$"))) ] | length' <<< "${acumulado}")"
        tem_prod="$(jq '[ .[] | select(.draft == false and (.tag_name | test("^api-prod-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$"))) ] | length' <<< "${acumulado}")"
        if (( tem_stg > 0 && tem_prod > 0 )); then
            break
        fi

        (( pagina++ ))
    done

    printf '%s\n' "${acumulado}" > "${cache_file}"
    if [[ -n "${novo_etag}" ]]; then
        printf '%s\n' "${novo_etag}" > "${etag_file}"
    fi
    RELEASES_LIST_JSON="${acumulado}"
    return 0
}

# Filtra apenas releases não-draft cuja tag casa estritamente com `^api-(staging|prod)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$`,
# ignorando tags fora do formato (como `api-prod-z`), e retorna a mais recente.
selecionar_release_mais_nova_do_canal() {
    local canal="$1"
    jq -c --arg canal "${canal}" '
        [ .[] | select(
            .draft == false and
            ((.tag_name // "") | test("^api-" + $canal + "-[0-9]{8}T[0-9]{6}Z-[0-9a-f]+$"))
        ) ]
        | sort_by(.tag_name) | last // empty
    ' <<< "${RELEASES_LIST_JSON}"
}

reiniciar_e_confirmar_novo_processo() {
    local servico="$1"
    local pid_antes ts_antes pid_depois ts_depois

    pid_antes="$("${SYSTEMCTL_BIN}" show -p MainPID --value "${servico}" 2>/dev/null || true)"
    ts_antes="$("${SYSTEMCTL_BIN}" show -p ExecMainStartTimestampMonotonic --value "${servico}" 2>/dev/null || true)"

    if ! "${SYSTEMCTL_BIN}" restart "${servico}"; then
        erro "Comando '${SYSTEMCTL_BIN} restart ${servico}' falhou com código não-zero!"
        return 1
    fi

    pid_depois="$("${SYSTEMCTL_BIN}" show -p MainPID --value "${servico}" 2>/dev/null || true)"
    ts_depois="$("${SYSTEMCTL_BIN}" show -p ExecMainStartTimestampMonotonic --value "${servico}" 2>/dev/null || true)"

    if [[ -z "${pid_depois}" || "${pid_depois}" == "0" ]]; then
        erro "Serviço ${servico} não possui MainPID ativo após restart (MainPID='${pid_depois:-vazio}')."
        return 1
    fi

    if [[ -n "${pid_antes}" && "${pid_antes}" != "0" ]]; then
        if [[ "${pid_depois}" == "${pid_antes}" && "${ts_depois}" == "${ts_antes}" ]]; then
            erro "Serviço ${servico} manteve o mesmo MainPID (${pid_depois}) e ExecMainStartTimestampMonotonic (${ts_depois:-0}) após restart!"
            return 1
        fi
    fi

    return 0
}

aguardar_health() {
    local porta="$1"
    local tentativa=1
    while (( tentativa <= HEALTH_RETRIES )); do
        if curl --fail --silent --max-time 2 "http://127.0.0.1:${porta}/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep "${HEALTH_SLEEP_SECS}"
        (( tentativa++ ))
    done
    return 1
}

atualizar_canal() {
    local canal="$1"         # staging | prod
    local porta="$2"         # 8081 | 8080
    local pubkey_file="$3"
    local servico="keyflow-api-${canal}.service"
    local dir_canal="${BASE_DIR}/${canal}"
    local dir_bin="${dir_canal}/bin"
    local dir_state="${dir_canal}/state"
    local bin_atual="${dir_bin}/keyflow-api"
    local bin_prev="${dir_bin}/keyflow-api.prev"
    local arq_tag_atual="${dir_state}/current_tag"
    local arq_tag_falha="${dir_state}/failed_tag"

    mkdir -p "${dir_bin}" "${dir_state}"

    local release_json
    release_json="$(selecionar_release_mais_nova_do_canal "${canal}")"
    if [[ -z "${release_json}" ]]; then
        return 0
    fi

    local tag_remota
    tag_remota="$(jq -r '.tag_name' <<< "${release_json}")"

    local ts_remoto
    if ! ts_remoto="$(extrair_timestamp_tag "${canal}" "${tag_remota}")"; then
        erro "Canal ${canal}: tag remota '${tag_remota}' fora do formato esperado! Recusando."
        return 1
    fi

    local tag_atual=""
    [[ -f "${arq_tag_atual}" ]] && tag_atual="$(<"${arq_tag_atual}")"
    local tag_falha=""
    [[ -f "${arq_tag_falha}" ]] && tag_falha="$(<"${arq_tag_falha}")"

    if [[ -n "${tag_atual}" ]]; then
        if [[ "${tag_remota}" == "${tag_atual}" ]]; then
            return 0
        fi
        local ts_atual
        if ! ts_atual="$(extrair_timestamp_tag "${canal}" "${tag_atual}")"; then
            erro "Canal ${canal}: current_tag instalada '${tag_atual}' inválida."
            return 1
        fi
        if [[ ! "${ts_remoto}" > "${ts_atual}" ]]; then
            erro "Canal ${canal}: tag remota '${tag_remota}' (ts=${ts_remoto}) não avança sobre a instalada '${tag_atual}' (ts=${ts_atual})! Recusando downgrade/replay."
            return 1
        fi
    fi

    if [[ -n "${tag_falha}" && "${tag_remota}" == "${tag_falha}" ]]; then
        aviso "Canal ${canal}: release ${tag_remota} já falhou anteriormente e sofreu rollback; aguardando release mais nova."
        return 0
    fi

    local url_bin url_manifest url_sig
    url_bin="$(jq -r '.assets[] | select(.name == "keyflow-api") | .browser_download_url' <<< "${release_json}")"
    url_manifest="$(jq -r '.assets[] | select(.name == "keyflow-api.manifest") | .browser_download_url' <<< "${release_json}")"
    url_sig="$(jq -r '.assets[] | select(.name == "keyflow-api.manifest.sig") | .browser_download_url' <<< "${release_json}")"

    if [[ -z "${url_bin}" || -z "${url_manifest}" || -z "${url_sig}" ]]; then
        erro "Canal ${canal}: release ${tag_remota} incompleta (faltam assets keyflow-api, keyflow-api.manifest ou keyflow-api.manifest.sig)."
        return 1
    fi

    log "Canal ${canal}: nova release detectada (${tag_remota}, anterior=${tag_atual:-nenhuma}). Baixando..."
    local tmp_dir
    tmp_dir="$(mktemp -d "${dir_bin}/.download.XXXXXX")"
    trap 'rm -rf "${tmp_dir:-}"' RETURN

    curl --fail --silent --show-error --location --max-time 120 -o "${tmp_dir}/keyflow-api" "${url_bin}"
    curl --fail --silent --show-error --location --max-time 30  -o "${tmp_dir}/keyflow-api.manifest" "${url_manifest}"
    curl --fail --silent --show-error --location --max-time 30  -o "${tmp_dir}/keyflow-api.manifest.sig" "${url_sig}"

    # 1. Verifica a assinatura Ed25519 do manifesto contra a chave pública exclusiva deste canal.
    if ! openssl pkeyutl -verify -pubin -inkey "${pubkey_file}" \
        -rawin -in "${tmp_dir}/keyflow-api.manifest" -sigfile "${tmp_dir}/keyflow-api.manifest.sig" >/dev/null 2>&1; then
        erro "Canal ${canal}: ASSINATURA ED25519 INVÁLIDA no manifesto da release ${tag_remota}! Abortando."
        return 1
    fi

    # 2. Valida o conteúdo do manifesto assinado: exatamente 1 linha no formato `canal|tag|sha256`.
    local num_linhas
    num_linhas="$(wc -l < "${tmp_dir}/keyflow-api.manifest" | tr -d ' ')"
    if [[ "${num_linhas}" != "1" ]]; then
        erro "Canal ${canal}: manifesto inválido (esperado 1 linha, encontrado ${num_linhas})."
        return 1
    fi

    local linha_manifesto mf_canal mf_tag mf_sha256 extra
    linha_manifesto="$(<"${tmp_dir}/keyflow-api.manifest")"
    IFS='|' read -r mf_canal mf_tag mf_sha256 extra <<< "${linha_manifesto}"

    if [[ -n "${extra:-}" || -z "${mf_canal:-}" || -z "${mf_tag:-}" || -z "${mf_sha256:-}" ]]; then
        erro "Canal ${canal}: manifesto malformado na release ${tag_remota}."
        return 1
    fi
    if [[ "${mf_canal}" != "${canal}" ]]; then
        erro "Canal ${canal}: manifesto assinado é do canal '${mf_canal}', não '${canal}'! Recusando tentativa de cruzamento de canal."
        return 1
    fi
    if [[ "${mf_tag}" != "${tag_remota}" ]]; then
        erro "Canal ${canal}: tag do manifesto assinado ('${mf_tag}') diverge da tag da release ('${tag_remota}')! Recusando."
        return 1
    fi
    if [[ ! "${mf_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
        erro "Canal ${canal}: SHA-256 inválido no manifesto ('${mf_sha256}')."
        return 1
    fi

    # 3. Confere o SHA-256 do binário baixado contra o SHA-256 autenticado no manifesto.
    local bin_sha256
    bin_sha256="$(sha256sum "${tmp_dir}/keyflow-api" | awk '{print $1}')"
    if [[ "${bin_sha256}" != "${mf_sha256}" ]]; then
        erro "Canal ${canal}: SHA-256 do binário (${bin_sha256}) diverge do manifesto assinado (${mf_sha256})! Abortando."
        return 1
    fi

    log "Canal ${canal}: manifesto (${mf_canal}|${mf_tag}) e SHA-256 verificados com sucesso."
    chmod 0755 "${tmp_dir}/keyflow-api"
    chown root:root "${tmp_dir}/keyflow-api"

    local tinha_anterior=0
    if [[ -x "${bin_atual}" && -n "${tag_atual}" ]]; then
        cp -pf "${bin_atual}" "${bin_prev}"
        tinha_anterior=1
    fi

    # Troca atômica no mesmo diretório (${dir_bin}).
    mv -f "${tmp_dir}/keyflow-api" "${bin_atual}"
    log "Canal ${canal}: reiniciando ${servico} e confirmando novo MainPID..."

    if reiniciar_e_confirmar_novo_processo "${servico}" && aguardar_health "${porta}"; then
        printf '%s\n' "${tag_remota}" > "${arq_tag_atual}"
        rm -f "${arq_tag_falha}"
        log "Canal ${canal}: deploy de ${tag_remota} concluído e saudável em 127.0.0.1:${porta}/health."
        return 0
    fi

    if (( tinha_anterior == 1 )) && [[ -x "${bin_prev}" ]]; then
        erro "Canal ${canal}: falha no restart/MainPID ou em /health na porta ${porta} após instalar ${tag_remota}! Fazendo rollback para ${tag_atual}..."
        printf '%s\n' "${tag_remota}" > "${arq_tag_falha}"
        mv -f "${bin_prev}" "${bin_atual}"
        if reiniciar_e_confirmar_novo_processo "${servico}" && aguardar_health "${porta}"; then
            log "Canal ${canal}: rollback para versão anterior (${tag_atual}) concluído e /health restabelecido."
        else
            erro "Canal ${canal}: /health ou restart continuou falhando mesmo após rollback!"
        fi
    else
        # Primeiro deploy: mantém o binário assinado no lugar e NÃO congela failed_tag nem current_tag,
        # para que assim que a configuração/banco ficar pronta a próxima execução valide /health.
        erro "Canal ${canal}: primeiro deploy (${tag_remota}) instalado em ${bin_atual}, mas restart/MainPID ou /health na porta ${porta} ainda não respondeu 200 (será retestado na próxima rodada)."
    fi
    return 1
}

CANAL_FILTRO="${1:-todos}"
status_geral=0

if [[ "${CANAL_FILTRO}" == "todos" || "${CANAL_FILTRO}" == "staging" ]]; then
    verificar_chave_publica "${PUBKEY_STAGING_FILE}" "staging" || status_geral=1
fi
if [[ "${CANAL_FILTRO}" == "todos" || "${CANAL_FILTRO}" == "prod" ]]; then
    verificar_chave_publica "${PUBKEY_PROD_FILE}" "prod" || status_geral=1
fi
if (( status_geral != 0 )); then
    exit 1
fi

carregar_releases_github || exit 1

if [[ "${CANAL_FILTRO}" == "todos" || "${CANAL_FILTRO}" == "staging" ]]; then
    atualizar_canal "staging" "8081" "${PUBKEY_STAGING_FILE}" || status_geral=1
fi

if [[ "${CANAL_FILTRO}" == "todos" || "${CANAL_FILTRO}" == "prod" ]]; then
    atualizar_canal "prod" "8080" "${PUBKEY_PROD_FILE}" || status_geral=1
fi

exit "${status_geral}"
EOATUALIZAR
    chmod 0750 /usr/local/sbin/keyflow-atualizar.sh
    chown root:root /usr/local/sbin/keyflow-atualizar.sh
}

# ==============================================================================
# 7. Unidades systemd (API prod, API staging, backup diário, pull deploy)
# ==============================================================================
configurar_systemd() {
    log "Gravando unidades systemd em /etc/systemd/system/..."
    mkdir -p /etc/systemd/system

    cat > /etc/systemd/system/keyflow-api-prod.service <<'EOF'
[Unit]
Description=KeyFlow API — Produção (porta 8080)
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service
ConditionFileIsExecutable=/opt/keyflow/prod/bin/keyflow-api

[Service]
Type=simple
User=keyflow-prod
Group=keyflow-prod
EnvironmentFile=/etc/keyflow/prod.env
Environment=MALLOC_ARENA_MAX=2
ExecStart=/opt/keyflow/prod/bin/keyflow-api
Restart=on-failure
RestartSec=3s

# Limites de recursos em máquina de 447 MB de RAM (aws.md §2.1 / §3)
MemoryAccounting=true
MemoryHigh=110M
MemoryMax=140M
CPUAccounting=true
TasksMax=64

# Endurecimento (Hardening) e isolamento de /proc entre serviços
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectProc=invisible
ProcSubset=pid
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
CapabilityBoundingSet=
ReadOnlyPaths=/etc/keyflow/google-sa-prod.json

[Install]
WantedBy=multi-user.target
EOF

    cat > /etc/systemd/system/keyflow-api-staging.service <<'EOF'
[Unit]
Description=KeyFlow API — Staging (porta 8081, apenas loopback)
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service
ConditionFileIsExecutable=/opt/keyflow/staging/bin/keyflow-api

[Service]
Type=simple
User=keyflow-staging
Group=keyflow-staging
EnvironmentFile=/etc/keyflow/staging.env
Environment=MALLOC_ARENA_MAX=2
ExecStart=/opt/keyflow/staging/bin/keyflow-api
Restart=on-failure
RestartSec=5s
Nice=10

# Limites estritos para o staging não competir com produção (aws.md §3)
MemoryAccounting=true
MemoryHigh=48M
MemoryMax=64M
CPUAccounting=true
CPUQuota=25%
TasksMax=32

# Endurecimento (Hardening) e isolamento de /proc entre serviços
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectProc=invisible
ProcSubset=pid
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
CapabilityBoundingSet=
ReadOnlyPaths=/etc/keyflow/google-sa-staging.json

[Install]
WantedBy=multi-user.target
EOF

    cat > /etc/systemd/system/keyflow-backup.service <<'EOF'
[Unit]
Description=Backup diário dos bancos PostgreSQL da KeyFlow API para o S3 (curl --aws-sigv4)
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service
ConditionPathExists=/etc/keyflow/backup.env

[Service]
Type=oneshot
User=root
Group=root
ExecStart=/usr/local/sbin/keyflow-backup.sh
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
CapabilityBoundingSet=CAP_SETUID CAP_SETGID CAP_DAC_READ_SEARCH
ReadOnlyPaths=/etc/keyflow
EOF

    cat > /etc/systemd/system/keyflow-backup.timer <<'EOF'
[Unit]
Description=Timer diário de backup dos bancos da KeyFlow API para o S3

[Timer]
OnCalendar=*-*-* 03:15:00 UTC
RandomizedDelaySec=15m
Persistent=true

[Install]
WantedBy=timers.target
EOF

    cat > /etc/systemd/system/keyflow-atualizar.service <<'EOF'
[Unit]
Description=Verificador pull de novas releases assinadas da KeyFlow API no GitHub
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=oneshot
User=root
Group=root
ExecStart=/usr/local/sbin/keyflow-atualizar.sh
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
ReadOnlyPaths=/etc/keyflow
ReadWritePaths=/opt/keyflow/staging/bin /opt/keyflow/staging/state /opt/keyflow/prod/bin /opt/keyflow/prod/state /opt/keyflow/state
EOF

    cat > /etc/systemd/system/keyflow-atualizar.timer <<'EOF'
[Unit]
Description=Timer de atualização pull da KeyFlow API (staging e produção)

[Timer]
OnBootSec=1min
OnUnitActiveSec=5min
AccuracySec=15s
Persistent=true

[Install]
WantedBy=timers.target
EOF

    chmod 0644 /etc/systemd/system/keyflow-*.service /etc/systemd/system/keyflow-*.timer

    if tem_systemd; then
        systemctl daemon-reload
        systemctl enable keyflow-api-prod.service keyflow-api-staging.service
        systemctl enable --now keyflow-backup.timer keyflow-atualizar.timer
        if [[ -x /opt/keyflow/prod/bin/keyflow-api ]]; then
            systemctl start keyflow-api-prod.service || true
        fi
        if [[ -x /opt/keyflow/staging/bin/keyflow-api ]]; then
            systemctl start keyflow-api-staging.service || true
        fi
    else
        aviso "systemd não está rodando como PID 1 neste ambiente; unidades gravadas em /etc/systemd/system/."
    fi
}

# ==============================================================================
# Execução principal
# ==============================================================================
configurar_swap_e_sysctl
instalar_pacotes_e_atualizacoes
configurar_usuario_e_diretorios
configurar_postgres
provisionar_bancos_e_envs
configurar_nginx
instalar_scripts_operacionais
configurar_systemd

log "Provisionamento concluído com sucesso! Produção: ${KEYFLOW_DOMAIN}. Staging: ${KEYFLOW_STAGING_DOMAIN}."

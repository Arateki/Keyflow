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

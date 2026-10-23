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

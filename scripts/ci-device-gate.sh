#!/usr/bin/env bash
# Gate de device do CI (.github/workflows/checks.yml): roda com o emulador já de pé, dentro do
# android-emulator-runner. O workflow espalha o gate `full` + `screenshot` do TESTING.md por vários
# emuladores em paralelo; cada job roda `all`:
#
#   ci-device-gate.sh all <índice> <total>     o pedaço <índice> da suíte e depois o mesmo recorte da
#                                              galeria (ROUTE_SHARD), no mesmo emulador;
#   ci-device-gate.sh suite <índice> <total>   só o pedaço da suíte instrumentada (numShards/shardIndex do
#                                              AndroidJUnitRunner), com o ponta a ponta do modelo local;
#   ci-device-gate.sh screens <temas> <k/n>    só um recorte da galeria (THEMES + ROUTE_SHARD do
#                                              verify-screens.sh, ex.: "light dark" 0/6).
# O diff contra os goldens é um passo à parte do workflow, sem emulador.
#
# Sem Gradle aqui: o job do emulador sobe junto com o `Unit tests and build` e instala os APKs que ele
# publica (artefato `apks`). Compilar de novo no runner do emulador custava ~7 min e disputava os 2
# núcleos e os 7 GB do runner com o QEMU — na rodada de 2026-10-07 o emulador travou nesse aperto.
#
# Não para no primeiro erro: cada etapa roda e o exit code final acusa qualquer falha, para a
# evidência (logcat, saída bruta, prints) sair completa mesmo quando algo quebra.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

MODE="${1:-}"
EVIDENCE=app/build/ci-evidence
mkdir -p "$EVIDENCE"
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
# Mesmo lugar do build local: os caminhos abaixo valem com ou sem o artefato.
APK_DIR=app/build/outputs/apk
APP_APK="$APK_DIR/debug/app-debug.apk"
TEST_APK="$APK_DIR/androidTest/debug/app-debug-androidTest.apk"
RUNNER=com.keyflow.test/androidx.test.runner.AndroidJUnitRunner
# O que passou na 1ª tentativa, lido pelo processo do app na repetição (notTestFile).
PASSED_ON_DEVICE=/data/local/tmp/keyflow-passed.txt
# Nome do job que publica os APKs — tem que bater com o `name:` dele em checks.yml.
UNIT_JOB="Unit tests and build"

# Sem diálogo de "app não está respondendo" (a mesma lição do emulador do sandbox, ENVIRONMENT.md).
# Medido no CI (2026-09-25 e 2026-09-27): logo após o boot, o launcher/systemui estouram ANR sob carga e
# o diálogo fica minutos na tela segurando o foco — nenhum campo recebe o IME e o 1º teste de teclado
# falha. Tem que valer cedo, enquanto o sistema ainda assenta.
"$ADB" shell settings put global hide_error_dialogs 1 || true

"$ADB" logcat -c || true
"$ADB" logcat -v threadtime > "$EVIDENCE/logcat.txt" 2>&1 &
LOGCAT_PID=$!

# Memória e carga do HOST a cada 20 s: foi assim que apareceu o vazamento do emulador 37.2.12 (ver o
# emulator-build fixo em checks.yml). Uma linha por amostra:
# hora, load average, memória livre e swap usada (MB), RSS do QEMU (MB).
sample_host() {
  local qemu
  while :; do
    # O maior entre os candidatos: o crashpad do emulador também leva "qemu-system" na linha de comando.
    qemu=$(for p in $(pgrep -f qemu-system); do echo "$(ps -o rss= -p "$p") $p"; done | sort -n | tail -1 | awk '{print $2}')
    printf '%s load=%s mem_avail=%s swap_used=%s qemu_rss=%s\n' "$(date +%H:%M:%S)" \
      "$(cut -d' ' -f1-3 /proc/loadavg)" \
      "$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)" \
      "$(awk '/SwapTotal/ {t=$2} /SwapFree/ {f=$2} END {print int((t-f)/1024)}' /proc/meminfo)" \
      "$( [ -n "$qemu" ] && ps -o rss= -p "$qemu" | awk '{print int($1/1024)}')"
    sleep 20
  done
}
sample_host > "$EVIDENCE/host.txt" 2>&1 &
SAMPLER_PID=$!

status=0
step() {
  echo "::group::$1"
  shift
  local rc=0
  "$@" || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "::error::falhou: $*"
    status=1
  fi
  echo "::endgroup::"
  return "$rc"
}

# Espera o artefato `apks` desta rodada. Falha logo se o job de unitários terminou sem publicá-lo.
# Fora do Actions (sem GITHUB_RUN_ID) usa os APKs do build local.
fetch_apks() {
  local deadline=$((SECONDS + 20 * 60)) state="" misses=0
  if [ -z "${GITHUB_RUN_ID:-}" ]; then
    ls -la "$APP_APK" "$TEST_APK" || { echo "rode antes: ./gradlew assembleDebug assembleDebugAndroidTest" >&2; return 1; }
    return 0
  fi
  while :; do
    rm -rf "$APK_DIR"
    if gh run download "$GITHUB_RUN_ID" -R "$GITHUB_REPOSITORY" -n apks -D "$APK_DIR" \
        2> "$EVIDENCE/apk-download.err"; then
      ls -la "$APP_APK" "$TEST_APK"
      return 0
    fi
    state=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
      --jq ".jobs[] | select(.name == \"$UNIT_JOB\") | .conclusion // .status" 2>/dev/null)
    case "$state" in
      success)
        # Terminou bem e o artefato não aparece: a API demora um pouco, ou ele expirou (1 dia) e esta
        # é uma re-execução só dos jobs que falharam — aí é preciso rodar o workflow inteiro.
        misses=$((misses + 1))
        if [ "$misses" -ge 6 ]; then
          echo "::error::o '$UNIT_JOB' passou, mas o artefato apks não baixa (expirou? rode o workflow inteiro)"
          cat "$EVIDENCE/apk-download.err"
          return 1
        fi ;;
      failure|cancelled|timed_out|skipped)
        echo "::error::o job '$UNIT_JOB' terminou em '$state': não há APKs para testar"
        return 1 ;;
    esac
    if [ "$SECONDS" -gt "$deadline" ]; then
      echo "::error::os APKs não chegaram em 20 min (estado do '$UNIT_JOB': ${state:-desconhecido})"
      cat "$EVIDENCE/apk-download.err"
      return 1
    fi
    echo "Esperando os APKs do '$UNIT_JOB' (${state:-?})..."
    sleep 10
  done
}

# Depois do `adb install` o app fica `run-from-apk`: sem a verificação gravada, cada abertura a frio
# verifica as classes de novo. O `compile` grava (APK debuggable para em `verify`, sem AOT, e basta):
# medido no emulador local, a GalleryActivity abre em 1,95 s contra 2,7 s.
install_apks() {
  local apk pkg
  for apk in "$@"; do
    "$ADB" install -r -t "$apk" || return 1
  done
  for pkg in com.keyflow com.keyflow.test; do
    "$ADB" shell pm path "$pkg" >/dev/null 2>&1 || continue
    "$ADB" shell cmd package compile -m speed -f "$pkg" || return 1
  done
}

# localModelE2E: liga o ponta a ponta do modelo local (baixa o Whisper base, ~160 MB, e transcreve).
# motionFrames=false: os testes de artefato de movimento conferem os gestos sem gravar os quadros dos GIFs.
instrument() {
  "$ADB" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  "$ADB" shell am instrument -w -r "$@" -e localModelE2E true -e motionFrames false "$RUNNER"
}

# O hash do nome de cada teste decide o pedaço: o mesmo teste cai sempre no mesmo shard.
# O que não passa é repetido UMA vez: na rodada 38028614941 (2026-10-10) caíram 4 testes diferentes em 6 shards,
# todos instáveis no emulador do CI, e cada um derrubava a rodada inteira. Falha de verdade falha de novo e o
# gate fica vermelho; quem passa na repetição sai como aviso de "instável" no PR. A repetição exclui o que
# passou (notTestFile) em vez de listar o que falhou: se o processo caiu, os testes que nem rodaram vêm junto.
run_shard() {
  local label="Instrumentados, shard $1 de $2"
  local shard=(-e numShards "$2" -e shardIndex "$1")
  instrument "${shard[@]}" \
    | tee "$EVIDENCE/instrument-raw.txt" \
    | python3 -u scripts/ci-instrument-summary.py --label "$label" --retry-follows \
        --passed-out "$EVIDENCE/passed.txt" --failed-out "$EVIDENCE/failed.txt" \
    && return 0
  echo "Repetindo o que não passou: $(paste -sd' ' "$EVIDENCE/failed.txt")"
  "$ADB" push "$EVIDENCE/passed.txt" "$PASSED_ON_DEVICE" >/dev/null || return 1
  instrument "${shard[@]}" -e notTestFile "$PASSED_ON_DEVICE" \
    | tee "$EVIDENCE/instrument-retry-raw.txt" \
    | python3 -u scripts/ci-instrument-summary.py --label "$label, repetição" \
        --earlier-failures "$EVIDENCE/failed.txt"
}

# Galeria num app recém-instalado, como na última `main` verde (o connected* desinstalava o app no fim da suíte).
reinstall_app() {
  "$ADB" uninstall com.keyflow >/dev/null 2>&1 || true
  install_apks "$APP_APK"
}

capture_screens() {
  step "Captura das rotas da galeria (temas: $1; recorte $2)" \
    env THEMES="$1" ROUTE_SHARD="$2" scripts/verify-screens.sh verification
  mkdir -p "$EVIDENCE/screens"
  cp verification/*.png "$EVIDENCE/screens/" 2>/dev/null || true
}

case "$MODE" in
  all)
    [ $# -eq 3 ] || { echo "uso: $0 all <índice> <total>" >&2; exit 2; }
    if fetch_apks && install_apks "$APP_APK" "$TEST_APK"; then
      step "Suíte instrumentada (shard $2 de $3)" run_shard "$2" "$3"
      grep -a "TestRunner: run finished" "$EVIDENCE/logcat.txt" || echo "::warning::sem 'run finished' no logcat"
      # A galeria vem depois da suíte, no mesmo emulador: cada emulador custa ~6 min de boot e espera dos APKs, e
      # assim é um por pedaço, não dois. O app é reinstalado do zero, como na última `main` verde.
      if step "Reinstala o app para a galeria" reinstall_app; then
        capture_screens "light dark" "$2/$3"
      fi
    else
      status=1
    fi
    ;;
  suite)
    [ $# -eq 3 ] || { echo "uso: $0 suite <índice> <total>" >&2; exit 2; }
    if fetch_apks && install_apks "$APP_APK" "$TEST_APK"; then
      step "Suíte instrumentada (shard $2 de $3)" run_shard "$2" "$3"
      # Conferência pelo próprio runner (a lição C' da quick 010: o resumo do am instrument pode sumir).
      grep -a "TestRunner: run finished" "$EVIDENCE/logcat.txt" || echo "::warning::sem 'run finished' no logcat"
    else
      status=1
    fi
    ;;
  screens)
    [ $# -eq 3 ] || { echo "uso: $0 screens <temas> <k/n>" >&2; exit 2; }
    if fetch_apks && install_apks "$APP_APK"; then
      capture_screens "$2" "$3"
    else
      status=1
    fi
    ;;
  *)
    echo "uso: $0 all <índice> <total> | suite <índice> <total> | screens <temas> <k/n>" >&2
    status=2
    ;;
esac

kill "$LOGCAT_PID" "$SAMPLER_PID" 2>/dev/null || true
echo "Pior momento do host: $(sort -t= -k3 -n "$EVIDENCE/host.txt" | head -1)"
exit "$status"

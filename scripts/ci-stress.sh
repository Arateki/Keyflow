#!/usr/bin/env bash
# Esteira de instabilidade (.github/workflows/stress.yml): roda com o emulador já de pé, dentro do
# android-emulator-runner, e repete as mesmas classes em laço até acabar o tempo — para pegar, com
# evidência, o teste que falha uma vez a cada tantas rodadas do gate. Vários emuladores rodam isto ao
# mesmo tempo; scripts/ci-stress-report.py soma as voltas de todos.
#
#   ci-stress.sh <classes> <minutos> [<CPU % do emulador>]
#
# <classes>   lista do `-e class` do AndroidJUnitRunner (Classe ou Classe#metodo, separados por vírgula).
# <CPU %>     teto de CPU só do QEMU, num cgroup próprio: 200 = 2 núcleos, perto do runner privado onde as
#             falhas apareciam mais. Vazio = o runner como ele é.
#
# Cada volta reinstala o app, como o gate faz antes da suíte: as falhas que sobraram aparecem logo depois
# de instalar (o holdingAKey… da CanvasGridTest é sempre o 1º teste de teclado do processo). Toda volta
# grava passed.txt/failed.txt; a que falha guarda também a saída bruta, o trecho do logcat e a tela.
#
# Roda o tempo todo, mesmo depois de uma falha: uma falha frequente não pode tirar o emulador da caça às
# raras. Como o GitHub só libera log e artefato de job terminado, cada volta com falha publica na hora um
# status no commit (`esteira/<emulador>/volta-N`: o teste e a 1ª linha do erro) — é o aviso antecipado;
# a evidência completa sobe no fim. Sai com 1 se alguma volta falhou.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

CLASSES="${1:?uso: $0 <classes> <minutos> [<CPU %>]}"
MINUTES="${2:?uso: $0 <classes> <minutos> [<CPU %>]}"
CPU="${3:-}"
OUT=app/build/stress-evidence
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=com.keyflow.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUT"

# Sem diálogo de "app não está respondendo" segurando o foco (a mesma lição do ci-device-gate.sh).
"$ADB" shell settings put global hide_error_dialogs 1 || true

qemu_pid() {
  # O maior entre os candidatos: o crashpad do emulador também leva "qemu-system" na linha de comando.
  for p in $(pgrep -f qemu-system); do echo "$(ps -o rss= -p "$p") $p"; done | sort -n | tail -1 | awk '{print $2}'
}

# Só o QEMU fica no teto; o adb, o logcat e o runner seguem livres. É a receita do emulador lento local
# (systemd-run --scope -p CPUQuota=…), que reproduziu a falha do seletor que só dava no CI (2026-10-10).
limit_cpu() {
  local cg=/sys/fs/cgroup/keyflow-emulator pid
  pid=$(qemu_pid)
  [ -n "$pid" ] || { echo "::error::QEMU não encontrado para limitar a CPU"; return 1; }
  grep -qw cpu /sys/fs/cgroup/cgroup.subtree_control ||
    echo +cpu | sudo tee /sys/fs/cgroup/cgroup.subtree_control >/dev/null || return 1
  sudo mkdir -p "$cg" || return 1
  echo "$((CPU * 1000)) 100000" | sudo tee "$cg/cpu.max" >/dev/null || return 1
  echo "$pid" | sudo tee "$cg/cgroup.procs" >/dev/null || return 1
  echo "QEMU $pid: cgroup $(cut -d: -f3 "/proc/$pid/cgroup"), cpu.max $(cat "$cg/cpu.max")"
}

# Depois do `adb install` o app fica `run-from-apk`; o compile grava a verificação (ci-device-gate.sh).
install_app() {
  "$ADB" install -r -t "$APP_APK" >/dev/null || return 1
  "$ADB" shell cmd package compile -m speed -f com.keyflow >/dev/null
}

# O aviso antecipado: um status no commit, que a API mostra com o job ainda rodando. Fora do Actions
# (sem GITHUB_SHA) não faz nada.
announce_failure() {
  local dir="$1" round="$2" test error
  [ -n "${GITHUB_SHA:-}" ] && [ -n "${GH_TOKEN:-}" ] || return 0
  test=$(head -1 "$dir/failed.txt" | sed 's/^.*\.//')
  error=$(grep -A1 "^  ✗ " "$dir/summary.txt" | sed -n 2p | sed 's/^ *//')
  gh api -X POST "repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" -f state=failure \
    -f context="esteira/${STRESS_WORKER:-?}/volta-$round" \
    -f description="$(printf '%s: %s' "${test:-processo caiu}" "$error" | cut -c1-140)" \
    -f target_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" >/dev/null || true
}

# Mediana e p90 do tempo de quadro do processo de teste: é o número que compara este emulador com o
# do gate e com o local (a calibração da memória do projeto).
frame_stats() {
  python3 - "$OUT/logcat-full.txt" <<'EOF'
import re, statistics, sys
lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
pids = {l.split()[2] for l in lines if " TestRunner: " in l and len(l.split()) > 2}
v = sorted(float(m.group(1)) for l in lines
           if len(l.split()) > 5 and l.split()[2] in pids
           for m in [re.search(r"app_time_stats: avg=([0-9.]+)ms", l)] if m)
print(f"quadros do app: {len(v)} amostras, mediana {statistics.median(v):.0f} ms, p90 {v[int(len(v) * .9)]:.0f} ms"
      if v else "quadros do app: sem amostras")
EOF
}

"$ADB" logcat -c || true
"$ADB" logcat -v threadtime > "$OUT/logcat-full.txt" 2>&1 &
LOGCAT_PID=$!

if ! "$ADB" install -r -t "$TEST_APK" >/dev/null; then
  echo "::error::o APK de teste não instalou"
  exit 1
fi
"$ADB" shell cmd package compile -m speed -f com.keyflow.test >/dev/null
if [ -n "$CPU" ] && ! limit_cpu; then
  echo "::error::não consegui limitar a CPU do emulador a $CPU%"
  exit 1
fi

deadline=$((SECONDS + MINUTES * 60))
i=0
failed_rounds=0
while [ "$SECONDS" -lt "$deadline" ]; do
  i=$((i + 1))
  dir="$OUT/$(printf 'volta-%03d' "$i")"
  mkdir -p "$dir"
  from=$(wc -l < "$OUT/logcat-full.txt")
  start=$SECONDS
  if ! install_app; then
    echo "::error::volta $i: o app não instalou"
    break
  fi
  "$ADB" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  "$ADB" shell am instrument -w -r -e class "$CLASSES" -e motionFrames false "$RUNNER" > "$dir/raw.txt"
  # --retry-follows: sem anotação por volta (seriam centenas); o relatório anota no fim.
  env -u GITHUB_STEP_SUMMARY python3 scripts/ci-instrument-summary.py --label "volta $i" --retry-follows \
    --passed-out "$dir/passed.txt" --failed-out "$dir/failed.txt" < "$dir/raw.txt" > "$dir/summary.txt"
  rc=$?
  score=$(grep "^volta $i: " "$dir/summary.txt" | cut -d: -f2-)
  if [ "$rc" -eq 0 ]; then
    rm -f "$dir/raw.txt"
    echo "volta $i ($((SECONDS - start)) s):$score"
  else
    failed_rounds=$((failed_rounds + 1))
    sed -n "$((from + 1)),\$p" "$OUT/logcat-full.txt" > "$dir/logcat.txt"
    "$ADB" exec-out screencap -p > "$dir/tela.png" 2>/dev/null || rm -f "$dir/tela.png"
    echo "volta $i ($((SECONDS - start)) s):$score — FALHOU: $(paste -sd' ' "$dir/failed.txt")"
    announce_failure "$dir" "$i"
  fi
done

kill "$LOGCAT_PID" 2>/dev/null || true
{
  echo "voltas: $i (com falha: $failed_rounds)"
  if [ -n "$CPU" ]; then echo "cpu do emulador: $CPU%"; else echo "cpu do emulador: livre"; fi
  frame_stats
} | tee "$OUT/meta.txt"
# O logcat inteiro tem centenas de milhares de linhas; as voltas com falha já guardaram o seu trecho.
rm -f "$OUT/logcat-full.txt"
[ "$i" -gt 0 ] && [ -s "$OUT/volta-001/passed.txt" -o -s "$OUT/volta-001/failed.txt" ] || exit 1
[ "$failed_rounds" -eq 0 ]

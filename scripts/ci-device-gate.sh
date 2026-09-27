#!/usr/bin/env bash
# Gate de device do CI (.github/workflows/checks.yml): roda com o emulador já de pé, dentro do
# android-emulator-runner. Mesmo gate `full` + `screenshot` do TESTING.md, na ordem em que um
# contribuidor roda localmente:
#
#   1. suíte instrumentada inteira (connectedDebugAndroidTest), com o ponta a ponta do modelo local;
#   2. APK debug reinstalado — o connected* desinstala o app no fim, e a galeria precisa dele;
#   3. verify-screens.sh + diff-screens.sh contra verification/golden/.
#
# Não para no primeiro erro: cada etapa roda e o exit code final acusa qualquer falha, para a
# evidência (logcat, relatórios, prints) sair completa mesmo quando algo quebra.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

EVIDENCE=app/build/ci-evidence
mkdir -p "$EVIDENCE"
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"

# Sem diálogo de "app não está respondendo" (a mesma lição do emulador do sandbox, ENVIRONMENT.md).
# Medido no CI (2026-09-25 e 2026-09-27): logo após o boot, o launcher/systemui estouram ANR sob a carga
# do build e o diálogo fica minutos na tela segurando o foco — nenhum campo recebe o IME e o 1º teste de
# teclado falha. Tem que valer ANTES do build, quando os ANRs acontecem.
"$ADB" shell settings put global hide_error_dialogs 1 || true

"$ADB" logcat -c || true
"$ADB" logcat -v threadtime > "$EVIDENCE/logcat.txt" 2>&1 &
LOGCAT_PID=$!

status=0
step() {
  echo "::group::$1"
  shift
  if ! "$@"; then
    echo "::error::falhou: $*"
    status=1
  fi
  echo "::endgroup::"
}

# localModelE2E: liga o ponta a ponta do modelo local (baixa o Whisper base, ~160 MB, e transcreve).
# Se um diálogo de sistema abriu antes do ajuste acima, fecha antes da suíte.
"$ADB" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
step "Suíte instrumentada" ./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.localModelE2E=true
step "Reinstala o APK debug" ./gradlew --no-daemon installDebug
step "Captura das rotas da galeria" scripts/verify-screens.sh verification
step "Diff contra os goldens" scripts/diff-screens.sh

kill "$LOGCAT_PID" 2>/dev/null || true
mkdir -p "$EVIDENCE/screens"
cp verification/*.png "$EVIDENCE/screens/" 2>/dev/null || true
exit "$status"

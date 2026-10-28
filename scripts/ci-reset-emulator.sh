#!/usr/bin/env bash
# Só no runner efêmero do CI e antes de iniciar testes; limpa o download incompleto e o boot que falhou.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && -n ${ANDROID_HOME:-} ]] || {
  echo 'ERRO: limpeza exclusiva do runner do GitHub Actions.' >&2; exit 2;
}
if [ -e app/build/ci-evidence/gate-started ]; then
  echo 'ERRO: gate já iniciado; falha de teste não permite repetir o emulador.' >&2
  exit 2
fi
echo '::warning::Preparo do emulador falhou antes dos testes; repetindo uma vez com download/AVD limpos.'
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 emu kill >/dev/null 2>&1 || true
# Os mesmos processos que o gate encerra; nenhum teste começou nesta tentativa.
pkill -9 -f '/emulator/(qemu/|netsimd|crashpad_handler)' || true
rm -rf "$ANDROID_HOME/.temp"
# O action recria o AVD por padrão; não reaproveita o userdata de um boot incompleto.

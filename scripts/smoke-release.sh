#!/usr/bin/env bash
set -euo pipefail

if [[ "${KEYFLOW_SMOKE_LOCKED:-}" != 1 ]]; then
    exec flock /tmp/keyflow-emulator.lock env KEYFLOW_SMOKE_LOCKED=1 "$0" "$@"
fi

serial="${ANDROID_SERIAL:-emulator-5554}"
sdk="${ANDROID_HOME:-/srv/dev/ferramentas/android-sdk}"
apk="app/build/outputs/apk/release/app-release.apk"
adb_bin="$(command -v adb)"
gradle_bin="${GRADLE_BIN:-/home/agent/.local/bin/gradle}"
gradle_user_home="${GRADLE_USER_HOME:-/mnt/immich/dev/keyflow/.gradle}"
log="${SMOKE_LOG:-/tmp/keyflow-release-smoke.log}"
[[ "$serial" == emulator-* ]] || { echo "recusando instalar fora do emulador: $serial" >&2; exit 2; }
[[ -x "$gradle_bin" ]] || { echo "Gradle ausente: $gradle_bin" >&2; exit 2; }
"$adb_bin" -s "$serial" get-state >/dev/null || { echo "inicie o emulador antes do smoke" >&2; exit 2; }
flock /tmp/keyflow-gradle.lock env GRADLE_USER_HOME="$gradle_user_home" "$gradle_bin" --no-daemon \
    -PkeyflowSmokeSigning=true :app:assembleRelease
[[ -f "$apk" ]] || { echo "APK release assinado pelo Gradle ausente: $apk" >&2; exit 2; }

# Varre todas as entradas descomprimidas (resources.arsc usa UTF-16, DEX usa UTF-8).
python3 scripts/check-release-admob.py "$apk"

# O release usa produção e não pode embutir identificador/token de Access de staging.
dex_dump="$(mktemp /tmp/keyflow-release-dex.XXXXXX)"
trap 'rm -f "$dex_dump"' EXIT
unzip -p "$apk" 'classes*.dex' > "$dex_dump"
if grep -aE 'keyflowStagingAccess|keyflow-api-staging\.arateki\.com' "$dex_dump"; then
    echo "release contém configuração/host de staging" >&2
    exit 1
fi
rm -f "$dex_dump"
trap - EXIT

"$adb_bin" -s "$serial" install -r "$apk"
"$adb_bin" -s "$serial" shell appops set com.keyflow SYSTEM_ALERT_WINDOW allow
"$adb_bin" -s "$serial" shell pm grant com.keyflow android.permission.RECORD_AUDIO
"$adb_bin" -s "$serial" shell pm grant com.keyflow android.permission.POST_NOTIFICATIONS || true
"$adb_bin" -s "$serial" logcat -c
"$adb_bin" -s "$serial" shell am force-stop com.keyflow
launch_result="$("$adb_bin" -s "$serial" shell am start -W -n com.keyflow/.ui.SetupActivity 2>&1)"
printf '%s\n' "$launch_result"
# am start pode retornar zero mesmo com "Error type 3"; a abertura precisa confirmar sucesso.
if ! grep -q '^Status: ok' <<< "$launch_result"; then
    echo "SetupActivity do release não abriu" >&2
    exit 1
fi
sleep 12

# Caixa-preta: confirme que a Activity do release está visível pela árvore de acessibilidade.
window_xml="$(mktemp /tmp/keyflow-release-window.XXXXXX)"
trap 'rm -f "$window_xml"' EXIT
# O UiAutomator também pode retornar zero sem gerar XML; nunca aceitar captura de rodada anterior.
"$adb_bin" -s "$serial" shell rm -f /sdcard/keyflow-release-window.xml
"$adb_bin" -s "$serial" shell uiautomator dump /sdcard/keyflow-release-window.xml >/dev/null
"$adb_bin" -s "$serial" pull /sdcard/keyflow-release-window.xml "$window_xml" >/dev/null
if ! rg -q 'package="com\.keyflow"' "$window_xml"; then
    echo "SetupActivity do release não aparece na árvore do UiAutomator" >&2
    exit 1
fi
rm -f "$window_xml"
trap - EXIT

"$adb_bin" -s "$serial" shell ime enable com.keyflow/.ime.KeyFlowKeyboardService
"$adb_bin" -s "$serial" shell ime set com.keyflow/.ime.KeyFlowKeyboardService
"$adb_bin" -s "$serial" shell input keyevent 3
"$adb_bin" -s "$serial" shell am start -W -a android.intent.action.VIEW -d 'https://example.invalid/'
sleep 3
"$adb_bin" -s "$serial" shell input text 'KeyFlow'
"$adb_bin" -s "$serial" shell input keyevent 3

# Log de todo o smoke é salvo para revisão; erros de R8/JNI/parcel devem interromper o script.
"$adb_bin" -s "$serial" logcat -d -v brief > "$log"
if rg -n 'FAILURES!!!|FATAL EXCEPTION|ClassNotFoundException|NoSuch(Method|Field)Error|JNI DETECTED ERROR|BadParcelableException|Parcel:.*(exception|error)' "$log"; then
    echo "erro fatal na saída do smoke; consulte $log" >&2
    exit 1
fi
"$adb_bin" -s "$serial" shell pidof com.keyflow >/dev/null
echo "Smoke básico release OK em $serial; log: $log"

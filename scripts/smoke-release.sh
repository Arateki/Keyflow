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

# O release usa produção e não pode embutir identificador/token de Access, simulador ou agente.
dex_dump="$(mktemp /tmp/keyflow-release-dex.XXXXXX)"
window_xml="$(mktemp /tmp/keyflow-release-window.XXXXXX)"
trap 'rm -f "$dex_dump" "$window_xml"' EXIT
unzip -p "$apk" 'classes*.dex' > "$dex_dump"
if grep -aE 'keyflowStagingAccess|keyflow-api-staging\.arateki\.com|KEYFLOW_SIMULATED_PURCHASE|SimulatedPurchase|simulated_purchase|/v1/test/purchases|kfsim1|com/keyflow/agenthost/AgentHost|com/keyflow/agent/AgentController|com/keyflow/ui/config/KeyflowPanel' "$dex_dump"; then
    echo "release contém caminho de staging, compra simulada ou chat do agente" >&2
    exit 1
fi
for symbol in 'androidx/work/impl/WorkDatabase_Impl' 'com/keyflow/transcription/local/ITranscriptionEngine$Stub' 'com/caverock/androidsvg/SVG'; do
    if ! grep -aFq "$symbol" "$dex_dump"; then
        echo "classe protegida por R8 ausente do release: $symbol" >&2
        exit 1
    fi
done
mapping="app/build/outputs/mapping/release/mapping.txt"
billing_class="$(awk '$1 == "com.keyflow.data.monetization.BillingManager" && $2 == "->" { sub(/:$/, "", $3); print $3; exit }' "$mapping")"
if [[ -z "$billing_class" ]]; then
    echo "BillingManager ausente do mapping R8 da release" >&2
    exit 1
fi
billing_descriptor="L${billing_class//./\\/};"
if ! grep -aFq "$billing_descriptor" "$dex_dump"; then
    echo "BillingManager mapeada para $billing_class não encontrada no DEX" >&2
    exit 1
fi
rm -f "$dex_dump"

"$adb_bin" -s "$serial" install -r "$apk"
"$adb_bin" -s "$serial" shell pm clear com.keyflow >/dev/null
"$adb_bin" -s "$serial" shell appops set com.keyflow SYSTEM_ALERT_WINDOW allow
"$adb_bin" -s "$serial" shell pm grant com.keyflow android.permission.RECORD_AUDIO
"$adb_bin" -s "$serial" shell pm grant com.keyflow android.permission.POST_NOTIFICATIONS || true
"$adb_bin" -s "$serial" shell ime enable com.keyflow/.ime.KeyFlowKeyboardService
"$adb_bin" -s "$serial" logcat -c
"$adb_bin" -s "$serial" shell am force-stop com.keyflow
"$adb_bin" -s "$serial" shell am start -W -n com.keyflow/.ui.SetupActivity
sleep 5

dump_window() {
    "$adb_bin" -s "$serial" shell uiautomator dump /sdcard/keyflow-release-window.xml >/dev/null 2>&1
    "$adb_bin" -s "$serial" pull /sdcard/keyflow-release-window.xml "$window_xml" >/dev/null 2>&1
}

find_bounds() {
    python3 - "$window_xml" "$@" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
needles = [value.casefold() for value in sys.argv[2:]]
for node in root.iter("node"):
    label = " ".join((node.attrib.get("text", ""), node.attrib.get("content-desc", ""))).strip().casefold()
    if any(label == needle for needle in needles):
        bounds = node.attrib.get("bounds", "")
        if bounds:
            print(bounds)
            break
PY
}

tap_label() {
    local bounds x1 y1 x2 y2 attempt
    for attempt in {1..8}; do
        dump_window
        bounds="$(find_bounds "$@")"
        if [[ -n "$bounds" ]]; then
            read -r x1 y1 x2 y2 < <(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")
            "$adb_bin" -s "$serial" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
            sleep 2
            return 0
        fi
        "$adb_bin" -s "$serial" shell input swipe 100 760 100 300 350
        sleep 1
    done
    echo "não encontrei controle acessível: $*" >&2
    cat "$window_xml" >&2
    return 1
}

assert_label() {
    local attempt
    for attempt in {1..8}; do
        dump_window
        if python3 - "$window_xml" "$@" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
needles = [value.casefold() for value in sys.argv[2:]]
raise SystemExit(0 if any(any(needle in " ".join((node.attrib.get("text", ""), node.attrib.get("content-desc", ""))).casefold() for needle in needles) for node in root.iter("node")) else 1)
PY
        then return 0; fi
        sleep 2
    done
    echo "texto acessível não apareceu: $*" >&2
    cat "$window_xml" >&2
    return 1
}

# Instalação limpa: aceita a divulgação, conclui onboarding e inicializa billing/WorkManager.
if assert_label 'Aceitar e continuar' 'Accept and continue' 'Aceptar y continuar'; then
    tap_label 'Aceitar e continuar' 'Accept and continue' 'Aceptar y continuar'
    tap_label 'Pular por agora' 'Skip for now' 'Omitir por ahora'
fi

# Passa pelas abas principais; a de KeyFlow deve ser apenas Em breve no release.
tap_label 'KeyFlow'
assert_label 'Em breve' 'Coming soon' 'Próximamente'
if rg -qi 'Olá! Sou o agente KeyFlow|Hello! I am the KeyFlow agent|chat-input|Permitir ferramenta|Allow tool' "$window_xml"; then
    echo "a árvore do release expõe o chat do agente" >&2
    exit 1
fi
tap_label 'Teclado' 'Keyboard'
tap_label 'Sobrepor' 'Overlay' 'Superposición'
tap_label 'Notificação' 'Notification' 'Notificación'
tap_label 'Fluxos' 'Flows' 'Flujos'
tap_label 'Transcrição' 'Transcription'
tap_label 'Visão' 'Vision' 'Visión'
tap_label 'Raciocínio' 'Reasoning' 'Razonamiento'
tap_label 'Voz' 'Voice'
tap_label 'Computador' 'Computer' 'Computadora'

# A tela de teclado compõe a prévia de skins; navegar até o fim também força o desenho de SVGs.
tap_label 'Teclado' 'Keyboard'
for _ in {1..4}; do "$adb_bin" -s "$serial" shell input swipe 600 1900 600 600 500; sleep 1; done
assert_label 'Suas skins' 'Your skins' 'Tus skins'

# Docs e Teclado são os hosts de banner. A etiqueta semântica confirma slot montado quando a
# monetização terminou de carregar; o slot é ausente enquanto o entitlement ainda não chegou.
tap_label 'Docs'
sleep 15
assert_label 'Banner de anúncios' 'Ad banner' 'Banner de anuncios'

"$adb_bin" -s "$serial" shell ime set com.keyflow/.ime.KeyFlowKeyboardService
"$adb_bin" -s "$serial" shell input keyevent 3
"$adb_bin" -s "$serial" shell am start -W -a android.intent.action.VIEW -d 'https://example.invalid/'
sleep 3
"$adb_bin" -s "$serial" shell input keyevent 3

# Log de todo o smoke é salvo para revisão; erros de R8/JNI/parcel devem interromper o script.
"$adb_bin" -s "$serial" logcat -d -v brief > "$log"
if rg -n 'FAILURES!!!|FATAL EXCEPTION|ClassNotFoundException|NoClassDefFoundError|Could not find class|NoSuch(Method|Field)Error|JNI DETECTED ERROR|BadParcelableException|Parcel:.*(exception|error)' "$log"; then
    echo "erro fatal na saída do smoke; consulte $log" >&2
    exit 1
fi
"$adb_bin" -s "$serial" shell pidof com.keyflow >/dev/null
echo "Smoke release (disclosure, abas, skins, banner e DEX/R8) OK em $serial; log: $log"

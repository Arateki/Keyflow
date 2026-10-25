#!/usr/bin/env bash
# Smoke do build de RELEASE (minificado pelo R8) no emulador — V1-3.
#
#   scripts/smoke-release.sh
#
# O que o R8 costuma quebrar sem avisar (reflexão, JNI, classes por nome) só aparece rodando o app. Este script:
#   1. gera o APK de release (com a chave de upload, se ela estiver configurada fora do repositório; senão assina com a de
#      debug só para o smoke) e confere, sem emulador, o que dá para ver no APK: nenhum ID de teste do AdMob, nenhum caminho
#      de staging/compra simulada/agente, classes protegidas e o que o JNI acessa por nome (check-release-jni.py);
#   2. instala no emulador, aceita a divulgação, percorre as abas, troca a skin do teclado, abre a folha de compra (Billing),
#      espera o banner do AdMob, habilita o KeyFlow como teclado e o abre num campo de texto de verdade;
#   3. varre o logcat atrás de ClassNotFoundException, NoSuchMethodError, erro de JNI e de parcel.
#
# Ambiente: KEYFLOW_SMOKE_APK=<apk> pula o Gradle (KEYFLOW_SMOKE_MAPPING=<mapping.txt> diz onde está o do R8); KEYFLOW_SMOKE_DICTATION=1 inclui o ditado local; ANDROID_SERIAL (padrão emulator-5554, nunca um aparelho real);
# SMOKE_LOG e SMOKE_EVIDENCE_DIR (padrão /tmp/keyflow-release) dizem onde ficam o logcat e as capturas.
# Pede o AdMob do release (KEYFLOW_ADMOB_*, ver .specs/features/monetization/admob-release.md) e segura as travas do
# Gradle (só na fase 1) e do emulador (só na fase 2).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
sdk="${ANDROID_HOME:-/srv/dev/ferramentas/android-sdk}"
export PATH="$sdk/platform-tools:/srv/dev/ferramentas/jdk-17/bin:/srv/dev/ferramentas/bin:$PATH"   # java (apksigner) e compare (ImageMagick)
build_tools="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)"
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
serial="${ANDROID_SERIAL:-emulator-5554}"
log="${SMOKE_LOG:-/tmp/keyflow-release-smoke.log}"
evidence="${SMOKE_EVIDENCE_DIR:-/tmp/keyflow-release}"
gradle_bin="${GRADLE_BIN:-$(command -v gradle || echo /srv/dev/ferramentas/bin/gradle)}"
gradle_user_home="${GRADLE_USER_HOME:-/srv/dev/keyflow/.gradle}"
mapping="${KEYFLOW_SMOKE_MAPPING:-app/build/outputs/mapping/release/mapping.txt}"
[[ "$serial" == emulator-5554 ]] || { echo "recusando instalar fora do emulador: $serial" >&2; exit 2; }
mkdir -p "$evidence"

# --- Fase 1: APK e conferências estáticas (sem emulador, sem a trava dele) -----------------------------------------------
if [[ "${KEYFLOW_SMOKE_PHASE:-1}" == 1 ]]; then
    [[ -x "$aapt2" ]] || { echo "aapt2 ausente em $build_tools" >&2; exit 2; }
    if [[ -n "${KEYFLOW_SMOKE_APK:-}" ]]; then
        apk="$KEYFLOW_SMOKE_APK"
    else
        [[ -x "$gradle_bin" ]] || { echo "Gradle ausente: $gradle_bin" >&2; exit 2; }
        gradle_release() {
            nice -n 15 flock /tmp/keyflow-gradle.lock env GRADLE_USER_HOME="$gradle_user_home" \
                "$gradle_bin" --no-daemon -p "$root" "$@"
        }
        gradle_release :app:assembleRelease
        apk="app/build/outputs/apk/release/app-release.apk"
        if [[ ! -f "$apk" ]]; then
            # Sem a chave de upload o release sai sem assinatura: o smoke assina com a de debug (só aqui, nunca para subir).
            gradle_release -PkeyflowSmokeSigning=true :app:assembleRelease
        fi
        [[ -f "$apk" ]] || { echo "APK de release assinado ausente: $apk" >&2; exit 2; }
    fi
    # O dump do sistema no API 34 pode lançar NPE em childNafCheck. Este leitor espera as
    # janelas, limpa o cache e tolera filhos removidos durante a consulta, sem alterar o APK.
    dump_build="$(mktemp -d "${TMPDIR:-/tmp}/keyflow-release-dump.XXXXXX")"
    trap 'rm -rf "$dump_build"' EXIT
    javac --release 17 -cp "$sdk/platforms/android-36/android.jar" -d "$dump_build" scripts/ReleaseUiDump.java
    "$build_tools/d8" --min-api 34 --lib "$sdk/platforms/android-36/android.jar" \
        --output "$evidence/smoke-ui-dump.zip" "$dump_build/ReleaseUiDump.class"
    rm -rf "$dump_build"
    trap - EXIT

    cp "$apk" "$evidence/smoke-release.apk"
    apk="$evidence/smoke-release.apk"
    pkg="$("$aapt2" dump packagename "$apk")"
    echo "APK: $apk ($(stat -c %s "$apk") bytes), pacote $pkg"
    certs="$("$apksigner" verify --print-certs "$apk")"
    awk '/certificate SHA-256 digest/ {print; exit}' <<< "$certs"

    # Varre todas as entradas descomprimidas (resources.arsc usa UTF-16, DEX usa UTF-8).
    python3 scripts/check-release-admob.py "$apk"
    if [[ -f "$mapping" ]]; then
        python3 scripts/check-release-jni.py "$mapping" "$apk"
    else
        echo "sem $mapping: conferência do JNI ignorada (APK de fora do Gradle)"
    fi

    # O release usa produção e não pode embutir identificador/token de Access, simulador ou agente.
    dex_dump="$(mktemp "${TMPDIR:-/tmp}/keyflow-release-dex.XXXXXX")"
    trap 'rm -f "$dex_dump"' EXIT
    unzip -p "$apk" 'classes*.dex' > "$dex_dump"
    if grep -aEq 'keyflowStagingAccess|keyflow-api-staging\.arateki\.com|KEYFLOW_SIMULATED_PURCHASE|SimulatedPurchase|simulated_purchase|/v1/test/purchases|kfsim1|com/keyflow/agenthost/AgentHost|com/keyflow/agent/AgentController|com/keyflow/ui/config/KeyflowPanel' "$dex_dump"; then
        echo "release contém caminho de staging, compra simulada ou chat do agente" >&2
        exit 1
    fi
    for symbol in 'androidx/work/impl/WorkDatabase_Impl' 'com/keyflow/transcription/local/ITranscriptionEngine$Stub' 'com/caverock/androidsvg/SVG'; do
        if ! grep -aFq "$symbol" "$dex_dump"; then
            echo "classe protegida por R8 ausente do release: $symbol" >&2
            exit 1
        fi
    done
    if [[ -f "$mapping" ]]; then
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
    fi
    rm -f "$dex_dump"
    trap - EXIT

    # Fase 2 com a trava do emulador, só agora: o build pode ter esperado minutos na fila do Gradle.
    adb start-server >/dev/null 2>&1
    # O chamador pode agrupar smoke e instrumentados sob a mesma trava, sem tentar pegá-la duas vezes.
    phase2=(env KEYFLOW_SMOKE_PHASE=2 KEYFLOW_SMOKE_APK="$apk" KEYFLOW_SMOKE_PKG="$pkg" KEYFLOW_SMOKE_UI_DUMP_JAR="$evidence/smoke-ui-dump.zip" "$0" "$@")
    if [[ "${KEYFLOW_SMOKE_LOCKED:-}" == 1 ]]; then exec "${phase2[@]}"; fi
    exec flock /tmp/keyflow-emulator.lock "${phase2[@]}"
fi

# --- Fase 2: emulador -----------------------------------------------------------------------------------------------------
apk="$KEYFLOW_SMOKE_APK"
pkg="$KEYFLOW_SMOKE_PKG"
# O id curto de componente ("pkg/.ui.X") é relativo ao applicationId; as classes ficam em com.keyflow (namespace).
ime="$pkg/com.keyflow.ime.KeyFlowKeyboardService"
setup="$pkg/com.keyflow.ui.SetupActivity"
window_xml="$(mktemp "${TMPDIR:-/tmp}/keyflow-release-window.XXXXXX")"
stay_awake=""
system_anr_recoveries=0
adbx() { adb -s "$serial" "$@" </dev/null; }
finish_smoke() {
    local result=$?
    trap - EXIT ERR
    if (( result != 0 )); then
        adbx exec-out screencap -p > "$evidence/smoke-falha.png" 2>/dev/null || true
        cp "$window_xml" "$evidence/smoke-falha.xml" || true
        timeout 20 adb -s "$serial" shell dumpsys window displays > "$evidence/smoke-falha-foco.txt" 2>/dev/null || true
        adbx logcat -d -v brief > "$log" 2>/dev/null || true
    fi
    if [[ "${KEYFLOW_SMOKE_KEEP:-}" != 1 ]]; then
        adbx shell ime reset >/dev/null 2>&1 || true
        adbx uninstall "$pkg" >/dev/null 2>&1 || true
    fi
    if [[ -n "$stay_awake" ]]; then
        if [[ "$stay_awake" == null ]]; then
            adbx shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1 || true
        else
            adbx shell settings put global stay_on_while_plugged_in "$stay_awake" >/dev/null 2>&1 || true
        fi
    fi
    adbx shell rm -f /data/local/tmp/keyflow-release-dump.jar /sdcard/keyflow-release-window.xml >/dev/null 2>&1 || true
    rm -f "$window_xml"
    exit "$result"
}
trap finish_smoke EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
set -E   # o trap de ERR vale também dentro das funções
trap 'echo "smoke: falhou na linha $LINENO: $BASH_COMMAND" >&2' ERR
adb -s "$serial" get-state >/dev/null || { echo "inicie o emulador antes do smoke (emulador ligar)" >&2; exit 2; }
# O emulador pode estar subindo (outro agente o religou): espera o boot e o serviço de pacotes antes de instalar.
for _ in {1..60}; do
    [[ "$(adbx shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && adbx shell pm path android >/dev/null 2>&1 && break
    sleep 5
done

adbx push "$KEYFLOW_SMOKE_UI_DUMP_JAR" /data/local/tmp/keyflow-release-dump.jar >/dev/null

# Mantém a tela acessível mesmo se o emulador estava com o visor apagado/bloqueado na fila.
adbx shell dumpsys power | grep -m1 'mWakefulness=' > "$evidence/smoke-energia-inicial.txt" || true
stay_awake="$(adbx shell settings get global stay_on_while_plugged_in | tr -d '\r')"
adbx shell svc power stayon true
adbx shell input keyevent KEYCODE_WAKEUP
adbx shell wm dismiss-keyguard

# Instalação limpa e do zero: a assinatura do release (upload) difere da de debug de quem instalou antes.
adbx uninstall "$pkg" >/dev/null 2>&1 || true
adbx install "$apk"
adbx shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
adbx shell pm grant "$pkg" android.permission.RECORD_AUDIO
adbx shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS || true
# O sistema de entrada demora a enxergar o teclado recém-instalado ("Unknown input method"): espera aparecer na lista.
for _ in {1..30}; do
    adbx shell ime list -a -s | tr -d '\r' | grep -qx "$ime" && break
    sleep 2
done
adbx shell ime enable "$ime"
# Já ativo: com o KeyFlow habilitado e outro teclado em uso, o app abre o seletor de teclados do sistema ao fim do onboarding,
# e esse diálogo cobre a tela inteira para o uiautomator.
adbx shell ime set "$ime"
adbx logcat -c
launch_result="$(adbx shell am start -W -n "$setup" 2>&1)"
printf '%s\n' "$launch_result"
if ! grep -q '^Status: ok' <<< "$launch_result"; then
    echo "SetupActivity do release não abriu" >&2
    exit 1
fi
sleep 5
app_pids="$(adbx shell pidof "$pkg" | tr -d '\r')"

# Consulta todas as janelas (incluindo o app sob a orbe), com cache renovado e filhos nulos tratados.
raw_dump() {
    local attempt dump_result
    for attempt in 1 2 3 4 5; do
        : > "$window_xml"
        adbx shell rm -f /sdcard/keyflow-release-window.xml || true
        if timeout 60 adb -s "$serial" shell 'CLASSPATH=/data/local/tmp/keyflow-release-dump.jar app_process /system/bin ReleaseUiDump /sdcard/keyflow-release-window.xml' >> "$evidence/smoke-acessibilidade.log" 2>&1 </dev/null; then
            dump_result=0
        else
            dump_result=$?
        fi
        adbx pull /sdcard/keyflow-release-window.xml "$window_xml" >/dev/null 2>&1 || true
        (( dump_result == 0 )) && grep -q 'type="TYPE_APPLICATION"' "$window_xml" && return 0
        recover_system_dialog || true
        sleep 3
    done
    echo "não foi possível obter a árvore de uma janela de aplicativo" >&2
    return 1
}

tap_bounds() {
    [[ "$1" =~ ^\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]$ ]] || { echo "limites inválidos para toque: $1" >&2; return 1; }
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 < <(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$1")
    adbx shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
}

# No API 34, o diálogo de ANR da System UI pode sumir da árvore do UIAutomator.
# O foco atual do WindowManager permite identificá-lo sem confundir com ANR do KeyFlow.
recover_system_dialog() {
    local focus size api
    focus="$(adbx shell dumpsys window displays | grep 'mCurrentFocus=' || true)"
    [[ "$focus" =~ Application\ Not\ Responding:\ (system|com\.android\.systemui)\} ]] || return 1
    system_anr_recoveries=$((system_anr_recoveries + 1))
    printf '%s\n' "$focus" > "$evidence/smoke-anr-sistema-$system_anr_recoveries-foco.txt"
    shot "anr-sistema-$system_anr_recoveries"
    (( system_anr_recoveries <= 3 )) || { echo "o sistema continua sem responder após três recuperações" >&2; exit 1; }
    size="$(adbx shell wm size | tr -d '\r')"
    api="$(adbx shell getprop ro.build.version.sdk | tr -d '\r')"
    # Coordenada do botão Wait medida nas capturas do diálogo padrão deste AVD.
    # Em outra resolução/API, falha com evidência em vez de tocar num controle desconhecido.
    [[ "$api" == 34 && "$size" == 'Physical size: 1080x2400' ]] || return 1
    echo "recuperando ANR do sistema confirmado pelo foco ($system_anr_recoveries)" >&2
    adbx shell input tap 400 1364
    sleep 3
}

# Só recupera um ANR quando a árvore identifica um processo do sistema. ANR do app invalida o smoke.
dump_window() {
    local wait_bounds
    raw_dump || exit 1
    if grep -Eqi "isn.t responding|não está respondendo|no responde" "$window_xml"; then
        if ! grep -Eqi 'Process system|System UI|SystemUI|processo system|interface do sistema' "$window_xml"; then
            echo "ANR de aplicativo durante o smoke; veja as evidências" >&2
            exit 1
        fi
        system_anr_recoveries=$((system_anr_recoveries + 1))
        shot "anr-sistema-$system_anr_recoveries"
        (( system_anr_recoveries <= 3 )) || { echo "o sistema continua sem responder após três recuperações" >&2; exit 1; }
        wait_bounds="$(find_bounds 'Wait' 'Aguardar' 'Esperar')"
        [[ -n "$wait_bounds" ]] || { echo "diálogo do sistema sem botão Wait acessível" >&2; exit 1; }
        echo "recuperando diálogo do sistema confirmado pela árvore ($system_anr_recoveries)" >&2
        tap_bounds "$wait_bounds"
        sleep 3
        raw_dump || exit 1
    fi
}

shot() { adbx exec-out screencap -p > "$evidence/smoke-$1.png"; }

find_bounds() {
    python3 - "$window_xml" "$@" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(0)
needles = [value.casefold() for value in sys.argv[2:]]
matches = []
for node in root.iter("node"):
    label = " ".join((node.attrib.get("text", ""), node.attrib.get("content-desc", ""))).strip().casefold()
    if any(label == needle for needle in needles) and node.attrib.get("bounds"):
        matches.append(node)
# O título da tela repete o nome da aba (texto solto); a aba da coluna é o nó clicável: ele tem a preferência.
matches.sort(key=lambda node: node.attrib.get("clickable") != "true")
if matches:
    print(matches[0].attrib["bounds"])
PY
}

# A coluna de abas ocupa a borda esquerda: rola o conteúdo pela margem direita.
scroll_down() {
    local x y1 y2
    read -r x y1 y2 < <(python3 - "$window_xml" <<'SCROLL'
import sys, re
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    root = ET.Element("empty")
areas = []
for node in root.iter("node"):
    b = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
    if node.get("scrollable") == "true" and len(b) == 4 and b[2] - b[0] > 400 and b[3] - b[1] > 400:
        areas.append(b)
if areas:
    x1, y1, x2, y2 = max(areas, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
    # Margem do painel: evita os editores Canvas e as teclas da prévia, que tratam seus próprios gestos.
    print(x2 - 12, y2 - 80, y1 + 120)
else:
    print(1000, 2000, 600)
SCROLL
)
    adbx shell input swipe "$x" "$y1" "$x" "$y2" 500
    sleep 1
}

tap_label() {
    echo "toque: $*"
    local bounds attempt
    for attempt in {1..8}; do
        dump_window
        bounds="$(find_bounds "$@")"
        if [[ -n "$bounds" ]]; then
            tap_bounds "$bounds"
            sleep 2
            return 0
        fi
        scroll_down
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

# Toca o chip que fica ACIMA de um rótulo (as skins: o rótulo é um texto solto debaixo do botão).
tap_above_label() {
    local bounds x1 y1 x2 y2 attempt
    for attempt in {1..8}; do
        dump_window
        bounds="$(find_bounds "$@")"
        if [[ -n "$bounds" ]]; then
            read -r x1 y1 x2 y2 < <(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")
            adbx shell input tap "$(((x1 + x2) / 2))" "$((y1 - 60))"
            sleep 3
            return 0
        fi
        scroll_down
    done
    echo "não encontrei o rótulo: $*" >&2
    return 1
}

# Rola até aparecer um dos textos (o conteúdo das abas passa da tela).
scroll_until() {
    echo "rolando até: $*"
    local attempt
    for attempt in {1..12}; do
        dump_window
        if [[ -n "$(find_bounds "$@")" ]]; then return 0; fi
        scroll_down
    done
    echo "não achei, mesmo rolando: $*" >&2
    return 1
}

# Pixels diferentes nas três fileiras de letras da prévia ("q" até "m"); a orbe
# flutuante se mexe sozinha e fica de fora).
preview_diff() {
    local before="$1" after="$2" box
    box="$(python3 - "$window_xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
import re
root = ET.parse(sys.argv[1]).getroot()
q = last = None
for node in root.iter("node"):
    d = node.attrib.get("content-desc", "")
    b = [int(v) for v in re.findall(r"\d+", node.attrib.get("bounds", "0 0 0 0"))]
    if d == "q" and q is None:
        q = b
    if d == "m" and last is None:
        last = b
if q and last:
    print(f"{last[2] - q[0]}x{last[3] - q[1]}+{q[0]}+{q[1]}")
PY
)"
    [[ -n "$box" ]] || { echo "prévia do teclado não achada para comparar as skins" >&2; return 1; }
    local metric result
    if metric="$(compare -metric AE -fuzz 3% "$evidence/smoke-$before.png[$box]" "$evidence/smoke-$after.png[$box]" null: 2>&1)"; then
        result=0
    else
        result=$?
    fi
    (( result <= 1 )) || { echo "$metric" >&2; return "$result"; }
    # ImageMagick 7 imprime "256 (1)" e pode usar notação científica: AE é o primeiro número.
    metric="${metric%% *}"
    [[ "$metric" =~ ^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$ ]] || { echo "métrica AE inválida: $metric" >&2; return 1; }
    awk -v n="$metric" 'BEGIN { printf "%.0f\n", n }'
}

# Os Ajustes podem restaurar a busca anterior. Localiza e toca o campo, mesmo nesse caso;
# uma coordenada fixa no conteúdo pode fechar o IME em vez de abrir a busca.
open_keyboard_in_search() {
    local field attempt imm
    adbx shell am start -a android.settings.SETTINGS >/dev/null
    sleep 4
    tap_label 'Search settings' 'Pesquisar configurações' 'Buscar en ajustes'
    dump_window
    field="$(python3 - "$window_xml" <<'FIELD'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("class") in ("android.widget.EditText", "android.widget.AutoCompleteTextView"):
        print(node.get("bounds", ""))
        break
FIELD
)"
    [[ -n "$field" ]] || { echo "campo de busca dos Ajustes não encontrado" >&2; return 1; }
    tap_bounds "$field"
    for attempt in {1..15}; do
        imm="$(adbx shell dumpsys input_method | tr -d '\r')"
        if grep -q "mCurMethodId=$ime" <<< "$imm" && grep -q 'mInputShown=true' <<< "$imm"; then return 0; fi
        sleep 2
    done
    printf '%s\n' "$imm" > "$evidence/smoke-input-method-falha.txt"
    echo "o teclado do KeyFlow não abriu no campo de texto" >&2
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
tap_label 'Transcrição' 'Transcription' 'Transcripción'
tap_label 'Visão' 'Vision' 'Visión'
tap_label 'Raciocínio' 'Reasoning' 'Razonamiento'
tap_label 'Voz' 'Voice'
tap_label 'Computador' 'Computer' 'Computadora'
tap_label 'Privacidade' 'Privacy' 'Privacidad'
shot abas

# Skins: a tela de teclado compõe a prévia (SVG/Canvas); trocar a skin redesenha o teclado e grava nas Settings (DataStore).
tap_label 'Teclado' 'Keyboard'
scroll_until 'Suas skins' 'Your skins' 'Tus skins'
tap_above_label 'Sistema' 'System'
dump_window; shot skin-sistema
tap_above_label 'Ateliê' 'Atelier'
dump_window; shot skin-atelier
diff_pixels="$(preview_diff skin-sistema skin-atelier)"
echo "skin Sistema -> Ateliê: $diff_pixels pixels diferentes na prévia do teclado"
if ! [[ "$diff_pixels" =~ ^[0-9]+$ ]] || (( diff_pixels < 500 )); then
    echo "a prévia do teclado não mudou ao trocar de skin (diferença: $diff_pixels)" >&2
    exit 1
fi

# Compra (Play Billing): a skin paga e o botão abrem a folha; sem Play Store/conta no emulador o Billing responde "indisponível"
# e a folha mostra o aviso: cobre a integração com BillingClient e o tratamento da indisponibilidade sob o R8.
# O emulador sem loja/conta não comprova ProxyBillingActivity nem uma compra real.
tap_label 'Desbloquear skins' 'Unlock skins'
assert_label 'R$ 15'
shot compra-folha
tap_label 'Pagar R$ 15 — uma vez' 'Pay R$ 15 — once' 'Pagar R$ 15 — una vez'
sleep 6
assert_label 'Tente novamente' 'Try again' 'Inténtalo de nuevo'
shot compra-indisponivel
adbx shell input tap 540 300   # o véu da folha fecha (o botão Voltar do sistema fecharia o app, não a folha)
sleep 2

# Docs e Teclado são os hosts de banner. A etiqueta semântica confirma slot montado quando a
# monetização terminou de carregar; o slot é ausente enquanto o entitlement ainda não chegou. Num emulador o AdMob serve
# anúncios de TESTE para qualquer ID válido ("Test Ad"), então o banner carrega de verdade.
tap_label 'Docs'
sleep 15
assert_label 'Banner de anúncios' 'Ad banner' 'Banner de anuncios'
assert_label 'Test Ad'
shot banner

# O teclado do KeyFlow num campo de texto de verdade (a busca dos Ajustes): janela do IME aberta e as teclas digitando.
open_keyboard_in_search
# As teclas do IME aparecem no dump como nós "h" e "i" (content-desc); sem eles, as coordenadas da grade padrão no emulador 1080x2400.
dump_window
for key in h i; do
    key_bounds="$(find_bounds "$key")"
    if [[ -n "$key_bounds" ]]; then
        tap_bounds "$key_bounds"
    elif [[ "$key" == h ]]; then
        adbx shell input tap 659 1906
    else
        adbx shell input tap 810 1760
    fi
    sleep 1
done
dump_window
[[ -n "$(find_bounds 'hi')" ]] || { echo "o texto digitado no teclado do KeyFlow não chegou ao campo" >&2; cat "$window_xml" >&2; exit 1; }
shot teclado-digitando
adbx shell input keyevent 4
adbx shell input keyevent 3

# Ditado com o modelo local (sherpa-onnx, JNI) — opcional, KEYFLOW_SMOKE_DICTATION=1: baixa o modelo Rápido (Whisper base, ~160 MB,
# a fila de downloads do app com OkHttp/SHA-256), grava pelo teclado e espera o :inference carregar o modelo. O emulador não tem
# microfone de verdade: a gravação é silêncio, o que basta para passar por AIDL, Parcelable, sherpa-onnx (config, reconhecedor e
# resultado) e voltar sem crash; o texto reconhecido de uma fala real fica para o roteiro do A32.
if [[ "${KEYFLOW_SMOKE_DICTATION:-}" == 1 ]]; then
    echo "etapa: ditado local"
    touch "$evidence/smoke-ditado-iniciado"
    adbx shell am start -n "$setup" >/dev/null
    sleep 4
    tap_label 'Transcrição' 'Transcription' 'Transcripción'
    tap_label 'Local'
    tap_label 'Baixar' 'Download' 'Descargar'
    for _ in {1..40}; do
        dump_window
        [[ -n "$(find_bounds 'Apagar' 'Delete' 'Eliminar')" ]] && break
        sleep 15
    done
    [[ -n "$(find_bounds 'Apagar' 'Delete' 'Eliminar')" ]] || { echo "o modelo Rápido não terminou de baixar em 10 min" >&2; exit 1; }
    shot modelo-instalado
    # Preserva também os erros das etapas anteriores para a conferência final de R8/JNI.
    open_keyboard_in_search
    dump_window
    mic_bounds="$(find_bounds '🎙')"
    [[ -n "$mic_bounds" ]] || { echo "tecla do microfone não achada" >&2; exit 1; }
    tap_bounds "$mic_bounds"
    sleep 6
    shot ditado-gravando
    dump_window
    # Durante a gravação o rótulo vira "● 0:06"; a posição do botão continua a mesma.
    tap_bounds "$mic_bounds"
    for _ in {1..48}; do
        adbx logcat -d -v brief -s InferenceService:I | grep -Eq 'ms de áudio .*transcrever [0-9]+ ms' && break
        sleep 5
    done
    adbx logcat -d -v brief -s InferenceService:I LocalTranscriber:W | tee "$evidence/smoke-ditado-logcat.txt"
    grep -q 'carregado em' "$evidence/smoke-ditado-logcat.txt" || { echo "o :inference não carregou o modelo local" >&2; exit 1; }
    grep -Eq 'ms de áudio .*transcrever [0-9]+ ms' "$evidence/smoke-ditado-logcat.txt" || { echo "a inferência local não terminou" >&2; exit 1; }
    shot ditado-fim
    adbx shell input keyevent 4
    adbx shell input keyevent 3
fi

# Os ajustes persistem (DataStore, protobuf) depois de matar o processo: a divulgação aceita não volta.
adbx shell am force-stop "$pkg"
adbx shell am start -W -n "$setup" >/dev/null
sleep 5
dump_window
if [[ -n "$(find_bounds 'Aceitar e continuar' 'Accept and continue' 'Aceptar y continuar')" ]]; then
    echo "a divulgação voltou depois de reabrir: o DataStore não persistiu" >&2
    exit 1
fi
assert_label 'Em breve' 'Coming soon' 'Próximamente' 'Keyboard' 'Teclado'

# O log de todo o smoke fica salvo para revisão; erro de R8/JNI/parcel interrompe o script.
adbx logcat -d -v brief > "$log"
cp "$log" "$evidence/smoke-logcat.txt"
# Considera o PID inicial, reinícios, subprocessos e tombstones do app. Crashes do capturador
# ou de outros processos do sistema ficam no log completo, sem serem atribuídos ao release.
if ! python3 scripts/check-release-logcat.py "$log" "$pkg" "$app_pids"; then
    echo "erro fatal na saída do smoke; consulte $log" >&2
    exit 1
fi
adbx shell pidof "$pkg" >/dev/null
echo "Smoke release OK em $serial ($pkg); log: $log; capturas em $evidence"

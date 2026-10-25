#!/usr/bin/env bash
# Gera artefatos visuais do gesto de segurar o mic do teclado pra mudar o estado da gravação
# (pausar/retomar, cancelar, desfazer — KBDCTL-01..04).
#
# Fluxo:
#   1. instala o APK debug e o APK de androidTest;
#   2. roda KeyboardMicHoldMotionArtifactTest via `am instrument` (não via connectedDebugAndroidTest,
#      porque o Gradle desinstala o app ao final e apaga externalFilesDir);
#   3. puxa PNGs + metadata.json para /tmp/keyflow-prints/keyboard-mic-hold-gifs;
#   4. monta GIFs com ImageMagick.
#
# Uso:
#   ANDROID_SERIAL=emulator-5554 scripts/verify-keyboard-mic-hold-gifs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.arateki.keyflow"
TEST_PKG="com.arateki.keyflow.test"
TEST_CLASS="com.keyflow.ime.KeyboardMicHoldMotionArtifactTest"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/keyboard-mic-hold-gifs"
OUT_DIR="${OUT_DIR:-/tmp/keyflow-prints/keyboard-mic-hold-gifs}"
GIF_DELAY="${GIF_DELAY:-10}"
GRADLE="${GRADLE:-/home/yan/.local/gradle/current/bin/gradle}"
ADB="${ADB:-/home/yan/Android/Sdk/platform-tools/adb}"

adbx() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then "$ADB" -s "$ANDROID_SERIAL" "$@"; else "$ADB" "$@"; fi
}

run_gradle() {
  GRADLE_USER_HOME="$ROOT/.gradle" "$GRADLE" --no-daemon "$@"
}

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERRO: ferramenta '$1' não encontrada" >&2
    exit 2
  fi
}

require_tool magick

mkdir -p "$OUT_DIR"

echo "Instalando APK debug e androidTest..."
run_gradle :app:installDebug :app:installDebugAndroidTest

echo "Preparando device..."
adbx shell rm -rf "$DEVICE_DIR" >/dev/null 2>&1 || true
adbx shell settings put secure immersive_mode_confirmations confirmed >/dev/null || true
adbx shell settings put global window_animation_scale 0 >/dev/null || true
adbx shell settings put global transition_animation_scale 0 >/dev/null || true
adbx shell settings put global animator_duration_scale 0 >/dev/null || true

echo "Rodando $TEST_CLASS via am instrument..."
adbx shell am instrument -w -e class "$TEST_CLASS" "$RUNNER"

echo "Puxando artefatos para $OUT_DIR..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
adbx pull "$DEVICE_DIR/." "$OUT_DIR/" >/dev/null

{
  echo "captured_at_host=$(date -Iseconds)"
  echo "android_serial=${ANDROID_SERIAL:-}"
  echo "adb_devices:"
  adbx devices
  echo "fingerprint=$(adbx shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "release=$(adbx shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adbx shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adbx shell getprop ro.product.model | tr -d '\r')"
  echo "manufacturer=$(adbx shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "wm_size=$(adbx shell wm size | tr -d '\r')"
  echo "wm_density=$(adbx shell wm density | tr -d '\r')"
} > "$OUT_DIR/host-run.txt"

GIF_DIR="$OUT_DIR/gifs"
mkdir -p "$GIF_DIR"

shopt -s nullglob
count=0
for scenario_dir in "$OUT_DIR"/*; do
  [ -d "$scenario_dir" ] || continue
  [ "$(basename "$scenario_dir")" = "gifs" ] && continue
  frames=("$scenario_dir"/frame-*.png)
  [ "${#frames[@]}" -gt 0 ] || continue
  name="$(basename "$scenario_dir")"
  magick -delay "$GIF_DELAY" -loop 0 "${frames[@]}" "$GIF_DIR/$name.gif"
  count=$((count + 1))
  echo "  ✓ gifs/$name.gif (${#frames[@]} frames)"
done

echo "Concluído: $count GIFs em $GIF_DIR"
echo "Metadados: $OUT_DIR/metadata.json e $OUT_DIR/host-run.txt"

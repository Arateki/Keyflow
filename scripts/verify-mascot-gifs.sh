#!/usr/bin/env bash
# Gera os GIFs de movimento do mascote da orbe (um por estado: repouso, escutando, pausado, transcrevendo).
#
# Fluxo (o mesmo do Q6/Q7):
#   1. instala o APK debug e o APK de androidTest;
#   2. roda MascotMotionArtifactTest via `am instrument` (não via connectedDebugAndroidTest, porque o
#      Gradle desinstala o app ao final e apaga externalFilesDir);
#   3. puxa os PNGs para /tmp/keyflow-prints/mascot-gifs;
#   4. monta GIFs com ImageMagick (cada quadro tem a orbe sobre branco e sobre preto, lado a lado).
#
# Uso:
#   ANDROID_SERIAL=emulator-5554 scripts/verify-mascot-gifs.sh
#
# Com o emulador compartilhado, rode dentro do flock dele: `flock /tmp/keyflow-emulator.lock scripts/verify-mascot-gifs.sh`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.keyflow"
TEST_PKG="com.keyflow.test"
TEST_CLASS="com.keyflow.ui.orb.MascotMotionArtifactTest"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/mascot-gifs"
OUT_DIR="${OUT_DIR:-/tmp/keyflow-prints/mascot-gifs}"
GIF_DELAY="${GIF_DELAY:-5}"   # centésimos de segundo por quadro: 5 = 20 quadros por segundo, o mesmo passo do teste
GRADLE="${GRADLE:-/home/yan/.local/gradle/current/bin/gradle}"
ADB="${ADB:-/home/yan/Android/Sdk/platform-tools/adb}"

adbx() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then "$ADB" -s "$ANDROID_SERIAL" "$@"; else "$ADB" "$@"; fi
}

run_gradle() {
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle}" "$GRADLE" --no-daemon "$@"
}

if ! command -v magick >/dev/null 2>&1; then
  echo "ERRO: ferramenta 'magick' não encontrada" >&2
  exit 2
fi

mkdir -p "$OUT_DIR"

if [ "${SKIP_INSTALL:-0}" != 1 ]; then
  echo "Instalando APK debug e androidTest..."
  run_gradle :app:installDebug :app:installDebugAndroidTest
fi

echo "Preparando device..."
adbx shell rm -rf "$DEVICE_DIR" >/dev/null 2>&1 || true
adbx shell settings put secure immersive_mode_confirmations confirmed >/dev/null || true

echo "Rodando $TEST_CLASS via am instrument..."
adbx shell am instrument -w -e class "$TEST_CLASS" "$RUNNER"

echo "Puxando artefatos para $OUT_DIR..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
adbx pull "$DEVICE_DIR/." "$OUT_DIR/" >/dev/null

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
  # Paleta de 64 cores e só o que muda de um quadro para o outro: os GIFs ficam pequenos (200 a 400 KB) sem perder o contorno.
  magick -delay "$GIF_DELAY" -loop 0 "${frames[@]}" -fuzz 3% -colors 64 -layers OptimizePlus -layers OptimizeTransparency "$GIF_DIR/$name.gif"
  count=$((count + 1))
  echo "  ✓ gifs/$name.gif (${#frames[@]} quadros)"
done

echo "Concluído: $count GIFs em $GIF_DIR"

#!/usr/bin/env bash
# Captura determinística das rotas Compose da galeria (T8 / VERIFY-02).
#
# Para cada rota do RouteCatalog × tema (claro/escuro): força-para o app, lança a GalleryActivity,
# aguarda o marcador `gallery_ready`, e salva verification/<rota>[-dark].png no emulador headless.
#
# Pré-requisitos: APK debug instalado (assembleDebug + adb install). Emulador alvo via ANDROID_SERIAL
# ou o único device conectado. Rodar 2× deve produzir PNGs bit-idênticos para as rotas do orbe.
#
#   ANDROID_SERIAL=emulator-5554 scripts/verify-screens.sh [diretório-de-saída]
set -euo pipefail

PKG="com.keyflow"
ACT="$PKG/.ui.gallery.GalleryActivity"
FILES_DIR="/sdcard/Android/data/$PKG/files"
READY="$FILES_DIR/gallery_ready"
ROUTES_FILE="$FILES_DIR/gallery_routes"

OUT_DIR="${1:-verification}"
mkdir -p "$OUT_DIR"

# adb com escopo opcional ao ANDROID_SERIAL.
adbx() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then adb -s "$ANDROID_SERIAL" "$@"; else adb "$@"; fi
}

# Determinismo: suprime o overlay "Viewing full screen" e congela a UI ao máximo.
adbx shell settings put secure immersive_mode_confirmations confirmed >/dev/null
adbx shell settings put global window_animation_scale 0 >/dev/null || true
adbx shell settings put global transition_animation_scale 0 >/dev/null || true
adbx shell settings put global animator_duration_scale 0 >/dev/null || true

# Lista de rotas a partir da fonte única (RouteCatalog), via modo lista da galeria.
adbx shell am force-stop "$PKG"
adbx shell am start -n "$ACT" --es route __list__ >/dev/null
for _ in $(seq 1 20); do
  if adbx shell "test -f $ROUTES_FILE" 2>/dev/null; then break; fi
  sleep 0.3
done
ROUTES=$(adbx shell "cat $ROUTES_FILE 2>/dev/null || true" | tr -d '\r')
if [ -z "$ROUTES" ]; then
  echo "ERRO: não obtive a lista de rotas do RouteCatalog" >&2
  exit 1
fi

capture() {
  local route="$1" dark="$2" suffix="$3"
  adbx shell am force-stop "$PKG"
  adbx shell am start -W -n "$ACT" --es route "$route" --ez dark "$dark" >/dev/null
  local ok=""
  for _ in $(seq 1 40); do
    local v
    v=$(adbx shell "cat $READY 2>/dev/null || true" | tr -d '\r')
    if [ "$v" = "$route" ]; then ok=1; break; fi
    sleep 0.25
  done
  if [ -z "$ok" ]; then
    echo "ERRO: rota '$route' (dark=$dark) não ficou pronta" >&2
    exit 1
  fi
  adbx exec-out screencap -p > "$OUT_DIR/$route$suffix.png"
  echo "  ✓ $route$suffix.png"
}

echo "Capturando rotas em $OUT_DIR/ ..."
for route in $ROUTES; do
  capture "$route" false ""
  capture "$route" true "-dark"
done
adbx shell am force-stop "$PKG"
echo "Concluído: $(ls "$OUT_DIR"/*.png | wc -l) PNGs."

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
#
# Recortes (o CI divide a galeria entre emuladores; o diff-screens.sh aceita os mesmos):
#   THEMES       temas capturados (default "light dark");
#   ROUTE_SHARD  "k/n": só as rotas de posição i no catálogo com i % n == k (default "0/1", todas).
# A lista completa de rotas fica em <saída>/routes.txt, para o diff saber o que é de cada recorte.
#
# READY_TIMEOUT (s, default 30): espera máxima pelo marcador de cada rota. Medido no CI (2026-10-09):
# a GalleryActivity leva 3–8 s para abrir a frio no emulador de 2 núcleos; 10 s não bastaram.
set -euo pipefail

PKG="com.arateki.keyflow"
ACT="$PKG/com.keyflow.ui.gallery.GalleryActivity"
FILES_DIR="/sdcard/Android/data/$PKG/files"
READY="$FILES_DIR/gallery_ready"
ROUTES_FILE="$FILES_DIR/gallery_routes"

OUT_DIR="${1:-verification}"
mkdir -p "$OUT_DIR"
THEMES="${THEMES:-light dark}"
for theme in $THEMES; do
  case "$theme" in
    light|dark) ;;
    *) echo "ERRO: tema '$theme' em THEMES (use light e/ou dark)" >&2; exit 2 ;;
  esac
done
ROUTE_SHARD="${ROUTE_SHARD:-0/1}"
SHARD_INDEX="${ROUTE_SHARD%/*}"
SHARD_COUNT="${ROUTE_SHARD#*/}"
if ! [[ "$SHARD_INDEX" =~ ^[0-9]+$ && "$SHARD_COUNT" =~ ^[1-9][0-9]*$ ]] || [ "$SHARD_INDEX" -ge "$SHARD_COUNT" ]; then
  echo "ERRO: ROUTE_SHARD='$ROUTE_SHARD' (use k/n, com 0 <= k < n)" >&2
  exit 2
fi
READY_TIMEOUT="${READY_TIMEOUT:-30}"

# adb com escopo opcional ao ANDROID_SERIAL.
adbx() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then adb -s "$ANDROID_SERIAL" "$@"; else adb "$@"; fi
}

# Determinismo: suprime o overlay "Viewing full screen" e congela a UI ao máximo.
adbx shell settings put secure immersive_mode_confirmations confirmed >/dev/null
adbx shell settings put global window_animation_scale 0 >/dev/null || true
adbx shell settings put global transition_animation_scale 0 >/dev/null || true
adbx shell settings put global animator_duration_scale 0 >/dev/null || true
# Diálogo de sistema deixado aberto (ex.: o seletor de teclado que o "Pular" do onboarding abre quando o KeyFlow está
# habilitado mas não ativo) fica com o foco: a galeria nunca o recebe, as barras do sistema não somem e toda captura sai com
# a barra de status real (gate da main de 2026-10-11, 22 de 36 telas). O HOME fecha os diálogos de sistema; a aba de
# notificações, o collapse.
adbx shell input keyevent KEYCODE_HOME >/dev/null || true
adbx shell cmd statusbar collapse >/dev/null 2>&1 || true

# Lista de rotas a partir da fonte única (RouteCatalog), via modo lista da galeria.
adbx shell am force-stop "$PKG"
# Sem apagar antes, a espera abaixo acha a lista de uma instalação anterior (emulador reaproveitado) e
# as rotas novas ficam de fora da captura.
adbx shell rm -f "$ROUTES_FILE"
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
printf '%s\n' $ROUTES > "$OUT_DIR/routes.txt"

# Abre a rota e espera o marcador; 0 = pronta.
open_route() {
  local route="$1" dark="$2"
  adbx shell am force-stop "$PKG"
  adbx shell am start -W -n "$ACT" --es route "$route" --ez dark "$dark" >/dev/null
  local deadline=$((SECONDS + READY_TIMEOUT))
  while [ "$SECONDS" -le "$deadline" ]; do
    local v
    v=$(adbx shell "cat $READY 2>/dev/null || true" | tr -d '\r')
    if [ "$v" = "$route" ]; then return 0; fi
    sleep 0.25
  done
  return 1
}

# Uma segunda chance, com aviso: no emulador do CI (GPU por software, 2 núcleos) o 1º quadro das rotas mais pesadas
# compila muitos shaders e pode passar dos 5 s que o Android dá à janela — ANR, e com hide_error_dialogs o app morre
# calado (2026-10-09, config-overlay-travado). O que a 2ª abertura mostrar ainda passa pelo diff contra o golden.
capture() {
  local route="$1" dark="$2" suffix="$3"
  if ! open_route "$route" "$dark"; then
    echo "AVISO: rota '$route' (dark=$dark) não ficou pronta em ${READY_TIMEOUT} s; abrindo de novo" >&2
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::warning::galeria: '$route$suffix' só abriu na 2ª tentativa (ver logcat: ANR?)"
    if ! open_route "$route" "$dark"; then
      echo "ERRO: rota '$route' (dark=$dark) não ficou pronta em ${READY_TIMEOUT} s, duas vezes" >&2
      exit 1
    fi
  fi
  adbx exec-out screencap -p > "$OUT_DIR/$route$suffix.png"
  echo "  ✓ $route$suffix.png"
}

echo "Capturando rotas em $OUT_DIR/ (temas: $THEMES; recorte $ROUTE_SHARD) ..."
i=0
for route in $ROUTES; do
  i=$((i + 1))
  [ $(((i - 1) % SHARD_COUNT)) -eq "$SHARD_INDEX" ] || continue
  for theme in $THEMES; do
    if [ "$theme" = dark ]; then capture "$route" true "-dark"; else capture "$route" false ""; fi
  done
done
adbx shell am force-stop "$PKG"
echo "Concluído: $(ls "$OUT_DIR"/*.png | wc -l) PNGs."

#!/usr/bin/env bash
# Abre rotas da galeria em rodízio, várias vezes, até o emulador cair ou as rodadas acabarem.
#
# Por que existe: o `qemu` do emulador em `-gpu swiftshader_indirect` (o modo do CI) tem um SIGSEGV na RenderThread do renderizador
# gráfico que depende do que a rota desenha (medido em 2026-10-03, ver .specs/quick/018-skin-mascote/TASK.md: o rabo do mascote com
# degradê e dobra positiva derrubava o emulador na 1ª a 5ª abertura). Capturar a galeria inteira só mostra "rota X não ficou pronta";
# isto isola qual rota derruba e em que abertura.
#
# Uso:   ANDROID_SERIAL=emulator-5554 scripts/stress-routes.sh <rodadas> <rota>...
# Saída: uma linha por rodada e, na queda, a rota, a rodada e quantas aberturas o emulador aguentou. Exit 1 se caiu.
# Para não gerar core dump de ~1 GB a cada queda, suba o emulador com `ulimit -c 0` no mesmo shell.
set -uo pipefail

PKG="com.keyflow"
ACT="$PKG/.ui.gallery.GalleryActivity"
READY="/sdcard/Android/data/$PKG/files/gallery_ready"
ROUNDS="${1:?uso: stress-routes.sh <rodadas> <rota>...}"
shift
[ "$#" -gt 0 ] || { echo "uso: stress-routes.sh <rodadas> <rota>..." >&2; exit 2; }
ROUTES=("$@")

# Sempre o emulador: sem `ANDROID_SERIAL`, o `adb` pegaria o único aparelho conectado — que pode ser o físico do Yan.
adbx() {
  adb -s "${ANDROID_SERIAL:-emulator-5554}" "$@"
}

alive() { [ "$(adbx get-state 2>/dev/null)" = device ]; }

adbx shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true

opens=0
for round in $(seq 1 "$ROUNDS"); do
  for i in "${!ROUTES[@]}"; do
    # A rota inicial gira a cada rodada: cada rota aparece em todas as posições do rodízio.
    route="${ROUTES[$(( (round - 1 + i) % ${#ROUTES[@]} ))]}"
    adbx shell am force-stop "$PKG" >/dev/null 2>&1
    adbx shell am start -W -n "$ACT" --es route "$route" --ez dark false >/dev/null 2>&1
    ready=""
    for _ in $(seq 1 80); do
      [ "$(adbx shell "cat $READY 2>/dev/null" 2>/dev/null | tr -d '\r')" = "$route" ] && { ready=1; break; }
      sleep 0.25
    done
    opens=$((opens + 1))
    if ! alive; then
      echo "QUEDA: emulador sem resposta em '$route' (rodada $round, abertura $opens)"
      exit 1
    fi
    [ -n "$ready" ] || echo "aviso: '$route' não ficou pronta (rodada $round), mas o emulador segue vivo"
  done
  echo "rodada $round/$ROUNDS ok ($opens aberturas)"
done
adbx shell am force-stop "$PKG" >/dev/null 2>&1
echo "sem quedas em $opens aberturas"

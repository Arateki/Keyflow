#!/usr/bin/env bash
# Diff de regressão visual (T26 / VERIFY-04). Compara cada golden versionado em
# verification/golden/<nome>.png com o PNG recém-capturado em verification/<nome>.png e falha se a
# diferença passar do limiar. As rotas determinísticas da galeria (T8) são bit-estáveis, então um
# limiar pequeno pega regressões reais tolerando jitter de antialiasing/fonte.
#
#   scripts/verify-screens.sh            # regenera verification/<nome>.png
#   scripts/diff-screens.sh              # compara contra o golden → exit !=0 se regrediu
#   scripts/diff-screens.sh --approve    # promove verification/ → golden/ (exceto sys-*)
#
# Requer ImageMagick (`compare`). Limiar: MAX_RATIO (default 0.005 = 0,5% dos pixels).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY_DIR="$ROOT/verification"
GOLDEN_DIR="$VERIFY_DIR/golden"
FUZZ="${FUZZ:-5%}"
MAX_RATIO="${MAX_RATIO:-0.005}"

# sys-* vêm de device real (wallpaper/relógio variáveis) → não entram no golden.
is_excluded() { [[ "$1" == sys-* ]]; }

approve() {
  mkdir -p "$GOLDEN_DIR"
  local n=0
  for f in "$VERIFY_DIR"/*.png; do
    [ -e "$f" ] || continue
    local base; base="$(basename "$f")"
    is_excluded "$base" && continue
    cp "$f" "$GOLDEN_DIR/$base"
    n=$((n + 1))
  done
  echo "Golden atualizado: $n PNGs em verification/golden/."
}

diff_all() {
  if [ ! -d "$GOLDEN_DIR" ] || [ -z "$(ls -A "$GOLDEN_DIR"/*.png 2>/dev/null)" ]; then
    echo "ERRO: sem golden. Rode 'scripts/diff-screens.sh --approve' primeiro." >&2
    exit 2
  fi

  local fails=0 checked=0
  for g in "$GOLDEN_DIR"/*.png; do
    local base; base="$(basename "$g")"
    local cur="$VERIFY_DIR/$base"
    checked=$((checked + 1))

    if [ ! -f "$cur" ]; then
      echo "  ✗ $base — ausente em verification/ (rode verify-screens.sh)"
      fails=$((fails + 1))
      continue
    fi

    # AE = nº de pixels diferentes além do fuzz; vai para o stderr do compare.
    local ae
    ae="$(compare -metric AE -fuzz "$FUZZ" "$g" "$cur" null: 2>&1 || true)"
    ae="${ae%% *}"  # "123 (0.0018)" → "123"
    ae="${ae%%.*}"  # "123.4" → "123"

    if ! [[ "$ae" =~ ^[0-9]+$ ]]; then
      echo "  ✗ $base — comparação falhou (dimensões diferentes?): $ae"
      fails=$((fails + 1))
      continue
    fi

    local total ratio
    total="$(identify -format '%[fx:w*h]' "$g")"
    ratio="$(awk -v a="$ae" -v t="$total" 'BEGIN { printf "%.6f", (t>0)? a/t : 1 }')"

    if awk -v r="$ratio" -v m="$MAX_RATIO" 'BEGIN { exit (r > m) ? 0 : 1 }'; then
      echo "  ✗ $base — diff ${ratio} (> ${MAX_RATIO}; ${ae} px)"
      fails=$((fails + 1))
    else
      echo "  ✓ $base — diff ${ratio} (${ae} px)"
    fi
  done

  echo "—"
  if [ "$fails" -gt 0 ]; then
    echo "REGRESSÃO: $fails/$checked acima do limiar."
    exit 1
  fi
  echo "OK: $checked/$checked dentro do limiar."
}

case "${1:-}" in
  --approve) approve ;;
  "") diff_all ;;
  *) echo "Uso: diff-screens.sh [--approve]" >&2; exit 2 ;;
esac

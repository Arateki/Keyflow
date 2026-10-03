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
# Requer ImageMagick (`compare`). Limiar: MAX_RATIO (default 0.0002 = 0,02% dos pixels).
#
# Por que tão apertado: a captura é determinística (duas rodadas do mesmo build dão PNGs bit-idênticos —
# medido em 2026-09-08, ruído de 0 px na pior rota), então não há variação legítima a tolerar. O limiar
# antigo (0,5%) deixava passar mudança real: um aviso NOVO de 7 linhas no `config-teclado` gerou 13.714 px
# de diferença = 0,53%, e só não passou por 0,03 ponto. Texto fino gera poucos pixels, então a métrica AE
# subestima muito mudanças textuais — com 0,02% (~520 px) aquele mesmo aviso teria acusado com folga de 26x.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY_DIR="$ROOT/verification"
GOLDEN_DIR="$VERIFY_DIR/golden"
FUZZ="${FUZZ:-5%}"
MAX_RATIO="${MAX_RATIO:-0.0002}"

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
    # O `compare` imprime em notação científica a partir de 7 dígitos ("1.11994e+06"): cortar no ponto dava "1" e uma tela inteira diferente
    # passava como "1 px". Converte pelo awk (aceita "123", "123.4" e "1.11994e+06"); o que não é número fica como está e cai na checagem abaixo.
    if [[ "$ae" =~ ^[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?$ ]]; then
      ae="$(awk -v a="$ae" 'BEGIN { printf "%.0f", a }')"
    fi

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

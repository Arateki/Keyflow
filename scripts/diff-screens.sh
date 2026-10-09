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
# Recortes, os mesmos do verify-screens.sh (o CI captura e compara cada pedaço da galeria num emulador):
#   THEMES       (default "light dark") só os goldens desses temas — `<rota>-dark.png` é escuro, o resto, claro;
#   ROUTE_SHARD  ("k/n", default "0/1") só as rotas do recorte, pela posição em verification/routes.txt
#                (escrito pelo verify-screens.sh). Golden de rota que saiu do catálogo é acusado no recorte 0.
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

THEMES="${THEMES:-light dark}"

# sys-* vêm de device real (wallpaper/relógio variáveis) → não entram no golden.
is_excluded() { [[ "$1" == sys-* ]]; }

# Golden de um tema pedido em THEMES?
in_themes() {
  local theme=light
  [[ "$1" == *-dark.png ]] && theme=dark
  [[ " $THEMES " == *" $theme "* ]]
}

ROUTE_SHARD="${ROUTE_SHARD:-0/1}"
SHARD_INDEX="${ROUTE_SHARD%/*}"
SHARD_COUNT="${ROUTE_SHARD#*/}"
ROUTES_LIST="$VERIFY_DIR/routes.txt"

# Posição de cada rota no catálogo (vazio sem routes.txt: aí todo golden conta, como antes dos recortes).
declare -A ROUTE_POS=()
load_routes() {
  if ! [[ "$SHARD_INDEX" =~ ^[0-9]+$ && "$SHARD_COUNT" =~ ^[1-9][0-9]*$ ]] || [ "$SHARD_INDEX" -ge "$SHARD_COUNT" ]; then
    echo "ERRO: ROUTE_SHARD='$ROUTE_SHARD' (use k/n, com 0 <= k < n)" >&2
    exit 2
  fi
  if [ ! -f "$ROUTES_LIST" ]; then
    if [ "$SHARD_COUNT" -gt 1 ]; then
      echo "ERRO: ROUTE_SHARD=$ROUTE_SHARD sem $ROUTES_LIST (rode o verify-screens.sh antes)" >&2
      exit 2
    fi
    return
  fi
  local i=0 route
  while read -r route; do
    [ -n "$route" ] || continue
    ROUTE_POS[$route]=$i
    i=$((i + 1))
  done < "$ROUTES_LIST"
}

# 0 = comparar; 1 = rota de outro recorte; 2 = rota fora do catálogo.
route_status() {
  [ "${#ROUTE_POS[@]}" -eq 0 ] && return 0
  local route="${1%.png}"
  route="${route%-dark}"
  [ -n "${ROUTE_POS[$route]+x}" ] || return 2
  [ $((ROUTE_POS[$route] % SHARD_COUNT)) -eq "$SHARD_INDEX" ] || return 1
  return 0
}

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

  load_routes
  local fails=0 checked=0
  for g in "$GOLDEN_DIR"/*.png; do
    local base; base="$(basename "$g")"
    in_themes "$base" || continue
    local rs=0
    route_status "$base" || rs=$?
    if [ "$rs" -eq 1 ]; then continue; fi
    if [ "$rs" -eq 2 ]; then
      [ "$SHARD_INDEX" -eq 0 ] || continue
      checked=$((checked + 1))
      echo "  ✗ $base — a rota não está mais no RouteCatalog (apague o golden)"
      fails=$((fails + 1))
      continue
    fi
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
  if [ "$checked" -eq 0 ]; then
    echo "ERRO: nenhum golden dos temas '$THEMES'." >&2
    exit 2
  fi
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

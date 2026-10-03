#!/usr/bin/env bash
# Empacota uma pasta de skin do KeyFlow num .zip pronto para importar (formato: .specs/features/skin-packs/FORMAT.md).
#
#   scripts/build-skin-pack.sh <pasta> <saida.zip>
#   scripts/build-skin-pack.sh skin-packs/exemplo-neon exemplo-neon.zip
#
# O .zip leva o `manifest.json` e os `*.svg` da RAIZ da pasta, na raiz do zip (é o que o app espera). Qualquer outra coisa que esteja na pasta
# (um leia-me, uma imagem de capa, o script que gerou os SVGs, subpastas) fica de fora e é listada, para não virar "arquivo fora do formato"
# no relatório do import. Quem confere o conteúdo é o próprio app, na hora de importar: ele mostra o que aceitou, o que ignorou e o que recusou.
#
# Usa o `zip` quando existe e, senão, o módulo `zipfile` do Python 3. Sobrescreve a saída (um .zip velho nunca é "atualizado" por cima).
set -euo pipefail

uso() {
  echo "uso: $0 <pasta-da-skin> <saida.zip>" >&2
  exit 2
}

[ $# -eq 2 ] || uso
dir="$1"
out="$2"

[ -d "$dir" ] || { echo "ERRO: '$dir' não é uma pasta." >&2; exit 1; }
[ -f "$dir/manifest.json" ] || { echo "ERRO: falta o manifest.json na raiz de '$dir'." >&2; exit 1; }

# O caminho da saída precisa valer depois do `cd` para a pasta da skin.
mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"

cd "$dir"

# Em ordem alfabética (C), para o mesmo conteúdo dar o mesmo zip. Só bash 3 (o do macOS): sem `mapfile`.
export LC_ALL=C
shopt -s nullglob dotglob
svgs=(*.svg)
if [ ${#svgs[@]} -eq 0 ]; then
  echo "ERRO: não há nenhum .svg na raiz de '$dir'." >&2
  exit 1
fi
files=(manifest.json "${svgs[@]}")

skipped=()
for entry in *; do
  case "$entry" in
    manifest.json | *.svg) ;;
    *) skipped+=("$entry") ;;
  esac
done

rm -f "$out"
if command -v zip > /dev/null 2>&1; then
  # -X: sem metadados extras (dono, horário em campo estendido); -q: sem o eco de cada arquivo.
  zip -X -q -9 "$out" "${files[@]}"
elif command -v python3 > /dev/null 2>&1; then
  python3 -m zipfile -c "$out" "${files[@]}"
else
  echo "ERRO: precisa do comando 'zip' ou do python3." >&2
  exit 1
fi

size=$(wc -c < "$out" | tr -d ' ')
echo "OK: $out (${#files[@]} arquivos, ${size} bytes)"
if [ ${#skipped[@]} -gt 0 ]; then
  echo "Ficaram de fora do .zip: ${skipped[*]}"
fi
case "$out" in
  *.zip) ;;
  *) echo "Aviso: a saída não termina em .zip; alguns seletores de arquivo não a mostram." >&2 ;;
esac

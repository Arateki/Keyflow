#!/usr/bin/env python3
"""Gera os dicionários do corretor a partir das listas de palavras do teclado do Android (AOSP LatinIME,
Apache 2.0): `app/src/main/assets/dict/<idioma>.kfd` (binário que o app lê) e, para os testes JVM,
`app/src/test/resources/dict/<idioma>.tsv` (o mesmo conteúdo em texto).

Fonte: espelho do LatinIME no GitHub do CyanogenMod, branch cm-14.1, fixado no commit abaixo (o
googlesource respondia 503 quando isto foi escrito). Cada arquivo baixado é conferido por SHA256 antes
de ser usado — mudou lá, o script para em vez de gerar outro dicionário em silêncio.

Formato texto, uma palavra por linha: `palavra<TAB>freq[<TAB>o]`, com `freq` de 1 a 255 e `o` marcando
as que a lista considera possivelmente ofensivas. Ficam de fora as de frequência 0 e as marcadas
`not_a_word`; ficam só as MAX_WORDS mais frequentes de cada idioma. As linhas saem em ordem alfabética da
forma "dobrada" (minúscula, sem acento — a mesma `WordList.fold` do app): assim o celular não precisa
ordenar 100 mil palavras ao carregar.

Formato binário (`WordList.readBinary`), big-endian: `KFD1`, n, bytes do texto, bytes das formas dobradas
(u32 cada); texto e formas dobradas em Latin-1; tamanho de cada palavra e de cada forma dobrada (u8 × n);
frequências (u8 × n); marca de ofensiva (bits, (n+7)/8 bytes). Ler 100 mil linhas de texto no celular
levava de 1,2 a 6 s; isto é cópia de blocos.

    scripts/build-dictionaries.py [pasta-com-os-.gz-já-baixados]

Com a pasta, usa os arquivos dela (mesma conferência de SHA256) em vez de baixar.
"""
import gzip
import hashlib
import io
import pathlib
import re
import struct
import sys
import unicodedata
import urllib.request

COMMIT = "dded75c18f5dc9449eac12b28ec86f3418ca749c"
BASE = f"https://raw.githubusercontent.com/CyanogenMod/android_packages_inputmethods_LatinIME/{COMMIT}/dictionaries"
SOURCES = {
    # idioma do teclado: (arquivo no LatinIME, SHA256 do .gz baixado)
    "pt": ("pt_BR_wordlist.combined.gz", "f9b7c2f610eacebc782c7d24e64d8ad2620b449defbac326507dfb5702c6c48b"),
    "es": ("es_wordlist.combined.gz", "889ad52bce2933e2a30a0560b8d5a76f5334500e776a7db7c7cb3e9e79fc2652"),
    "en": ("en_US_wordlist.combined.gz", "0f78dd455b532be169a23f233227b811fabced4b5bd7fc9c40cc05839793bcbd"),
}
MAX_WORDS = 100_000
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/dict"
TEST_OUT = ROOT / "app/src/test/resources/dict"
WORD = re.compile(r"^ word=([^,]+),f=(\d+)(.*)$")


def fold(word: str) -> str:
    """Espelho de `WordList.fold`: minúscula, NFD e sem as marcas não espaçantes (acento, til, cedilha)."""
    return "".join(c for c in unicodedata.normalize("NFD", word.lower()) if unicodedata.category(c) != "Mn")


def fetch(name: str, sha256: str, local: pathlib.Path | None) -> bytes:
    if local is not None:
        data = (local / name).read_bytes()
    else:
        data = urllib.request.urlopen(f"{BASE}/{name}", timeout=60).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{name}: SHA256 {digest} não confere com o fixado ({sha256})")
    return data


def build(lang: str, name: str, sha256: str, local: pathlib.Path | None) -> None:
    text = gzip.decompress(fetch(name, sha256, local)).decode("utf-8")
    entries = []
    for line in text.splitlines():
        m = WORD.match(line)
        if not m:
            continue
        word, freq, rest = m.group(1), int(m.group(2)), m.group(3)
        if freq == 0 or "not_a_word=true" in rest or "\t" in word:
            continue
        # O binário guarda Latin-1 e tamanhos em 1 byte: o que não cabe fica de fora (quase nada).
        try:
            word.encode("latin-1"), fold(word).encode("latin-1")
        except UnicodeEncodeError:
            continue
        if len(word) > 255:
            continue
        entries.append((word, freq, "possibly_offensive=true" in rest))
    entries.sort(key=lambda e: -e[1])
    entries = entries[:MAX_WORDS]
    entries.sort(key=lambda e: (fold(e[0]), -e[1]))
    body = io.StringIO()
    for word, freq, offensive in entries:
        body.write(f"{word}\t{freq}\to\n" if offensive else f"{word}\t{freq}\n")
    TEST_OUT.mkdir(parents=True, exist_ok=True)
    (TEST_OUT / f"{lang}.tsv").write_text(body.getvalue(), encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{lang}.kfd").write_bytes(binary(entries))
    print(f"{lang}: {len(entries)} palavras → {OUT / f'{lang}.kfd'} ({(OUT / f'{lang}.kfd').stat().st_size} bytes)")


def binary(entries: list) -> bytes:
    text = "".join(w for w, _, _ in entries).encode("latin-1")
    folded = "".join(fold(w) for w, _, _ in entries).encode("latin-1")
    n = len(entries)
    offensive = bytearray((n + 7) // 8)
    for i, (_, _, o) in enumerate(entries):
        if o:
            offensive[i // 8] |= 1 << (i % 8)
    return b"".join([
        b"KFD1",
        struct.pack(">III", n, len(text), len(folded)),
        text,
        folded,
        bytes(len(w) for w, _, _ in entries),
        bytes(len(fold(w)) for w, _, _ in entries),
        bytes(f for _, f, _ in entries),
        bytes(offensive),
    ])


if __name__ == "__main__":
    local = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None
    for lang, (name, sha) in SOURCES.items():
        build(lang, name, sha, local)

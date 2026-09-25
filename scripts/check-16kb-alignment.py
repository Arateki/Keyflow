#!/usr/bin/env python3
"""Confere se o código nativo de um APK está pronto para páginas de 16 KB (STO-02, requisito do Play).

Para cada lib/<abi>/*.so dentro do APK:
  1. todo segmento PT_LOAD do ELF tem p_align >= 16384;
  2. se o .so está guardado sem compressão (o padrão do AGP, que o carrega direto do APK), o início dos
     dados no zip está alinhado a 16384.

Uso: scripts/check-16kb-alignment.py app/build/outputs/apk/debug/app-debug.apk
Sai com 1 se algo falhar, listando o quê.
"""
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1


def load_alignments(elf: bytes):
    if elf[:4] != b"\x7fELF":
        raise ValueError("não é ELF")
    is64 = elf[4] == 2
    endian = "<" if elf[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", elf, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", elf, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x2A)
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", elf, base)
        if p_type != PT_LOAD:
            continue
        # p_align é o último campo do program header nos dois formatos.
        p_align, = struct.unpack_from(endian + ("Q" if is64 else "I"), elf, base + phentsize - (8 if is64 else 4))
        yield p_align


def data_offset(apk_path: str, info: zipfile.ZipInfo) -> int:
    with open(apk_path, "rb") as f:
        f.seek(info.header_offset)
        header = f.read(30)
        name_len, extra_len = struct.unpack_from("<HH", header, 26)
        return info.header_offset + 30 + name_len + extra_len


def main(apk_path: str) -> int:
    problems = []
    checked = 0
    with zipfile.ZipFile(apk_path) as apk:
        for info in apk.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            checked += 1
            aligns = list(load_alignments(apk.read(info)))
            bad = [a for a in aligns if a < PAGE]
            if bad:
                problems.append(f"{info.filename}: PT_LOAD com alinhamento {sorted(set(bad))} < {PAGE}")
            if info.compress_type == zipfile.ZIP_STORED:
                offset = data_offset(apk_path, info)
                if offset % PAGE:
                    problems.append(f"{info.filename}: guardado sem compressão no offset {offset}, não múltiplo de {PAGE}")
            print(f"ok? {'não' if bad else 'sim'}  {info.filename}  PT_LOAD={sorted(set(aligns))}  "
                  f"{'stored' if info.compress_type == zipfile.ZIP_STORED else 'deflated'}")
    if checked == 0:
        print("nenhum .so no APK — nada a conferir")
    for p in problems:
        print("FALHA: " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))

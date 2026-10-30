#!/usr/bin/env python3
"""Empacota apenas o JNI auditado do termlib e os avisos das partes incorporadas."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

RAIZ = Path(__file__).resolve().parent.parent
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
LICENCAS = {
    "termlib": "Apache-2.0",
    "libvterm": "MIT",
    "wcwidth": "permissiva-Markus-Kuhn",
    "libc++": "Apache-2.0 WITH LLVM-exception",
    "libc++abi": "Apache-2.0 WITH LLVM-exception",
    "libunwind": "Apache-2.0 WITH LLVM-exception",
    "compiler-rt": "Apache-2.0 WITH LLVM-exception",
}
PROIBIDA = re.compile(rb"GNU (?:GENERAL|LESSER GENERAL|AFFERO GENERAL) PUBLIC LICENSE|SPDX-License-Identifier:\s*(?:A?GPL|LGPL)-", re.I)


def empacotar(pasta, saida):
    modulo = RAIZ / "third_party/termlib"
    arquivos = {}
    spec = importlib.util.spec_from_file_location("alinhamento", RAIZ / "scripts/check-16kb-alignment.py")
    alinhamento = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(alinhamento)
    manifest = {"version": (modulo / "NATIVE_VERSION").read_text().strip(), "libraries": {}, "licenses": LICENCAS}
    fontes = hashlib.sha256()
    for p in sorted((modulo / "src/main/cpp").rglob("*")):
        if p.is_file():
            fontes.update(str(p.relative_to(modulo)).encode() + b"\0" + p.read_bytes())
    manifest["source_sha256"] = fontes.hexdigest()
    for abi in ABIS:
        p = pasta / abi / "libtermlib.so"
        data = p.read_bytes()
        segmentos = list(alinhamento.load_alignments(data))
        if not segmentos or any(n < 16384 for n in segmentos):
            raise ValueError(f"{abi}: ELF sem alinhamento de 16 KB: {segmentos}")
        if PROIBIDA.search(data):
            raise ValueError(f"{abi}: texto de licença não permitida")
        simbolos = subprocess.check_output(["readelf", "--dyn-syms", "--wide", str(p)], text=True)
        for metodo in ("nativeMouseMove", "nativeMouseButton"):
            if "Java_org_connectbot_terminal_TerminalNative_" + metodo not in simbolos:
                raise ValueError(f"{abi}: JNI do fork sem {metodo}")
        dinamicos = subprocess.check_output(["readelf", "-dW", str(p)], text=True)
        permitidas = {"libandroid.so", "liblog.so", "libc.so", "libm.so", "libdl.so"}
        necessarias = set(re.findall(r"Shared library: \[(.*?)\]", dinamicos))
        if necessarias - permitidas:
            raise ValueError(f"{abi}: biblioteca dinâmica não auditada: {necessarias - permitidas}")
        nome = f"jni/{abi}/libtermlib.so"
        arquivos[nome] = data
        manifest["libraries"][nome] = hashlib.sha256(data).hexdigest()
    arquivos["licenses/Apache-2.0_termlib.txt"] = (modulo / "LICENSE").read_bytes()
    arquivos["licenses/MIT_libvterm.txt"] = (modulo / "src/main/cpp/libvterm/LICENSE").read_bytes()
    for p in sorted((modulo / "licenses").glob("*.txt")):
        arquivos["licenses/" + p.name] = p.read_bytes()
    for nome, data in arquivos.items():
        if nome.startswith("licenses/") and PROIBIDA.search(data):
            raise ValueError(f"{nome}: licença não permitida")
    arquivos["manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    saida.mkdir(parents=True, exist_ok=True)
    destino = saida / f"termlib-jni-{manifest['version']}.zip"
    # Metadados fixos: a mesma entrada gera o mesmo ZIP, sem timestamps do runner.
    with zipfile.ZipFile(destino, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipado:
        for nome, data in sorted(arquivos.items()):
            info = zipfile.ZipInfo(nome, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zipado.writestr(info, data)
    digest = hashlib.sha256(destino.read_bytes()).hexdigest()
    destino.with_suffix(".zip.sha256").write_text(f"{digest}  {destino.name}\n")
    (saida / "manifest.json").write_bytes(arquivos["manifest.json"])
    print(digest, destino.name)


if __name__ == "__main__":
    empacotar(Path(sys.argv[1]), Path(sys.argv[2]))

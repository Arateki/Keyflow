#!/usr/bin/env python3
"""Recusa classes do agente no DEX do release, mesmo renomeadas pelo R8.

Uso: check-release-agent.py <mapping.txt> <APK> [<dexdump>]. O mapping é obrigatório.
A conferência usa definições de classes, não referências ou strings soltas no DEX.
"""
import re
import runpy
import sys
from pathlib import Path

PREFIXES = ("com.keyflow.agent.", "com.keyflow.agenthost.")
MAPPING_LINE = re.compile(r"^(\S+) -> (\S+):$")


def check_agent(mapping_path, descriptors):
    mapping = Path(mapping_path)
    if not mapping.is_file() or mapping.stat().st_size == 0:
        return ["mapping R8 obrigatório ausente ou vazio"]
    pairs = [match.groups() for line in mapping.read_text(encoding="utf-8").splitlines()
             if (match := MAPPING_LINE.fullmatch(line))]
    if not pairs:
        return ["mapping R8 sem classes"]
    problems = []
    for original, target in pairs:
        if original.startswith(PREFIXES) and "L" + target.replace(".", "/") + ";" in descriptors:
            problems.append(f"agente no DEX: {original} -> {target}")
    # Também recusa classes originais que um mapping incorreto/recortado tenha omitido.
    for descriptor in descriptors:
        if descriptor[1:-1].replace("/", ".").startswith(PREFIXES):
            problems.append(f"agente no DEX: {descriptor}")
    if not descriptors:
        problems.append("APK sem definições de classes DEX")
    return problems


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    mapping, apk = sys.argv[1:3]
    if not Path(mapping).is_file() or Path(mapping).stat().st_size == 0:
        sys.exit("mapping R8 obrigatório ausente ou vazio")
    jni = runpy.run_path(str(Path(__file__).with_name("check-release-jni.py")))
    dexdump = jni["find_dexdump"](sys.argv[3] if len(sys.argv) == 4 else None)
    if not dexdump:
        sys.exit("dexdump não encontrado")
    descriptors = jni["dex_members"](apk, dexdump)
    problems = check_agent(mapping, descriptors)
    if problems:
        sys.exit("\n".join(problems))
    print("R8: nenhum com.keyflow.agent.* ou com.keyflow.agenthost.* definido no DEX (mapping conferido).")


if __name__ == "__main__":
    main()

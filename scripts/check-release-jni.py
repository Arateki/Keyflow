#!/usr/bin/env python3
"""Confere se o R8 deixou intacto o que código nativo e reflexão acessam por nome no APK de release (V1-3).

Uso: scripts/check-release-jni.py <mapping.txt> <app-release.apk> [<dexdump>]

1. No mapping.txt do R8, toda classe do sherpa-onnx e do LiteRT-LM (AARs sem regras de consumidor, com .so que
   resolve classes, campos e métodos por nome) tem de continuar com o nome original.
2. No DEX do APK, os membros que SÓ o código nativo usa (os callbacks `onMessage`/`onDone`/`onError`, os getters das
   configurações) têm de existir: nenhum código Java os chama, então sem a keep rule o R8 os apaga e o nativo morre com
   NoSuchMethodError. Foi o que o primeiro release minificado fazia com o LiteRT-LM (mapping de 2026-10-24).

Sai com 1 se algo falhar, listando o quê. O dexdump vem do argumento, de $DEXDUMP ou de $ANDROID_HOME/build-tools/*.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

# Pacotes em que TODA classe tem de manter o nome (a keep rule é do pacote inteiro).
IDENTITY_PREFIXES = ("com.k2fsa.sherpa.onnx.", "com.google.ai.edge.litertlm.")

# Membros que o nativo chama/lê por nome. Descritor DEX da classe -> métodos que têm de existir.
REQUIRED_MEMBERS = {
    "Lcom/google/ai/edge/litertlm/LiteRtLmJni$JniMessageCallback;": ["onMessage", "onDone", "onError"],
    "Lcom/google/ai/edge/litertlm/LiteRtLmJni$JniInferenceCallback;": ["onNext", "onDone", "onError"],
    "Lcom/google/ai/edge/litertlm/SamplerConfig;": ["getTopK", "getTopP", "getTemperature", "getSeed"],
    "Lcom/google/ai/edge/litertlm/InputData$Text;": ["getText"],
    "Lcom/google/ai/edge/litertlm/BenchmarkInfo;": [
        "getInitTimeInSecond", "getTimeToFirstTokenInSecond", "getLastPrefillTokenCount",
        "getLastDecodeTokenCount", "getLastPrefillTokensPerSecond", "getLastDecodeTokensPerSecond",
    ],
    "Lcom/google/ai/edge/litertlm/LiteRtLmJniException;": ["<init>"],
    # AIDL dos processos :inference e :reasoning: o Stub trata as transações.
    "Lcom/keyflow/transcription/local/ITranscriptionEngine$Stub;": ["onTransact", "asInterface"],
    "Lcom/keyflow/reasoning/local/IReasoningService$Stub;": ["onTransact", "asInterface"],
}

MAPPING_LINE = re.compile(r"^(\S+) -> (\S+):$")


def check_mapping(mapping_path):
    problems, seen = [], {prefix: 0 for prefix in IDENTITY_PREFIXES}
    with open(mapping_path, encoding="utf-8", errors="replace") as mapping:
        for line in mapping:
            m = MAPPING_LINE.match(line.rstrip("\n"))
            if not m:
                continue
            original, target = m.groups()
            if "$$ExternalSynthetic" in original:
                continue  # classe sintética que o próprio D8/R8 cria (lambda desaçucarada); nenhum nativo a conhece
            for prefix in IDENTITY_PREFIXES:
                if original.startswith(prefix):
                    seen[prefix] += 1
                    if target != original:
                        problems.append(f"mapping: {original} virou {target}")
    for prefix, count in seen.items():
        if count == 0:
            problems.append(f"mapping: nenhuma classe {prefix}* encontrada (mapping errado?)")
    return problems, seen


def find_dexdump(arg):
    if arg:
        return arg
    if os.environ.get("DEXDUMP"):
        return os.environ["DEXDUMP"]
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/srv/dev/ferramentas/android-sdk"
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", "dexdump")))
    return found[-1] if found else None


def dex_members(apk_path, dexdump):
    """descritor da classe -> conjunto de nomes de métodos e campos, lido de todos os classes*.dex."""
    members = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk_path) as apk:
        for entry in apk.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            path = os.path.join(tmp, entry)
            with open(path, "wb") as out:
                out.write(apk.read(entry))
            dump = subprocess.run([dexdump, "-f", path], capture_output=True, text=True, errors="replace", check=True).stdout
            current = None
            for line in dump.splitlines():
                m = re.match(r"\s+Class descriptor\s+: '(.*)'", line)
                if m:
                    current = members.setdefault(m.group(1), set())
                    continue
                m = re.match(r"\s+name\s+: '(.*)'", line)
                if m and current is not None:
                    current.add(m.group(1))
    return members


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mapping_path, apk_path = sys.argv[1], sys.argv[2]
    dexdump = find_dexdump(sys.argv[3] if len(sys.argv) > 3 else None)
    if not dexdump:
        sys.exit("dexdump não encontrado (argumento, $DEXDUMP ou $ANDROID_HOME/build-tools)")

    problems, seen = check_mapping(mapping_path)
    members = dex_members(apk_path, dexdump)
    for descriptor, required in REQUIRED_MEMBERS.items():
        present = members.get(descriptor)
        if present is None:
            problems.append(f"dex: classe {descriptor} ausente ou renomeada")
            continue
        for name in required:
            if name not in present:
                problems.append(f"dex: {descriptor} perdeu o membro {name}")

    if problems:
        print("\n".join(problems), file=sys.stderr)
        sys.exit(1)
    counts = ", ".join(f"{count} classes {prefix}*" for prefix, count in seen.items())
    print(f"R8 manteve o que o JNI acessa: {counts} com o nome original e {len(REQUIRED_MEMBERS)} classes com os membros chamados pelo nativo.")


if __name__ == "__main__":
    main()

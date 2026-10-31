#!/usr/bin/env python3
"""Confere que o APK debug leva as três ABIs do JNI exatamente como aprovado no CI."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verificar(apk):
    raiz = Path(__file__).resolve().parent.parent
    manifest = json.loads((raiz / 'third_party/termlib/native-manifest.json').read_text())
    esperados = {nome.replace('jni/', 'lib/', 1): sha for nome, sha in manifest['libraries'].items()}
    with zipfile.ZipFile(apk) as zipado:
        encontrados = {n for n in zipado.namelist() if n.endswith('/libtermlib.so')}
        if encontrados != set(esperados):
            raise ValueError(f'ABIs do APK divergentes: {sorted(encontrados)}')
        for nome, esperado in esperados.items():
            atual = hashlib.sha256(zipado.read(nome)).hexdigest()
            if atual != esperado:
                raise ValueError(f'{nome}: SHA-256 difere do binário aprovado')
            print(f'{nome}: SHA-256 aprovado')


if __name__ == '__main__':
    verificar(sys.argv[1])

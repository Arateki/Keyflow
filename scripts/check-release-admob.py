#!/usr/bin/env python3
"""Recusa APK release com o publisher de teste do Google em qualquer entrada."""
import sys
import zipfile

publisher = "3940256099942544"
patterns = [publisher.encode(encoding) for encoding in ("utf-8", "utf-16le", "utf-16be")]
with zipfile.ZipFile(sys.argv[1]) as apk:
    for entry in apk.infolist():
        if entry.is_dir():
            continue
        data = apk.read(entry)
        if any(pattern in data for pattern in patterns):
            sys.exit(f"Release contém publisher AdMob de teste em {entry.filename}")
print("Release sem publisher AdMob de teste em todas as entradas do APK.")

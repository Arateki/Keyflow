#!/usr/bin/env python3
"""Recusa erros de R8/JNI e crashes dos processos do APK, preservando o log completo separado."""
import re
import sys
from pathlib import Path


def main() -> int:
    log, package, initial_pids = sys.argv[1:]
    lines = Path(log).read_text(errors="replace").splitlines()
    pids = set(initial_pids.split())
    owner = re.compile(r"^[VDIWEF]/[^\n(]+\(\s*(\d+)\)")
    start = re.compile(r"Start proc (\d+):" + re.escape(package) + r"(?=[:/])")
    process = re.compile(r"(?:Cmdline|Process): " + re.escape(package) + r"(?=[:,\s]|$)")
    for line in lines:
        match = start.search(line)
        if match:
            pids.add(match[1])
        if process.search(line) and (match := owner.search(line)):
            pids.add(match[1])
    if not pids:
        print("não há PID do APK para conferir o log", file=sys.stderr)
        return 2
    errors = re.compile(
        r"FAILURES!!!|FATAL EXCEPTION|Fatal signal|ClassNotFoundException|NoClassDefFoundError|"
        r"Could not find class|NoSuch(Method|Field)Error|JNI DETECTED ERROR|BadParcelableException|"
        r"Parcel:.*(exception|error)"
    )
    failed = False
    for number, line in enumerate(lines, 1):
        match = owner.search(line)
        if match and match[1] in pids and errors.search(line):
            print(f"{number}:{line}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

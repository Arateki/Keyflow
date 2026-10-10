#!/usr/bin/env python3
"""Mede de FORA do app onde a janela do teclado fica ao abrir — sem nada de teste no processo do KeyFlow.

    python3 scripts/ime-placement.py --ime keyflow|gboard [--probe remoto|local] [--openings 12] [--seconds 4] [--cold]

Mesma comparação do ImeWindowPlacementTest (sistema de entrada × gerenciador de janelas, `dumpsys`), mas pelo adb, do
computador. Lá a medição rodava dentro do processo com.keyflow — o mesmo do teclado do KeyFlow e do UiAutomator —, e
competia com a thread principal do KeyFlow e não com a do Gboard (esteira de instabilidade, 2026-10-10).

Abertura deslocada = 2 leituras seguidas com a janela visível na tela e diferente do gerenciador de janelas. Sai com 1 se
alguma abertura deslocou. Usa o aparelho do ANDROID_SERIAL.

--cold encerra o processo do teclado antes de cada abertura: toda abertura começa a frio, como a 1ª depois de instalar
ou de o Android matar o processo. É a comparação justa — sem isso o Gboard está sempre quente e o KeyFlow, recém-instalado.
"""
import argparse
import re
import subprocess
import sys
import time

PACKAGES = {"keyflow": "com.keyflow", "gboard": "com.google.android.inputmethod.latin"}
IMES = {
    "keyflow": "com.keyflow/.ime.KeyFlowKeyboardService",
    "gboard": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
}
PROBES = {"remoto": "com.keyflow/.ui.ImeProbeRemoteActivity", "local": "com.keyflow/.ui.ImeProbeActivity"}
FRAME = re.compile(r"frame=(\[[^\]]*\]\[[^\]]*\])")
# Uma chamada ao aparelho por leitura: a linha da janela do IME no InputDispatcher e o bloco dela no WindowManager.
SAMPLE = (
    "dumpsys input | grep -m1 \" InputMethod'\"; echo ---; "
    "dumpsys window windows | grep -A30 'InputMethod}:' | grep -E 'Frames:|isVisible='"
)


def adb(*args, check=False):
    return subprocess.run(["adb", "shell", *args], capture_output=True, text=True, check=check).stdout


def sample():
    out = adb(SAMPLE)
    input_part, _, wm_part = out.partition("---")
    seen = FRAME.search(input_part)
    visible = "isVisible=true" in wm_part
    frames = [line for line in wm_part.splitlines() if "Frames:" in line]
    wm = FRAME.search(frames[0]) if frames else None
    return (seen.group(1) if seen else None), (wm.group(1) if wm and visible else None)


def top(frame):
    return int(frame.split(",")[1].split("]")[0])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ime", choices=IMES, required=True)
    parser.add_argument("--probe", choices=PROBES, default="remoto")
    parser.add_argument("--openings", type=int, default=12)
    parser.add_argument("--seconds", type=float, default=4.0)
    parser.add_argument("--cold", action="store_true")
    args = parser.parse_args()
    ime = IMES[args.ime]
    height = int(adb("wm", "size").strip().split("x")[-1])

    adb("ime", "enable", ime)
    adb("ime", "set", ime)
    displaced, total = [], 0
    for opening in range(1, args.openings + 1):
        adb("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1.5)
        if args.cold:
            # O probe é do pacote do KeyFlow: com o KeyFlow, encerrar o pacote leva o probe junto (ele reabre abaixo).
            adb("am", "force-stop", PACKAGES[args.ime])
            if args.ime == "keyflow":
                adb("ime", "set", ime)  # force-stop tira o KeyFlow de teclado ativo
            time.sleep(1.0)
        adb("am", "start", "-n", PROBES[args.probe], "-f", "0x10008000")
        time.sleep(2.0)
        adb("input", "tap", "540", "283")
        streak, bad, end = 0, [], time.time() + args.seconds
        while time.time() < end:
            seen, wm = sample()
            if not seen or not wm:
                continue
            total += 1
            if seen != wm and top(seen) < height:
                bad.append(f"entrada={seen} wm={wm}")
                streak += 1
                if streak == 2:
                    displaced.append(f"abertura {opening}: {bad[-1]}")
            else:
                streak = 0
        print(f"abertura {opening}/{args.openings}: {len(bad)} leituras fora do lugar", flush=True)
    print(f"{args.ime} ({args.probe}{', a frio' if args.cold else ''}): {len(displaced)} de {args.openings} aberturas deslocadas, {total} leituras — {displaced}")
    return 1 if displaced else 0


if __name__ == "__main__":
    sys.exit(main())

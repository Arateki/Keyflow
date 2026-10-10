#!/usr/bin/env python3
"""Lê a saída de `am instrument -r` pela entrada padrão e decide o gate de device do CI.

Uso (scripts/ci-device-gate.sh):

    adb shell am instrument -w -r ... | tee bruto.txt | python3 -u scripts/ci-instrument-summary.py --label "shard 0 de 4"

Imprime uma linha por teste terminado, com a duração, e no fim o placar. Sai com código != 0 se algum
teste falhou OU se a rodada não terminou. O `am instrument` sai com 0 nos dois casos — quando o processo
do app cai no meio, ele só escreve `shortMsg=Process crashed.` e os testes que faltavam somem do placar —,
então o código de saída dele não serve de gate.

Cada falha vira também uma anotação `::error::` do GitHub Actions (aparece no resumo do PR).

O gate repete uma vez o que não passou (ci-device-gate.sh). Na 1ª tentativa (`--retry-follows`) não há
anotação: `--passed-out` grava o que terminou sem falha — o `notTestFile` da repetição — e `--failed-out` o
que falhou ou estava rodando quando o processo caiu. Na repetição, `--earlier-failures` recebe esse último
arquivo: quem falhou antes e passou agora vira aviso de teste instável; quem falhou de novo, erro.
"""
import argparse
import os
import sys
import time

# InstrumentationResultPrinter (androidx.test): códigos de INSTRUMENTATION_STATUS_CODE.
START, OK, ERROR, FAILURE, IGNORED, ASSUMPTION_FAILURE = 1, 0, -1, -2, -3, -4
STATUS = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
RESULT = "INSTRUMENTATION_RESULT: "
CODE = "INSTRUMENTATION_CODE: "
# Activity.RESULT_OK: a instrumentação terminou sozinha.
RUN_OK = -1


def annotation(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotation_property(text):
    return annotation(text).replace(":", "%3A").replace(",", "%2C")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", default="instrumentados")
    parser.add_argument("--retry-follows", action="store_true")
    parser.add_argument("--passed-out")
    parser.add_argument("--failed-out")
    parser.add_argument("--earlier-failures")
    args = parser.parse_args()

    status, result = {}, {}
    current = None  # dicionário e chave do valor que pode continuar nas linhas seguintes
    started = {}  # nome do teste → instante do início
    finished = {}  # nome do teste → código final
    failures = []  # (nome, stack)
    numtests = None
    run_code = None

    for raw in sys.stdin:
        line = raw.rstrip("\r\n")
        if line.startswith(STATUS):
            key, _, value = line[len(STATUS):].partition("=")
            status[key] = value
            current = (status, key)
        elif line.startswith(STATUS_CODE):
            code = int(line[len(STATUS_CODE):].strip())
            name = f"{status.get('class', '?')}#{status.get('test', '?')}"
            if status.get("numtests", "").isdigit():
                numtests = int(status["numtests"])
            if code == START:
                started[name] = time.monotonic()
            else:
                finished[name] = code
                elapsed = time.monotonic() - started.get(name, time.monotonic())
                mark = {OK: "✓", IGNORED: "-", ASSUMPTION_FAILURE: "-"}.get(code, "✗")
                print(f"  {mark} {name}  {elapsed:.1f} s", flush=True)
                if code in (FAILURE, ERROR):
                    stack = status.get("stack", "").strip()
                    failures.append((name, stack))
                    print("\n".join("      " + s for s in stack.splitlines()[:12]), flush=True)
            status, current = {}, None
        elif line.startswith(RESULT):
            key, _, value = line[len(RESULT):].partition("=")
            result[key] = value
            current = (result, key)
        elif line.startswith(CODE):
            try:
                run_code = int(line[len(CODE):].strip())
            except ValueError:
                run_code = None
            current = None
        elif current is not None:
            target, key = current
            target[key] += "\n" + line

    passed = sum(1 for c in finished.values() if c == OK)
    skipped = sum(1 for c in finished.values() if c in (IGNORED, ASSUMPTION_FAILURE))
    problems = []
    if run_code != RUN_OK:
        problems.append(f"a instrumentação não terminou normalmente (INSTRUMENTATION_CODE: {run_code})")
    if "shortMsg" in result:
        problems.append(f"o processo caiu: {result['shortMsg'].strip()}")
    if numtests is not None and len(finished) < numtests:
        problems.append(f"só {len(finished)} de {numtests} testes chegaram ao fim")
    unfinished = [n for n in started if n not in finished]
    if unfinished:
        problems.append(f"teste começou e não terminou: {unfinished[-1]}")
    if numtests is None and not finished:
        problems.append("nenhum teste rodou")

    if args.passed_out:
        # Sem linha vazia: o runner lê linha vazia como o pacote "" e excluiria a suíte inteira.
        done = [n for n, c in finished.items() if c in (OK, IGNORED, ASSUMPTION_FAILURE)]
        with open(args.passed_out, "w", encoding="utf-8") as out:
            out.writelines(f"{n}\n" for n in done)
    if args.failed_out:
        with open(args.failed_out, "w", encoding="utf-8") as out:
            out.writelines(f"{n}\n" for n in dict.fromkeys([n for n, _ in failures] + unfinished))
    flaky = []
    if args.earlier_failures:
        with open(args.earlier_failures, encoding="utf-8") as earlier:
            flaky = [n for n in (line.strip() for line in earlier) if finished.get(n) == OK]

    total = numtests if numtests is not None else len(finished)
    score = f"{total} testes — passaram: {passed}, falharam: {len(failures)}, pulados: {skipped}"
    print(f"\n{args.label}: {score}", flush=True)
    if not args.retry_follows:
        for name, stack in failures:
            first = "\n".join(stack.splitlines()[:3])
            print(f"::error title={annotation_property(name)}::{annotation(first)}", flush=True)
        for problem in problems:
            print(f"::error title={annotation_property(args.label)}::{annotation(problem)}", flush=True)
    for name in flaky:
        print(f"::warning title={annotation_property(name)}::instável: falhou na 1ª tentativa e passou na repetição",
              flush=True)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"### {args.label}\n\n{score}\n\n")
            for name, _ in failures:
                out.write(f"- ✗ `{name}`\n")
            for problem in problems:
                out.write(f"- ⚠ {problem}\n")
            for name in flaky:
                out.write(f"- ⚠ instável (passou na repetição): `{name}`\n")
            if args.retry_follows and (failures or problems):
                out.write("\nO que não passou é repetido uma vez a seguir.\n")

    return 1 if failures or problems else 0


if __name__ == "__main__":
    sys.exit(main())

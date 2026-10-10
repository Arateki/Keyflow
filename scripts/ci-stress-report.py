#!/usr/bin/env python3
"""Soma as voltas da esteira de instabilidade (scripts/ci-stress.sh) de todos os emuladores.

Uso (.github/workflows/stress.yml, job do relatório):

    python3 scripts/ci-stress-report.py <pasta com um subdiretório por artefato stress-evidence-*>

Para cada teste que falhou ao menos uma vez: quantas execuções, quantas falhas, em que condição de CPU e
onde está a evidência (artefato/volta). Escreve no resumo do job e sai com 1 se algum teste falhou — a
rodada fica vermelha quando a esteira achou alguma coisa.
"""
import os
import pathlib
import sys
from collections import defaultdict


def read_lines(path):
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] if path.exists() else []


def first_error(summary, name):
    """A 1ª linha da pilha que o ci-instrument-summary.py imprime logo abaixo do ✗ do teste."""
    lines = summary.read_text(encoding="utf-8").splitlines() if summary.exists() else []
    for i, line in enumerate(lines):
        if line.lstrip().startswith("✗ ") and f" {name} " in f"{line} ":
            return lines[i + 1].strip() if i + 1 < len(lines) else ""
    return ""


def main():
    root = pathlib.Path(sys.argv[1])
    runs = defaultdict(lambda: defaultdict(int))  # teste → condição → execuções
    fails = defaultdict(lambda: defaultdict(int))  # teste → condição → falhas
    where = defaultdict(list)  # teste → [(artefato, volta, 1ª linha do erro)]
    workers = []
    for artifact in sorted(p for p in root.iterdir() if p.is_dir()):
        meta = read_lines(artifact / "meta.txt")
        cpu = next((m.split(": ", 1)[1] for m in meta if m.startswith("cpu do emulador")), "?")
        workers.append((artifact.name, meta))
        for round_dir in sorted(artifact.glob("volta-*")):
            for name in read_lines(round_dir / "passed.txt"):
                runs[name][cpu] += 1
            for name in read_lines(round_dir / "failed.txt"):
                runs[name][cpu] += 1
                fails[name][cpu] += 1
                where[name].append((artifact.name, round_dir.name, first_error(round_dir / "summary.txt", name)))

    total_runs = sum(sum(c.values()) for c in runs.values())
    conditions = sorted({cpu for c in runs.values() for cpu in c})
    out = ["## Esteira de instabilidade", ""]
    out.append(f"{len(workers)} emuladores, {len(runs)} testes, {total_runs} execuções no total.")
    out.append("")
    if fails:
        out.append("| Teste | " + " | ".join(f"falhas / execuções (CPU {c})" for c in conditions) + " |")
        out.append("|---|" + "---|" * len(conditions))
        for name in sorted(fails, key=lambda n: -sum(fails[n].values())):
            cells = [f"{fails[name][c]} / {runs[name][c]}" for c in conditions]
            out.append(f"| `{name.split('.')[-1]}` | " + " | ".join(cells) + " |")
        out.append("")
        out.append("Evidência (artefato → volta: 1ª linha do erro):")
        out.append("")
        for name in sorted(fails, key=lambda n: -sum(fails[n].values())):
            out.append(f"- `{name.split('.')[-1]}`")
            for artifact, round_name, error in where[name][:10]:
                out.append(f"  - {artifact} → {round_name}: {error}")
    else:
        out.append("Nenhuma falha.")
    out.append("")
    out.append("| Emulador | Voltas, CPU e quadros |")
    out.append("|---|---|")
    for name, meta in workers:
        out.append(f"| {name} | {'; '.join(meta) or 'sem meta.txt (o emulador não chegou ao fim)'} |")

    text = "\n".join(out) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)
    for name in fails:
        count = sum(fails[name].values())
        print(f"::warning title={name.split('.')[-1]}::falhou {count} de {sum(runs[name].values())} execuções na esteira")
    return 1 if fails or not workers else 0


if __name__ == "__main__":
    sys.exit(main())

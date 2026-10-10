#!/usr/bin/env python3
"""Resume o relatório do teste de mutação (PIT) por classe e lista os mutantes que sobreviveram.

    python3 scripts/mutation-summary.py app/build/reports/pitest/mutations.xml

Escreve Markdown na saída e, no GitHub Actions, no resumo do job. Força = mutantes detectados entre os que algum teste
executa (os "sem cobertura" ficam de fora: não há teste para errar). Um sobrevivente é uma mudança no código que nenhum
teste percebe — às vezes equivalente (não muda o comportamento), quase sempre uma regra que falta conferir.
"""
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}
MAX_SURVIVORS = 80


def main():
    root = ET.parse(sys.argv[1]).getroot()
    per_class = defaultdict(lambda: defaultdict(int))
    survivors = []
    for m in root.findall("mutation"):
        status = m.get("status")
        cls = m.findtext("mutatedClass").split("$")[0]
        per_class[cls][status] += 1
        if status == "SURVIVED":
            survivors.append((cls, m.findtext("sourceFile"), int(m.findtext("lineNumber")), m.findtext("mutatedMethod"), m.findtext("description")))

    def strength(c):
        covered = sum(v for k, v in c.items() if k != "NO_COVERAGE")
        detected = sum(c[k] for k in DETECTED)
        return detected, covered

    total = defaultdict(int)
    for c in per_class.values():
        for k, v in c.items():
            total[k] += v
    detected, covered = strength(total)
    out = [
        "## Teste de mutação (PIT)",
        "",
        f"**Força: {100 * detected / covered:.1f}%** — {detected} de {covered} mutantes cobertos foram detectados "
        f"({total['SURVIVED']} sobreviveram, {total['NO_COVERAGE']} sem cobertura).",
        "",
        "| Classe | Mutantes | Detectados | Sobreviveram | Sem cobertura | Força |",
        "|---|---|---|---|---|---|",
    ]
    rows = []
    for cls, c in per_class.items():
        d, cov = strength(c)
        rows.append((d / cov if cov else 1.0, cls, sum(c.values()), d, c["SURVIVED"], c["NO_COVERAGE"]))
    for ratio, cls, n, d, s, nc in sorted(rows):
        out.append(f"| `{cls.removeprefix('com.keyflow.')}` | {n} | {d} | {s} | {nc} | {100 * ratio:.0f}% |")
    if survivors:
        out += ["", f"### Sobreviventes ({len(survivors)}{', os primeiros ' + str(MAX_SURVIVORS) if len(survivors) > MAX_SURVIVORS else ''})", ""]
        for cls, source, line, method, description in sorted(survivors)[:MAX_SURVIVORS]:
            package = cls.rsplit(".", 1)[0].removeprefix("com.keyflow.").replace(".", "/")
            out.append(f"- `{package}/{source}:{line}` `{method}` — {description}")
    text = "\n".join(out) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Resume o relatório de cobertura do JaCoCo (testes de unidade) por pacote, do menos coberto ao mais.

    python3 scripts/coverage-summary.py app/build/reports/coverage/test/debug/report.xml

Escreve uma tabela Markdown na saída e, no GitHub Actions, no resumo do job. É mapa do que nenhum teste de unidade toca —
o que roda só nos instrumentados (telas, teclado, serviço) aparece baixo aqui de propósito; não é meta de número.
"""
import os
import sys
import xml.etree.ElementTree as ET


def counter(node, kind):
    for c in node.findall("counter"):
        if c.get("type") == kind:
            covered, missed = int(c.get("covered")), int(c.get("missed"))
            return covered, covered + missed
    return 0, 0


def main():
    root = ET.parse(sys.argv[1]).getroot()
    rows = []
    for package in root.findall("package"):
        covered, total = counter(package, "LINE")
        if total:
            rows.append((covered / total, package.get("name").replace("/", "."), covered, total))
    covered, total = counter(root, "LINE")
    out = [
        "## Cobertura dos testes de unidade (linhas)",
        "",
        f"**Total: {100 * covered / total:.1f}%** ({covered} de {total} linhas). Mapa, não meta: telas, teclado e serviços "
        "são testados pelos instrumentados, que não entram aqui.",
        "",
        "| Pacote | Linhas cobertas | % |",
        "|---|---|---|",
    ]
    for ratio, name, c, t in sorted(rows):
        out.append(f"| `{name}` | {c} / {t} | {100 * ratio:.0f}% |")
    text = "\n".join(out) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Validação das entradas e resumo da execução CI sob demanda."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

TAREFAS = {
    "assembleDebug", "assembleDebugAndroidTest", "testDebugUnitTest",
    ":app:lintDebug", "lintDebug", "check",
}
CLASSE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.$]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?$")
ROTULO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")


def _lista(valor: str, nome: str) -> list[str]:
    if not valor:
        return []
    itens = valor.split(",")
    if any(not item for item in itens) or len(itens) != len(set(itens)):
        raise ValueError(f"{nome}: itens vazios ou repetidos")
    return itens


def resolver_classes(valor: str, raiz: Path) -> list[str]:
    classes = _lista(valor, "classes")
    if not classes:
        return []
    raiz_testes = raiz / "app/src/androidTest"
    arquivos = list(raiz_testes.rglob("*.kt")) + list(raiz_testes.rglob("*.java"))
    resolvidas = []
    for alvo in classes:
        if not CLASSE.fullmatch(alvo):
            raise ValueError("classe/método fora do formato permitido")
        nome, marcador, metodo = alvo.partition("#")
        simples = nome.rsplit(".", 1)[-1]
        declarada = simples.split("$", 1)[0]
        pacote_esperado = nome.rpartition(".")[0] if "." in nome else None
        achados = []
        for arquivo in arquivos:
            texto = arquivo.read_text(encoding="utf-8", errors="replace")
            pacote = re.search(r"(?m)^\s*package\s+([A-Za-z0-9_.]+)", texto)
            if pacote_esperado is not None and (not pacote or pacote.group(1) != pacote_esperado):
                continue
            if re.search(rf"(?m)^\s*(?:public\s+|internal\s+|private\s+|protected\s+)*(?:abstract\s+|final\s+|open\s+)*(?:class|object|interface|enum\s+class)\s+{re.escape(declarada)}\b", texto):
                achados.append((pacote.group(1) if pacote else "", declarada))
        if len(achados) != 1:
            motivo = "ambígua" if achados else "não encontrada"
            raise ValueError(f"classe {motivo} em app/src/androidTest; use o nome completo se houver ambiguidade")
        pacote, _ = achados[0]
        fqcn = f"{pacote}.{simples}" if pacote else simples
        resolvidas.append(f"{fqcn}#{metodo}" if marcador else fqcn)
    return resolvidas


def validar(tarefas: str, classes: str, pedacos: str, repeticoes: str,
            rotulo: str, ref: str, raiz: Path) -> dict:
    if not ref.startswith("refs/heads/equipe/"):
        raise ValueError("workflow aceito somente em branches equipe/*")
    gradle = re.split(r"[,\s]+", tarefas.strip()) if tarefas.strip() else []
    filtros = [t for t in gradle if t.startswith("--tests=")]
    if any(not re.fullmatch(r"--tests=[A-Za-z_][A-Za-z0-9_.$*]*", t) for t in filtros):
        raise ValueError("filtro de unitário inválido; use --tests=Classe ou --tests=pacote.Classe")
    if filtros and "testDebugUnitTest" not in gradle:
        raise ValueError("--tests exige testDebugUnitTest")
    ultima_tarefa = None
    for tarefa in gradle:
        if tarefa.startswith("--tests="):
            if ultima_tarefa != "testDebugUnitTest":
                raise ValueError("--tests deve vir logo depois de testDebugUnitTest ou de seu filtro anterior")
        else:
            ultima_tarefa = tarefa
    proibidas = sorted(set(gradle) - TAREFAS - set(filtros))
    if proibidas:
        raise ValueError("tarefas Gradle fora da allowlist; consulte a lista em TESTING.md")
    if not gradle and not classes:
        raise ValueError("informe tarefas Gradle ou classes instrumentadas")
    if not ROTULO.fullmatch(rotulo):
        raise ValueError("rótulo deve ter 1–32 caracteres: letras, números, ponto, sublinhado ou hífen")
    try:
        n_pedacos, n_repeticoes = int(pedacos), int(repeticoes)
    except ValueError as exc:
        raise ValueError("pedacos e repeticoes precisam ser números inteiros") from exc
    if not 1 <= n_pedacos <= 6:
        raise ValueError("pedacos deve ficar entre 1 e 6")
    if not 1 <= n_repeticoes <= 20:
        raise ValueError("repeticoes deve ficar entre 1 e 20")
    resolvidas = resolver_classes(classes, raiz)
    if not resolvidas and (n_pedacos != 1 or n_repeticoes != 1):
        raise ValueError("pedacos e repeticoes só se aplicam quando há classes instrumentadas")
    return {
        "gradle_tasks": ",".join(gradle), "classes": ",".join(resolvidas),
        "pedacos": n_pedacos, "repeticoes": n_repeticoes, "rotulo": rotulo,
        "has_gradle": bool(gradle), "has_device": bool(resolvidas),
        "shards": json.dumps(list(range(n_pedacos)), separators=(",", ":")),
    }


def ler_log(path: Path) -> dict:
    testes: dict[str, int | None] = {}
    status: dict[str, str] = {}
    atual = None
    codigo_final = None
    quantidade = 0
    problemas = []
    erros: dict[str, str] = {}
    for linha in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if linha.startswith("INSTRUMENTATION_STATUS: "):
            chave, _, valor = linha.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            status[chave] = valor
            atual = chave
        elif linha.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                codigo = int(linha.rsplit(" ", 1)[1])
            except ValueError:
                problemas.append("código de status instrumentado inválido")
                status, atual = {}, None
                continue
            nome = f"{status.get('class', '?')}#{status.get('test', '?')}"
            if status.get("numtests", "").isdigit():
                quantidade = max(quantidade, int(status["numtests"]))
            if codigo == 1:
                testes.setdefault(nome, None)
            else:
                testes[nome] = codigo
                if codigo in (-1, -2):
                    erros[nome] = "\n".join(status.get("stack", "falha sem stack trace").strip().splitlines()[:6])
            status, atual = {}, None
        elif linha.startswith("INSTRUMENTATION_CODE: "):
            try:
                codigo_final = int(linha.rsplit(" ", 1)[1])
            except ValueError:
                codigo_final = None
        elif linha.startswith("INSTRUMENTATION_RESULT: shortMsg="):
            problemas.append("processo caiu: " + linha.partition("=")[2])
        elif atual:
            status[atual] = status.get(atual, "") + "\n" + linha
    if codigo_final != -1:
        problemas.append(f"instrumentação incompleta (código {codigo_final})")
    terminados = sum(codigo is not None for codigo in testes.values())
    if terminados < quantidade or any(codigo is None for codigo in testes.values()):
        problemas.append(f"só {terminados} de {max(quantidade, len(testes))} testes terminaram")
    # Um pedaço vazio é válido: a conferência de classes/total acontece juntando todos os pedaços.
    return {"tests": testes, "problems": problemas, "errors": erros}


def corresponde(alvo: str, teste: str) -> bool:
    nome, _, metodo = alvo.partition("#")
    classe, _, nome_teste = teste.partition("#")
    mesmo_nome = classe == nome or classe.endswith("." + nome) or classe.rsplit("$", 1)[0] == nome
    return mesmo_nome and (not metodo or nome_teste == metodo)


def resumir(args: argparse.Namespace) -> int:
    classes = [c for c in args.classes.split(",") if c]
    repeticoes = int(args.repeticoes)
    arquivos = sorted(args.evidencia.rglob("rep-*.txt"))
    rodadas: dict[int, list[dict]] = {n: [] for n in range(1, repeticoes + 1)}
    for arquivo in arquivos:
        try:
            rep = int(arquivo.stem.removeprefix("rep-"))
        except ValueError:
            continue
        if rep in rodadas:
            rodadas[rep].append(ler_log(arquivo))

    por_rep = {}
    problemas_gerais = []
    for rep, shard_logs in rodadas.items():
        resultados: dict[str, int] = {}
        for log in shard_logs:
            resultados.update(log["tests"])
            problemas_gerais.extend(f"repetição {rep}: {p}" for p in log["problems"])
        por_rep[rep] = resultados
        if classes and len(shard_logs) != args.pedacos:
            problemas_gerais.append(f"repetição {rep}: chegaram {len(shard_logs)} de {args.pedacos} pedaços")
    for arquivo in args.evidencia.rglob("*.adb-exit"):
        if arquivo.read_text().strip() != "0":
            problemas_gerais.append(f"ADB falhou: {arquivo.parent.name}/{arquivo.name}")

    esperado: dict[str, set[str]] = {c: set() for c in classes}
    for resultados in por_rep.values():
        for nome in resultados:
            for alvo in classes:
                if corresponde(alvo, nome):
                    esperado[alvo].add(nome)

    linhas = [f"# CI sob demanda — {args.rotulo}", "", f"Branch: `{args.branch}`",
              f"Gradle: {args.tarefas_resultado}", f"Instrumentados: {args.device_resultado}", "",
              "## Testes por repetição e classe", ""]
    linhas.append("| Repetição | Classe solicitada | Passaram | Resultado |")
    linhas.append("| ---: | --- | ---: | --- |")
    falhou = args.build_resultado != "success" or (bool(classes) and args.device_resultado != "success") or bool(problemas_gerais)
    for rep, resultados in por_rep.items():
        for alvo in classes:
            nomes = esperado[alvo]
            encontrados = {n: resultados[n] for n in nomes if n in resultados}
            ok = sum(c == 0 for c in encontrados.values())
            total = len(nomes)
            problemas = sum(c not in (0, -3, -4) for c in encontrados.values()) + (total - len(encontrados))
            if not total:
                texto = "sem testes detectados"
                falhou = True
            else:
                texto = "falhou" if problemas else "ok"
            falhou = falhou or problemas > 0
            linhas.append(f"| {rep} | `{alvo}` | {ok}/{total} | {texto} |")
    if not classes:
        linhas.append("Nenhuma classe instrumentada foi solicitada.")
    unitarios = {}
    falhas_unitarios = []
    for arquivo in args.evidencia.rglob("TEST-*.xml"):
        try:
            suite = ET.parse(arquivo).getroot()
        except ET.ParseError:
            problemas_gerais.append(f"relatório JVM inválido: {arquivo.name}")
            falhou = True
            continue
        for teste in suite.iter("testcase"):
            classe = teste.get("classname", "?")
            contagem = unitarios.setdefault(classe, [0, 0])
            contagem[1] += 1
            passou = teste.find("failure") is None and teste.find("error") is None and teste.find("skipped") is None
            contagem[0] += passou
            if teste.find("failure") is not None or teste.find("error") is not None:
                falhou = True
                erro = teste.find("failure")
                if erro is None:
                    erro = teste.find("error")
                trecho = "\n".join((erro.text or erro.get("message", "falha sem detalhe")).splitlines()[:6])
                falhas_unitarios.append((f"{classe}.{teste.get('name', '?')}", trecho))
    if unitarios:
        linhas.extend(["", "## Unitários por classe", "", "| Classe | Passaram |", "| --- | ---: |"])
        linhas.extend(f"| `{classe}` | {ok}/{total} |" for classe, (ok, total) in sorted(unitarios.items()))
    for nome, trecho in falhas_unitarios:
        linhas.extend(["", f"Falha JVM em `{nome}`:", "```text", trecho.replace("```", "'''")[:1200], "```"])
    if problemas_gerais:
        linhas.extend(["", "## Falhas e trechos do log", ""])
        linhas.extend(f"- {p}" for p in dict.fromkeys(problemas_gerais))
    for rep, resultados in por_rep.items():
        for log in rodadas[rep]:
            for nome, trecho in log["errors"].items():
                linhas.extend([f"- Repetição {rep}, `{nome}`:", "", "```text", trecho.replace("```", "'''")[:1200], "```"])
    if args.build_resultado != "success":
        linhas.extend(["", f"Gradle terminou como `{args.build_resultado}`."])
        for log in sorted(args.evidencia.rglob("*.log")):
            linhas.extend(["", f"Trecho de {log.name}:", "```text",
                           "\n".join(log.read_text(errors="replace").splitlines()[-20:]).replace("```", "'''"), "```"])
    if classes and args.device_resultado != "success":
        linhas.extend(["", f"Jobs instrumentados terminaram como `{args.device_resultado}`."])
    texto = "\n".join(linhas) + "\n"
    args.saida.parent.mkdir(parents=True, exist_ok=True)
    args.saida.write_text(texto, encoding="utf-8")
    print(texto, end="")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(texto)
    return 1 if falhou else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="comando", required=True)
    validar_parser = sub.add_parser("validar")
    validar_parser.add_argument("--raiz", type=Path, default=Path.cwd())
    validar_parser.add_argument("--saida-github", type=Path)
    resumo_parser = sub.add_parser("resumir")
    resumo_parser.add_argument("--evidencia", type=Path, required=True)
    resumo_parser.add_argument("--saida", type=Path, required=True)
    resumo_parser.add_argument("--classes", required=True)
    resumo_parser.add_argument("--repeticoes", required=True)
    resumo_parser.add_argument("--pedacos", type=int, default=1)
    resumo_parser.add_argument("--rotulo", required=True)
    resumo_parser.add_argument("--branch", required=True)
    resumo_parser.add_argument("--tarefas-resultado", required=True)
    resumo_parser.add_argument("--build-resultado", required=True)
    resumo_parser.add_argument("--device-resultado", required=True)
    args = parser.parse_args()
    if args.comando == "validar":
        try:
            dados = validar(os.environ.get("CI_TAREFAS", ""), os.environ.get("CI_CLASSES", ""),
                            os.environ.get("CI_PEDACOS", "1"), os.environ.get("CI_REPETICOES", "1"),
                            os.environ.get("CI_ROTULO", "manual"), os.environ.get("CI_REF", ""), args.raiz)
        except ValueError as erro:
            print(f"entrada inválida: {erro}", file=sys.stderr)
            return 2
        if args.saida_github:
            with args.saida_github.open("a", encoding="utf-8") as out:
                for chave, valor in dados.items():
                    if isinstance(valor, bool):
                        valor = str(valor).lower()
                    out.write(f"{chave}={valor}\n")
        else:
            print(json.dumps(dados))
        return 0
    return resumir(args)


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Cliente do workflow manual; usa o mesmo gh e o mesmo git do comando ci enviar."""
from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
import time
import uuid

REPO = "Arateki/Keyflow"
WORKFLOW = "ci-sob-demanda.yml"
VALIDAR = runpy.run_path(str(Path(__file__).with_name("ci-sob-demanda.py")))["validar"]


class Erro(Exception):
    def __init__(self, mensagem, codigo=2):
        self.codigo = codigo
        super().__init__(mensagem)


def executar(comando, *, aceitar_erro=False):
    resultado = subprocess.run(comando, text=True, capture_output=True)
    if resultado.returncode and not aceitar_erro:
        raise Erro(f"falhou o comando {comando[0]} {comando[1]}; confira acesso/estado antes de repetir", 4)
    return resultado


def publicar(branch, head):
    executar(["git", "fetch", "-q", "origin", "main"])
    base = executar(["git", "merge-base", head, "origin/main"]).stdout.strip()
    arquivos = executar(["git", "diff", "--name-only", base, head]).stdout.splitlines()
    if not arquivos:
        raise Erro("a branch não tem commits além da main")
    segredo = re.compile(r"(\.jks|\.keystore|\.p12|\.pem|(^|/)\.env(\..*)?|google-services\.json|keystore\.properties|upload-keystore)$")
    for nome in arquivos:
        if segredo.search(nome) and nome != "api/tests/fixtures/test_service_account_key.pem" and not nome.endswith(".env.example"):
            raise Erro("recusei arquivo com cara de segredo na branch; o repositório é público", 3)
    diff = executar(["git", "diff", base, head]).stdout
    padrao = re.compile(r"AIza[0-9A-Za-z_-]{35}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}|xai-[A-Za-z0-9]{40,}|gh[pousr]_[A-Za-z0-9]{36}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----")
    for linha in diff.splitlines():
        if linha.startswith("+") and not re.search(r"test_service_account_key|chave-falsa|fake", linha) and padrao.search(linha):
            raise Erro("recusei formato de chave/token no diff; confira antes de publicar", 3)
    if executar(["git", "status", "--porcelain"]).stdout.strip():
        print("aviso: há mudanças não commitadas; só os commits sobem.", flush=True)
    remoto = executar(["git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}"]).stdout.split()
    if not remoto or remoto[0] != head:
        push = executar(["git", "push", "-q", "origin", f"{head}:refs/heads/{branch}"], aceitar_erro=True)
        if push.returncode:
            raise Erro("push recusado; não uso force. Fale com o coordenador sobre a divergência da branch", 4)


def main():
    parser = argparse.ArgumentParser(description="CI sob demanda: listas por vírgula; tarefas também aceitam espaços.")
    parser.add_argument("--gh", default="/srv/dev/ferramentas/bin/gh")
    parser.add_argument("--tarefas", default="")
    parser.add_argument("--classes", default="")
    parser.add_argument("--pedacos", default="1")
    parser.add_argument("--repetir", default="1")
    parser.add_argument("--rotulo", default="pedido-" + uuid.uuid4().hex[:16])
    args = parser.parse_args()
    raiz = Path(executar(["git", "rev-parse", "--show-toplevel"]).stdout.strip())
    branch = executar(["git", "branch", "--show-current"]).stdout.strip()
    if not re.fullmatch(r"equipe/[A-Za-z0-9._/-]+", branch):
        raise Erro("só branches equipe/*; main e HEAD destacado são recusados")
    try:
        pedido = VALIDAR(args.tarefas, args.classes, args.pedacos, args.repetir, args.rotulo,
                         "refs/heads/" + branch, raiz)
    except ValueError as exc:
        raise Erro(str(exc)) from exc
    head = executar(["git", "rev-parse", "HEAD"]).stdout.strip()
    publicar(branch, head)
    pasta = Path(os.environ.get("CI_TMP_DIR", "/tmp/ci-sob-demanda"))
    pasta.mkdir(parents=True, exist_ok=True)
    limite = time.monotonic() + float(os.environ.get("CI_ESPERA", "10800"))
    intervalo = max(.01, min(60., float(os.environ.get("CI_INTERVALO", "20"))))

    def gh(*argumentos):
        return executar([args.gh, *argumentos, "-R", REPO]).stdout

    def gh_json(*argumentos):
        try:
            return json.loads(gh(*argumentos))
        except (ValueError, TypeError) as exc:
            raise Erro("resposta inválida do gh; nenhum pedido adicional foi enviado", 4) from exc

    def pausa(mensagem):
        if time.monotonic() >= limite:
            raise Erro(mensagem, 75)
        time.sleep(min(intervalo, max(0., limite - time.monotonic())))

    def rodadas():
        return gh_json("run", "list", "-w", WORKFLOW, "-b", branch, "-L", "100", "--json",
                       "databaseId,status,conclusion,headSha,url,displayTitle")

    # A trava coordena clientes no servidor. O GitHub continua impondo o teto global, inclusive a
    # disparos externos. Reservar a rodada inteira também cobre jobs dependentes ainda não criados.
    with (pasta / "pool.lock").open("a") as trava:
        while True:
            try:
                fcntl.flock(trava, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                print("ci: esperando outro cliente reservar vaga no pool…", flush=True)
                pausa("espera pelo pool expirou; nenhum workflow disparado")
        reserva = (pedido["pedacos"] if pedido["has_device"] else 0) + 3
        while True:
            ativos = gh_json("run", "list", "-L", "100", "--json", "databaseId,status,workflowName")
            ocupados = 0
            for run in ativos:
                if run["status"] == "completed":
                    continue
                jobs = gh_json("run", "view", str(run["databaseId"]), "--json", "jobs")["jobs"]
                vistos = sum(job["status"] != "completed" for job in jobs)
                previstos = {"Checks": 7, "Stress": 17, "CI sob demanda": 9}.get(run.get("workflowName"), 20)
                ocupados += max(vistos, previstos)
            if ocupados + reserva <= 20:
                break
            print(f"ci: esperando vaga no pool ({ocupados} jobs reservados + {reserva} pedidos; teto 20)…", flush=True)
            pausa("pool continua ocupado; nenhum workflow disparado")
        if executar(["git", "rev-parse", "HEAD"]).stdout.strip() != head or executar(["git", "branch", "--show-current"]).stdout.strip() != branch:
            raise Erro("HEAD/branch mudou durante a espera; confira e peça novamente")
        remoto = executar(["git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}"]).stdout.split()
        if not remoto or remoto[0] != head:
            raise Erro("branch remota mudou durante a espera; não disparo testes de outro commit")
        titulo = f"demanda-{pedido['rotulo']}-{branch}"
        anteriores_lista = rodadas()
        for run in anteriores_lista:
            if run["displayTitle"] == titulo and run["status"] != "completed":
                raise Erro(f"já há pedido ativo com esse rótulo: {run['url']}; acompanhe essa rodada", 75)
        anteriores = {run["databaseId"] for run in anteriores_lista}
        despacho = executar([args.gh, "workflow", "run", WORKFLOW, "--ref", branch,
           "-f", "tarefas=" + pedido["gradle_tasks"], "-f", "classes=" + pedido["classes"],
           "-f", "pedacos=" + str(pedido["pedacos"]), "-f", "repeticoes=" + str(pedido["repeticoes"]),
           "-f", "rotulo=" + pedido["rotulo"], "-R", REPO], aceitar_erro=True)
        if despacho.returncode:
            raise Erro("não foi possível disparar; confira a credencial já usada por ci enviar e se o workflow está registrado na main (exigência de workflow_dispatch)", 4)
        print(f"ci: pedido {pedido['rotulo']} enviado no commit {head[:7]}", flush=True)
        while True:
            candidatas = [run for run in rodadas() if run["databaseId"] not in anteriores
                          and run["headSha"] == head and run["displayTitle"] == titulo]
            if candidatas:
                rodada = candidatas[0]
                break
            pausa(f"pedido enviado, mas a rodada ainda não apareceu; confira Actions pelo rótulo {pedido['rotulo']} antes de repetir")
        fcntl.flock(trava, fcntl.LOCK_UN)
    id_run = str(rodada["databaseId"])
    print(f"rodada {id_run}: {rodada['url']}", flush=True)
    while rodada["status"] != "completed":
        pausa(f"rodada {id_run} ainda em curso: {rodada['url']}; acompanhe sem redisparar")
        rodada = gh_json("run", "view", id_run, "--json", "databaseId,status,conclusion,headSha,url")
        if rodada["headSha"] != head:
            raise Erro("a rodada consultada não corresponde ao commit pedido", 4)
    with tempfile.TemporaryDirectory(prefix=f"resumo-{id_run}-", dir=pasta) as destino:
        resultado = executar([args.gh, "run", "download", id_run, "-R", REPO,
                              "-n", "ci-sob-demanda-resumo", "-D", destino], aceitar_erro=True)
        resumo = Path(destino) / "resumo.md"
        if resultado.returncode == 0 and resumo.is_file():
            print(resumo.read_text(encoding="utf-8"))
        else:
            print("ci: resumo indisponível; trecho dos jobs que falharam:", flush=True)
            falhas = executar([args.gh, "run", "view", id_run, "-R", REPO, "--log-failed"], aceitar_erro=True)
            print("\n".join(falhas.stdout.splitlines()[-40:]))
            return 1
    print(f"rodada {id_run}: {rodada['conclusion']} — {rodada['url']}")
    return 0 if rodada["conclusion"] == "success" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Erro as erro:
        print(f"ci: {erro}", file=sys.stderr)
        raise SystemExit(erro.codigo)
    except KeyboardInterrupt:
        print("ci: espera interrompida; confira Actions antes de disparar novamente", file=sys.stderr)
        raise SystemExit(130)

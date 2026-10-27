"""Regressões sem rede: gh falso e remotos Git locais descartáveis."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest

RAIZ = Path(__file__).resolve().parents[2]
MOD = runpy.run_path(str(RAIZ / "scripts/ci-sob-demanda.py"))
BASE = Path("/tmp/ci-sob-demanda")
BASE.mkdir(exist_ok=True)

GH_FALSO = r'''#!/usr/bin/env python3
import json, os, subprocess, sys
from pathlib import Path
a=sys.argv[1:]
p=Path(os.environ['CI_TMP_DIR'])
with (p/'gh-chamadas').open('a') as f: f.write(json.dumps(a)+'\n')
def valor(op): return a[a.index(op)+1]
if a[:2]==['workflow','run']:
 campos={a[i+1].split('=',1)[0]:a[i+1].split('=',1)[1] for i,v in enumerate(a) if v=='-f'}
 (p/'disparo').write_text(json.dumps(campos));sys.exit()
if a[:2]==['run','list']:
 if '-w' not in a:
  n=int((p/'consultas').read_text()) if (p/'consultas').exists() else 0
  (p/'consultas').write_text(str(n+1))
  ocupado=os.environ.get('GH_OCUPADO')=='sempre' or (os.environ.get('GH_OCUPADO')=='uma' and n==0)
  print(json.dumps([dict(databaseId=i,status='in_progress',workflowName='Checks') for i in range(3)] if ocupado else []));sys.exit()
 if not (p/'disparo').exists():print('[]');sys.exit()
 campos=json.loads((p/'disparo').read_text())
 sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
 branch=subprocess.check_output(['git','branch','--show-current'],text=True).strip()
 r=dict(databaseId=123,status='completed',conclusion=os.environ.get('GH_CONCLUSAO','success'),headSha=sha,url='https://example.invalid/run/123',displayTitle='demanda-'+campos['rotulo']+'-'+branch)
 print(json.dumps([dict(r,databaseId=122,headSha='outro'),r]));sys.exit()
if a[:2]==['run','view']:
 if '--log-failed' in a:print('trecho da falha para diagnóstico');sys.exit()
 print(json.dumps(dict(jobs=[dict(status='in_progress')])));sys.exit()
if a[:2]==['run','download']:
 if os.environ.get('GH_SEM_RESUMO'):sys.exit(1)
 destino=Path(valor('-D'));destino.mkdir(parents=True,exist_ok=True)
 (destino/'resumo.md').write_text('Classe: 2/2; repetição 1: 2/2\n');sys.exit()
raise SystemExit('gh falso recebeu chamada não prevista: '+str(a))
'''


class Cliente(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=BASE)
        self.addCleanup(self.tmp.cleanup)
        self.pasta = Path(self.tmp.name)
        self.repo = self.pasta / "repo"
        self.repo.mkdir()
        self.pool = self.pasta / "pool"
        self.pool.mkdir()
        self.remoto = self.pasta / "origin.git"
        subprocess.run(["git", "init", "--bare", "-q", str(self.remoto)], check=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Teste")
        self.git("config", "user.email", "teste@example.invalid")
        (self.repo / "scripts").mkdir()
        for nome in ("ci", "ci-rodar.py", "ci-sob-demanda.py"):
            shutil.copy2(RAIZ / "scripts" / nome, self.repo / "scripts" / nome)
        fonte = self.repo / "app/src/androidTest/com/keyflow/ui/PermissionsCheckerTest.kt"
        fonte.parent.mkdir(parents=True)
        fonte.write_text("package com.keyflow.ui\nclass PermissionsCheckerTest {}\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Base de teste")
        self.git("remote", "add", "origin", str(self.remoto))
        self.git("push", "-q", "origin", "main")
        self.git("checkout", "-qb", "equipe/teste")
        self.commit("mudanca.txt", "Mudança de teste")
        self.gh = self.pasta / "gh"
        self.gh.write_text(GH_FALSO)
        self.gh.chmod(0o755)
        self.env = dict(os.environ, CI_GH_BIN=str(self.gh), CI_TMP_DIR=str(self.pool),
                        CI_INTERVALO="0.01", CI_ESPERA="5", PYTHONDONTWRITEBYTECODE="1")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, text=True, capture_output=True).stdout.strip()

    def commit(self, nome, conteudo):
        (self.repo / nome).write_text(conteudo)
        self.git("add", nome)
        self.git("commit", "-qm", "Alteração de teste")

    def rodar(self, *args, **env):
        return subprocess.run(["bash", str(self.repo / "scripts/ci"), "rodar", *args], cwd=self.repo,
                              env=dict(self.env, **env), text=True, capture_output=True, timeout=15)

    def chamadas(self):
        path = self.pool / "gh-chamadas"
        return [json.loads(l) for l in path.read_text().splitlines()] if path.exists() else []

    def test_publica_dispara_ref_correta_e_imprime_resumo(self):
        r = self.rodar("--tarefas", "assembleDebug", "--classes", "PermissionsCheckerTest", "--pedacos", "2", "--rotulo", "primeiro")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("Classe: 2/2", r.stdout)
        chamada = next(c for c in self.chamadas() if c[:2] == ["workflow", "run"])
        self.assertEqual(chamada[chamada.index("--ref") + 1], "equipe/teste")
        self.assertIn("classes=com.keyflow.ui.PermissionsCheckerTest", chamada)
        self.assertEqual(self.git("ls-remote", "origin", "refs/heads/equipe/teste").split()[0], self.git("rev-parse", "HEAD"))
        download = next(c for c in self.chamadas() if c[:2] == ["run", "download"])
        self.assertEqual(download[2], "123")

    def test_recusa_entradas_antes_de_rede(self):
        casos = [("--classes", "PermissionsCheckerTest#m;id"), ("--tarefas", "assembleDebug;id"),
                 ("--tarefas", "assembleDebug", "--pedacos", "7"),
                 ("--classes", "PermissionsCheckerTest", "--repetir", "21"),
                 ("--tarefas", "assembleDebug", "--rotulo", "a b"), (),
                 ("--classes", "NaoExisteTest"), ("--tarefas", "publish"),
                 ("--classes", "PermissionsCheckerTest#"), ("--classes", "PermissionsCheckerTest", "--pedacos", "-1")]
        for caso in casos:
            with self.subTest(caso=caso):
                r = self.rodar(*caso)
                self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertEqual(self.chamadas(), [])
        self.assertEqual(self.git("ls-remote", "origin", "refs/heads/equipe/teste"), "")

    def test_recusa_main_e_head_destacado(self):
        for ref in ("main", self.git("rev-parse", "HEAD")):
            self.git("checkout", "-q", ref)
            r = self.rodar("--tarefas", "assembleDebug")
            self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertEqual(self.chamadas(), [])

    def test_recusa_arquivo_de_segredo(self):
        self.commit(".env", "valor de teste")
        r = self.rodar("--tarefas", "assembleDebug")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertEqual(self.chamadas(), [])

    def test_recusa_conteudo_de_token_sem_imprimir_valor(self):
        token = "ghp_" + "x" * 36
        self.commit("config.txt", token)
        r = self.rodar("--tarefas", "assembleDebug")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertNotIn(token, r.stdout + r.stderr)
        self.assertEqual(self.chamadas(), [])

    def test_push_divergente_nao_usa_force_nem_dispara(self):
        self.git("checkout", "-qb", "divergente", "main")
        self.commit("outro.txt", "Outro histórico")
        self.git("push", "-q", "origin", "HEAD:refs/heads/equipe/teste")
        remoto = self.git("rev-parse", "HEAD")
        self.git("checkout", "-q", "equipe/teste")
        r = self.rodar("--tarefas", "assembleDebug")
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertEqual(self.git("ls-remote", "origin", "refs/heads/equipe/teste").split()[0], remoto)
        self.assertEqual(self.chamadas(), [])

    def test_head_ja_publicado_nao_tenta_outro_push(self):
        self.git("push", "-q", "origin", "HEAD:refs/heads/equipe/teste")
        hook = self.remoto / "hooks/pre-receive"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o755)
        r = self.rodar("--tarefas", "assembleDebug")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_espera_pool_sem_enfileirar_varios_pedidos(self):
        r = self.rodar("--classes", "PermissionsCheckerTest", GH_OCUPADO="uma")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("esperando vaga no pool", r.stdout)
        self.assertEqual(sum(c[:2] == ["workflow", "run"] for c in self.chamadas()), 1)

    def test_pool_ocupado_expira_sem_disparar(self):
        r = self.rodar("--tarefas", "assembleDebug", GH_OCUPADO="sempre", CI_ESPERA="0.1")
        self.assertEqual(r.returncode, 75, r.stdout + r.stderr)
        self.assertFalse(any(c[:2] == ["workflow", "run"] for c in self.chamadas()))

    def test_conclusao_falha_nao_vira_sucesso_por_ter_resumo(self):
        r = self.rodar("--tarefas", "assembleDebug", GH_CONCLUSAO="failure")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("Classe: 2/2", r.stdout)

    def test_resumo_ausente_mostra_log_e_falha(self):
        r = self.rodar("--tarefas", "assembleDebug", GH_SEM_RESUMO="1")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("trecho da falha", r.stdout)

    def test_filtro_unitario_chega_literalmente_ao_workflow(self):
        r = self.rodar("--tarefas", "testDebugUnitTest --tests=com.keyflow.FooTest")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        chamada = next(c for c in self.chamadas() if c[:2] == ["workflow", "run"])
        self.assertIn("tarefas=testDebugUnitTest,--tests=com.keyflow.FooTest", chamada)


def log(teste="metodo", codigo=0, final=True):
    return ("INSTRUMENTATION_STATUS: class=com.keyflow.ui.PermissionsCheckerTest\n"
            f"INSTRUMENTATION_STATUS: test={teste}\nINSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: exemplo\n  at Classe.kt:42\n"
            f"INSTRUMENTATION_STATUS_CODE: {codigo}\n" + ("INSTRUMENTATION_CODE: -1\n" if final else ""))


class Resumo(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=BASE)
        self.addCleanup(self.tmp.cleanup)
        self.raiz = Path(self.tmp.name)
        self.args = argparse.Namespace(evidencia=self.raiz, saida=self.raiz / "resumo.md", classes="com.keyflow.ui.PermissionsCheckerTest",
            repeticoes="2", pedacos=2, rotulo="pedido", branch="equipe/teste", tarefas_resultado="assembleDebug",
            build_resultado="success", device_resultado="success")
        for shard in range(2):
            pasta = self.raiz / f"demanda-device-{shard}/demand/shard-{shard}"
            pasta.mkdir(parents=True)
            for rep in range(1, 3):
                (pasta / f"rep-{rep}.txt").write_text(log(f"teste{shard}"))

    def resumir(self):
        with contextlib.redirect_stdout(io.StringIO()):
            rc = MOD["resumir"](self.args)
        return rc, self.args.saida.read_text()

    def test_placar_por_classe_e_por_repeticao(self):
        rc, texto = self.resumir()
        self.assertEqual(rc, 0)
        self.assertEqual(texto.count("| 2/2 | ok |"), 2)

    def test_falha_tem_trecho_do_log_e_codigo_nao_zero(self):
        (self.raiz / "demanda-device-1/demand/shard-1/rep-2.txt").write_text(log("teste1", -2))
        rc, texto = self.resumir()
        self.assertEqual(rc, 1)
        self.assertIn("| 1/2 | falhou |", texto)
        self.assertIn("AssertionError: exemplo", texto)
        self.assertIn("Classe.kt:42", texto)

    def test_crash_e_falta_de_pedaco_falham(self):
        (self.raiz / "demanda-device-1/demand/shard-1/rep-2.txt").unlink()
        (self.raiz / "demanda-device-0/demand/shard-0/rep-2.txt").write_text(log("teste0", final=False))
        rc, texto = self.resumir()
        self.assertEqual(rc, 1)
        self.assertIn("1 de 2 pedaços", texto)
        self.assertIn("instrumentação incompleta", texto)

    def test_pedaco_vazio_nao_falha_se_outro_executou_a_classe(self):
        for rep in range(1, 3):
            (self.raiz / f"demanda-device-1/demand/shard-1/rep-{rep}.txt").write_text("INSTRUMENTATION_CODE: -1\n")
        rc, texto = self.resumir()
        self.assertEqual(rc, 0, texto)
        self.assertEqual(texto.count("| 1/1 | ok |"), 2)

    def test_sem_testes_no_pedido_instrumentado_falha(self):
        for path in self.raiz.rglob("rep-*.txt"):
            path.write_text("INSTRUMENTATION_CODE: -1\n")
        rc, texto = self.resumir()
        self.assertEqual(rc, 1)
        self.assertIn("sem testes detectados", texto)

    def test_so_gradle_e_resumo_de_unitarios(self):
        self.args.classes = ""
        self.args.device_resultado = "skipped"
        for path in self.raiz.rglob("rep-*.txt"):
            path.unlink()
        (self.raiz / "TEST-classe.xml").write_text('<testsuite><testcase classname="Foo" name="a"/><testcase classname="Foo" name="b"/></testsuite>')
        rc, texto = self.resumir()
        self.assertEqual(rc, 0, texto)
        self.assertIn("`Foo` | 2/2", texto)

    def test_falha_jvm_inclui_trecho_do_relatorio(self):
        (self.raiz / "TEST-classe.xml").write_text('<testsuite><testcase classname="Foo" name="quebra"><failure>AssertionError: JVM\n at Foo.kt:12</failure></testcase></testsuite>')
        rc, texto = self.resumir()
        self.assertEqual(rc, 1)
        self.assertIn("`Foo` | 0/1", texto)
        self.assertIn("Foo.kt:12", texto)


class Workflow(unittest.TestCase):
    def test_seguranca_e_actions_fixadas(self):
        texto = (RAIZ / ".github/workflows/ci-sob-demanda.yml").read_text()
        self.assertIn("workflow_dispatch:", texto)
        self.assertNotIn("pull_request:", texto)
        self.assertNotIn("  push:", texto)
        self.assertIn("contents: read", texto)
        self.assertNotIn("secrets.", texto)
        import re
        for uses in re.findall(r"uses: (.+)", texto):
            self.assertRegex(uses, r"@[a-f0-9]{40}(?: |$)")
        # Entradas do usuário só aparecem em campos de dados; nenhum bloco run recebe expressão inputs.
        em_run = False
        nivel = 0
        for linha in texto.splitlines():
            if em_run and linha.strip() and len(linha) - len(linha.lstrip()) <= nivel:
                em_run = False
            if em_run:
                self.assertNotIn("inputs.", linha)
            if "run:" in linha:
                self.assertNotIn("inputs.", linha)
                em_run = linha.rstrip().endswith("|")
                nivel = len(linha) - len(linha.lstrip())

    def test_blocos_run_tem_sintaxe_bash_valida(self):
        texto = (RAIZ / ".github/workflows/ci-sob-demanda.yml").read_text()
        linhas = texto.splitlines()
        blocos = []
        for i, linha in enumerate(linhas):
            if "run: " not in linha:
                continue
            nivel = len(linha) - len(linha.lstrip())
            conteudo = linha.split("run: ", 1)[1]
            if conteudo == "|":
                trecho = []
                for seguinte in linhas[i+1:]:
                    if seguinte.strip() and len(seguinte) - len(seguinte.lstrip()) <= nivel:
                        break
                    trecho.append(seguinte[nivel+2:])
                conteudo = "\n".join(trecho)
            blocos.append(conteudo)
        self.assertGreater(len(blocos), 5)
        for bloco in blocos:
            resultado = subprocess.run(["bash", "-n"], input=bloco, text=True, capture_output=True)
            self.assertEqual(resultado.returncode, 0, resultado.stderr + bloco)


class Device(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=BASE)
        self.addCleanup(self.tmp.cleanup)
        self.raiz = Path(self.tmp.name)
        # Carrega só a função seletiva: o restante do gate administra o emulador real e não entra no teste.
        texto = (RAIZ / "scripts/ci-device-gate.sh").read_text()
        self.funcao = texto[texto.index("run_selected() {"):].split("\n}", 1)[0] + "\n}\n"
        self.adb = self.raiz / "adb"
        self.adb.write_text("#!/usr/bin/env python3\nimport json,os,sys\nfrom pathlib import Path\np=Path(os.environ['DEVICE_LOG'])\nwith p.open('a') as f:f.write(json.dumps(sys.argv[1:])+'\\n')\nprint('INSTRUMENTATION_STATUS_CODE: '+os.environ.get('CODIGO_TESTE','0'))\nprint('INSTRUMENTATION_CODE: -1')\n")
        self.adb.chmod(0o755)

    def rodar(self, classes="com.keyflow.Foo$Aninhado#metodo", **env):
        script = self.funcao + '\nstatus=0\nrun_selected 0 2 2\nrc=$?\nprintf "%s %s" "$rc" "$status" > "$RESULTADO"\n'
        resultado = subprocess.run(["bash", "-uo", "pipefail"], input=script, text=True, capture_output=True,
            env=dict(os.environ, CI_CLASSES=classes, EVIDENCE=str(self.raiz), ADB=str(self.adb), RUNNER="com.keyflow.test/Runner",
                     DEVICE_LOG=str(self.raiz / "chamadas"), RESULTADO=str(self.raiz / "resultado"), **env))
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return (self.raiz / "resultado").read_text()

    def test_repeticoes_geram_logs_distintos_e_classe_chega_literal(self):
        self.assertEqual(self.rodar(), "0 0")
        chamadas = [json.loads(l) for l in (self.raiz / "chamadas").read_text().splitlines()]
        self.assertEqual(len(chamadas), 2)
        self.assertIn("'com.keyflow.Foo$Aninhado#metodo'", chamadas[0])
        self.assertEqual(len(list(self.raiz.rglob("rep-*.txt"))), 2)

    def test_falha_instrumentada_preserva_repeticoes_e_status(self):
        self.assertEqual(self.rodar(CODIGO_TESTE="-2"), "0 1")
        self.assertEqual(len(list(self.raiz.rglob("rep-*.txt"))), 2)

    def test_metacaracteres_nao_chegam_ao_adb(self):
        self.assertEqual(self.rodar(classes="Classe;id"), "2 0")
        self.assertFalse((self.raiz / "chamadas").exists())


if __name__ == "__main__":
    unittest.main()

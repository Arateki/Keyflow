"""Regressões do que reduz conflito entre branches paralelas (.specs/codebase/TESTING.md › Conflitos de mesclagem).

Sem rede: os repositórios Git são descartáveis, em diretório temporário.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

RAIZ = Path(__file__).resolve().parents[2]

# Arquivos de anotação que só crescem por acréscimo: linha repetida ou ordem trocada não quebra nada neles.
# Para pôr outro aqui, confirme isso e atualize a seção de TESTING.md e o comentário do .gitattributes.
COM_UNION = {".specs/project/STATE.md", "app/licenses/README.md"}


def entradas_union():
    achados = set()
    for linha in (RAIZ / ".gitattributes").read_text(encoding="utf-8").splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#"):
            continue
        padrao, *atributos = linha.split()
        if "merge=union" in atributos:
            achados.add(padrao)
    return achados


class Union(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / "repo"
        self.repo.mkdir()
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1", HOME=self.tmp.name)

    def git(self, *args, check=True):
        return subprocess.run(
            ["git", "-c", "user.name=teste", "-c", "user.email=teste@example.invalid", "-c", "commit.gpgsign=false",
             "-C", str(self.repo), *args],
            capture_output=True, text=True, check=check, env=self.env,
        )

    def escrever(self, caminho, texto, modo="w"):
        arquivo = self.repo / caminho
        arquivo.parent.mkdir(parents=True, exist_ok=True)
        with arquivo.open(modo, encoding="utf-8") as f:
            f.write(texto)

    def montar(self, com_atributos):
        self.git("init", "-q", "-b", "main")
        if com_atributos:
            self.escrever(".gitattributes", (RAIZ / ".gitattributes").read_text(encoding="utf-8"))
        self.escrever(".specs/project/STATE.md", "# STATE\n\n## 2026-10-20 — lição antiga\n\nTexto antigo.\n")
        self.escrever("app/licenses/README.md", "# Licenças\n\n| Componente | Licença |\n|---|---|\n| A | MIT |\n\nNotas.\n")
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        for nome in ("a", "b"):
            self.git("checkout", "-q", "-b", nome, "main")
            self.escrever(".specs/project/STATE.md", f"\n## 2026-10-27 — lição da branch {nome}\n\nTexto da {nome}.\n", "a")
            licencas = (self.repo / "app/licenses/README.md").read_text(encoding="utf-8")
            self.escrever("app/licenses/README.md", licencas.replace("| A | MIT |\n", f"| A | MIT |\n| Modelo {nome} | Apache-2.0 |\n"))
            self.git("commit", "-qam", f"lição {nome}")
        self.git("checkout", "-q", "a")

    def test_a_lista_de_union_e_a_combinada(self):
        self.assertEqual(
            entradas_union(), COM_UNION,
            "merge=union só vale para arquivo que cresce por acréscimo; mexeu na lista? Atualize COM_UNION, a seção "
            "'Conflitos de mesclagem' do TESTING.md e o comentário do .gitattributes",
        )

    def test_controle_sem_o_gitattributes_conflita(self):
        # Prova que o cenário de baixo de fato conflita: sem isto o teste seguinte passaria à toa.
        self.montar(com_atributos=False)
        r = self.git("merge", "--no-edit", "b", check=False)
        self.assertNotEqual(r.returncode, 0, r.stdout)
        self.assertIn("<<<<<<<", (self.repo / ".specs/project/STATE.md").read_text(encoding="utf-8"))

    def test_duas_branches_que_acrescentam_no_fim_mesclam_sem_conflito(self):
        self.montar(com_atributos=True)
        r = self.git("merge", "--no-edit", "b", check=False)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        estado = (self.repo / ".specs/project/STATE.md").read_text(encoding="utf-8")
        licencas = (self.repo / "app/licenses/README.md").read_text(encoding="utf-8")
        for texto in ("Texto antigo.", "Texto da a.", "Texto da b."):
            self.assertEqual(estado.count(texto), 1, estado)
        for linha in ("| A | MIT |", "| Modelo a | Apache-2.0 |", "| Modelo b | Apache-2.0 |"):
            self.assertEqual(licencas.count(linha), 1, licencas)
        self.assertNotIn("<<<<<<<", estado + licencas)

    def test_receita_da_primeira_sincronizacao_de_branch_antiga(self):
        # O Git lê as regras do que já está no diretório, não do que a mesclagem traz: a branch aberta antes do
        # .gitattributes conflitaria na 1ª sincronização. A receita do TESTING.md aponta o arquivo da main à mão.
        self.montar(com_atributos=False)
        self.git("checkout", "-q", "main")
        self.escrever(".gitattributes", (RAIZ / ".gitattributes").read_text(encoding="utf-8"))
        self.git("add", ".gitattributes")
        self.git("commit", "-qm", "gitattributes")
        self.git("merge", "-q", "--no-edit", "b")
        self.git("checkout", "-q", "a")
        arquivo = Path(self.tmp.name) / "gitattributes-da-main"
        arquivo.write_text(self.git("show", "main:.gitattributes").stdout, encoding="utf-8")
        r = self.git("-c", f"core.attributesFile={arquivo}", "merge", "--no-edit", "main", check=False)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        estado = (self.repo / ".specs/project/STATE.md").read_text(encoding="utf-8")
        self.assertIn("Texto da a.", estado)
        self.assertIn("Texto da b.", estado)
        self.assertNotIn("<<<<<<<", estado)


class Licoes(unittest.TestCase):
    """Uma lição por arquivo em .specs/project/licoes/ (arquivo novo não conflita)."""

    pasta = RAIZ / ".specs/project/licoes"

    def test_a_pasta_e_o_aviso_no_state_existem(self):
        self.assertTrue((self.pasta / "LEIA-ME.md").is_file())
        topo = (RAIZ / ".specs/project/STATE.md").read_text(encoding="utf-8")[:1500]
        self.assertIn("licoes/", topo, "o STATE.md perdeu o aviso de que lição nova vai para licoes/")

    def test_nomes_no_formato_data_e_assunto(self):
        nomes = sorted(p.name for p in self.pasta.glob("*.md") if p.name != "LEIA-ME.md")
        self.assertTrue(nomes, "a pasta de lições está vazia")
        for nome in nomes:
            self.assertRegex(nome, r"^\d{4}-\d{2}-\d{2}-[a-z0-9]+(-[a-z0-9]+)*\.md$", f"nome fora do padrão AAAA-MM-DD-assunto.md: {nome}")

    def test_cada_licao_abre_com_a_data_do_nome(self):
        for arquivo in sorted(self.pasta.glob("*.md")):
            if arquivo.name == "LEIA-ME.md":
                continue
            primeira = arquivo.read_text(encoding="utf-8").splitlines()[0]
            self.assertTrue(primeira.startswith(f"# {arquivo.name[:10]} — "), f"{arquivo.name}: a 1ª linha deve ser '# AAAA-MM-DD — Título'")


class ChecksNaFilaDeMesclagem(unittest.TestCase):
    """O checks.yml roda também na fila de mesclagem (merge_group), onde não existe `github.event.pull_request`."""

    texto = (RAIZ / ".github/workflows/checks.yml").read_text(encoding="utf-8")

    def gatilhos(self):
        bloco = self.texto.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        return set(re.findall(r"^  (\w+):", bloco, re.M))

    def test_roda_no_pr_na_main_na_fila_e_no_manual(self):
        self.assertEqual(self.gatilhos(), {"pull_request", "push", "merge_group", "workflow_dispatch"})

    def test_nada_depende_do_objeto_do_pr(self):
        # Na fila esses valores vêm vazios ou nem existem: o passo que os lê quebra só lá, depois do PR aprovado.
        proibidos = r"github\.event\.pull_request|github\.head_ref|github\.base_ref|GITHUB_HEAD_REF|GITHUB_BASE_REF|GITHUB_EVENT_PATH"
        self.assertIsNone(re.search(proibidos, self.texto), "checks.yml lê o contexto do PR; use github.sha/github.ref ou um fallback")
        for nome in sorted(set(re.findall(r"scripts/[\w.-]+", self.texto))):
            arquivo = RAIZ / nome
            if arquivo.is_file() and arquivo.suffix in (".sh", ".py"):
                achado = re.search(proibidos, arquivo.read_text(encoding="utf-8"))
                self.assertIsNone(achado, f"{nome} lê o contexto do PR, que não existe no merge_group")

    def test_a_fila_nunca_cancela_a_rodada_em_curso(self):
        # Só o push novo no mesmo PR cancela a rodada anterior; cada entrada da fila tem a sua ref e termina.
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", self.texto)
        self.assertIn("group: checks-${{ github.ref }}", self.texto)


if __name__ == "__main__":
    unittest.main()

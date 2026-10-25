"""Páginas /doc e /docs do site: o texto vem do doc.json (gerado do app) e chega inteiro ao HTML."""
import copy
import html
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RAIZ = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(RAIZ / "scripts"))
import site_doc  # noqa: E402


def dados_reais() -> dict:
    return site_doc.carregar(RAIZ)


def dados_minimos() -> dict:
    def idioma(sufixo: str) -> dict:
        return {
            "comoFunciona": {"titulo": "Como " + sufixo, "passos": [{"numero": "01", "titulo": "A", "texto": "B"}]},
            "indice": "Índice " + sufixo,
            "secoes": [
                {"id": f"s{i}", "titulo": f"Seção {i} {sufixo}", "blocos": [
                    {"tipo": "paragrafo", "texto": f"Texto {i} <b>&</b>"},
                    {"tipo": "rotulado", "rotulo": "Um", "texto": "dois"},
                    {"tipo": "rotulado", "rotulo": "Três", "texto": "quatro"},
                ]}
                for i in range(1, 6)
            ] + [{"id": "feedback", "titulo": "Feedback", "feedback": True, "blocos": [{"tipo": "paragrafo", "texto": "contato fulano@exemplo.com"}]}],
        }
    return {"idiomas": {"pt": idioma("pt"), "en": idioma("en")}}


class EstruturaDoJson(unittest.TestCase):
    def test_o_json_versionado_e_valido(self):
        self.assertEqual([], site_doc.validar(dados_reais()))

    def test_portugues_e_ingles_tem_as_mesmas_secoes(self):
        d = dados_reais()["idiomas"]
        self.assertEqual([s["id"] for s in d["pt"]["secoes"]], [s["id"] for s in d["en"]["secoes"]])

    def test_acusa_idioma_com_secoes_diferentes(self):
        d = dados_minimos()
        d["idiomas"]["en"]["secoes"].pop(0)
        self.assertTrue(any("seções diferentes" in e for e in site_doc.validar(d)))

    def test_acusa_id_repetido_bloco_invalido_e_falta_de_feedback(self):
        d = dados_minimos()
        d["idiomas"]["pt"]["secoes"][1]["id"] = "s1"
        d["idiomas"]["pt"]["secoes"][2]["blocos"].append({"tipo": "rotulado", "rotulo": "", "texto": "x"})
        d["idiomas"]["en"]["secoes"][-1]["feedback"] = False
        erros = " | ".join(site_doc.validar(d))
        self.assertIn("ids de seção repetidos", erros)
        self.assertIn("bloco inválido", erros)
        self.assertIn("exatamente 1 seção de feedback", erros)


class Renderizacao(unittest.TestCase):
    def setUp(self):
        self.pt = site_doc.renderizar("pt", dados_minimos())

    def test_escapa_o_texto_do_app(self):
        self.assertIn("Texto 1 &lt;b&gt;&amp;&lt;/b&gt;", self.pt)
        self.assertNotIn("<b>", self.pt)

    def test_linhas_rotuladas_seguidas_viram_uma_lista_de_definicao(self):
        self.assertEqual(1, self.pt.split('id="s1"')[1].split("</section>")[0].count('<dl class="doc-lista">'))
        self.assertIn("<div><dt>Um</dt><dd>dois</dd></div>", self.pt)

    def test_cada_secao_esta_nos_dois_indices_e_numerada(self):
        for i in range(1, 6):
            self.assertEqual(2, self.pt.count(f'href="#s{i}"'))
        self.assertIn('<p class="doc-n" aria-hidden="true">03</p>', self.pt)

    def test_feedback_vira_o_contato_do_site_e_protege_e_mails(self):
        secao = self.pt.split('id="feedback"')[1]
        self.assertIn('href="mailto:contato@arateki.com?subject=', secao)
        self.assertNotIn("fulano@exemplo.com", secao)  # o texto do app não vale aqui: no site não há botões de e-mail
        self.assertEqual(secao.count("<!--email_off-->"), secao.count("<!--/email_off-->"))

    def test_e_mail_no_texto_do_app_fica_protegido_da_ofuscacao(self):
        d = dados_minimos()
        d["idiomas"]["pt"]["secoes"][0]["blocos"][0]["texto"] = "escreva a fulano@exemplo.com hoje"
        saida = site_doc.renderizar("pt", d)
        self.assertIn("<!--email_off-->fulano@exemplo.com<!--/email_off-->", saida)

    def test_secao_de_privacidade_leva_a_politica_no_idioma_certo(self):
        d = dados_minimos()
        for c in ("pt", "en"):
            d["idiomas"][c]["secoes"][0]["id"] = "privacidade"
        self.assertIn('href="privacidade.html"', site_doc.renderizar("pt", d))
        self.assertIn('href="privacy.html"', site_doc.renderizar("en", d))


class SiteCompleto(unittest.TestCase):
    """Roda o gerar-site.py de verdade (com toda a validação dele) e confere que o texto do app chegou às páginas."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="site-doc-")
        cls.saida = Path(cls.tmp.name) / "site"
        r = subprocess.run(
            [sys.executable, str(RAIZ / "scripts" / "gerar-site.py"), "--out", str(cls.saida)],
            capture_output=True, text=True,
        )
        cls.resultado = r

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_o_gerador_passa_na_propria_validacao(self):
        self.assertEqual(0, self.resultado.returncode, self.resultado.stderr)

    def test_todo_texto_da_aba_docs_esta_na_pagina_do_idioma(self):
        d = dados_reais()["idiomas"]
        for chave, arquivo in (("pt", "doc.html"), ("en", "docs.html")):
            pagina = (self.saida / arquivo).read_text(encoding="utf-8")
            esperados = [d[chave]["indice"], d[chave]["comoFunciona"]["titulo"]]
            for p in d[chave]["comoFunciona"]["passos"]:
                esperados += [p["titulo"], p["texto"]]
            for s in d[chave]["secoes"]:
                esperados.append(s["titulo"])
                if s.get("feedback"):
                    continue  # no site o Feedback tem o texto do site (contato por e-mail), não os botões do app
                for b in s["blocos"]:
                    esperados += [b["texto"]] + ([b["rotulo"]] if b["tipo"] == "rotulado" else [])
            for texto in esperados:
                self.assertIn(html.escape(texto, quote=False), pagina, f"{arquivo}: falta o texto do app: {texto!r}")

    def test_paginas_geradas_nao_tem_estilo_em_linha_nem_e_mail_pessoal(self):
        for arquivo in ("doc.html", "docs.html"):
            pagina = (self.saida / arquivo).read_text(encoding="utf-8")
            self.assertNotIn("<style", pagina)
            self.assertNotIn(' style="', pagina)
            self.assertNotIn("live.com", pagina)

    def test_a_pagina_inicial_e_o_rodape_levam_a_documentacao(self):
        for arquivo, destino in (("index.html", "doc.html"), ("en.html", "docs.html"), ("404.html", "doc.html"),
                                 ("privacidade.html", "doc.html"), ("privacy.html", "docs.html")):
            pagina = (self.saida / arquivo).read_text(encoding="utf-8")
            self.assertGreaterEqual(pagina.count(f'href="{destino}"'), 2, f"{arquivo}: cabeçalho e rodapé devem ligar a {destino}")

    def test_cabecalho_do_celular_tem_o_atalho_docs_menos_na_propria_documentacao(self):
        for arquivo, destino in (("index.html", "doc.html"), ("en.html", "docs.html"), ("404.html", "doc.html"),
                                 ("privacidade.html", "doc.html"), ("terms.html", "docs.html")):
            pagina = (self.saida / arquivo).read_text(encoding="utf-8")
            self.assertIn(f'<a class="docs-atalho" href="{destino}">Docs', pagina, arquivo)
        for arquivo in ("doc.html", "docs.html"):
            self.assertNotIn("docs-atalho", (self.saida / arquivo).read_text(encoding="utf-8"), arquivo)

    def test_sitemap_e_llms_listam_a_documentacao(self):
        sitemap = (self.saida / "sitemap.xml").read_text(encoding="utf-8")
        llms = (self.saida / "llms.txt").read_text(encoding="utf-8")
        for url in ("https://keyflow.arateki.com/doc", "https://keyflow.arateki.com/docs"):
            self.assertIn(f"<loc>{url}</loc>", sitemap)
            self.assertIn(f"]({url})", llms)


if __name__ == "__main__":
    unittest.main()

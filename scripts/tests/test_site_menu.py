"""Menu do cabeçalho do site no celular (abaixo de 961 px): as 9 páginas geradas têm o botão com os atributos
certos e o painel com os destinos certos de cada página."""
from html.parser import HTMLParser
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

RAIZ = Path(__file__).resolve().parents[2]

# Os destinos do <nav id="menu-topo"> de cada página, na ordem: os mesmos do menu do computador daquela página.
LEGAIS_PT = [("index.html", "Início"), ("doc.html", "Documentação"), ("privacidade.html", "Política de Privacidade"),
             ("termos.html", "Termos de Uso")]
LEGAIS_EN = [("en.html", "Home"), ("docs.html", "Documentation"), ("privacy.html", "Privacy Policy"),
             ("terms.html", "Terms of Use")]
DESTINOS = {
    "index.html": [("#como", "Como funciona"), ("#recursos", "Recursos"), ("#privacidade", "Privacidade"),
                   ("#agente", "O que vem aí"), ("#faq", "Dúvidas"), ("doc.html", "Documentação")],
    "en.html": [("#como", "How it works"), ("#recursos", "Features"), ("#privacidade", "Privacy"),
                ("#agente", "What's next"), ("#faq", "FAQ"), ("docs.html", "Documentation")],
    "doc.html": LEGAIS_PT, "privacidade.html": LEGAIS_PT, "termos.html": LEGAIS_PT, "404.html": LEGAIS_PT,
    "docs.html": LEGAIS_EN, "privacy.html": LEGAIS_EN, "terms.html": LEGAIS_EN,
}
# Páginas que listam o próprio endereço no painel: o link dele leva aria-current="page".
PAGINA_ATUAL = {"doc.html": "doc.html", "docs.html": "docs.html", "privacidade.html": "privacidade.html",
                "termos.html": "termos.html", "privacy.html": "privacy.html", "terms.html": "terms.html"}


class Cabecalho(HTMLParser):
    """Lê só o <header class="topo">: os elementos na ordem em que aparecem e o texto de cada link e botão."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.dentro = False
        self.filhos_diretos = []  # marca, botão, nav e ações, na ordem do HTML
        self.botoes_menu = []
        self.nav = None
        self.links_nav = []
        self.todos_links = []
        self._nav_aberto = False
        self._link = None
        self._botao = None
        self._prof = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "header" and "topo" in (a.get("class") or "").split():
            self.dentro = True
            return
        if not self.dentro:
            return
        self._prof += 1
        classes = (a.get("class") or "").split()
        if tag == "div" and "topo-in" in classes:
            self._topo_in = self._prof
        if tag in ("a", "button", "nav", "div") and self._prof == getattr(self, "_topo_in", -9) + 1:
            self.filhos_diretos.append((tag, classes))
        if tag == "button" and "menu-botao" in classes:
            self._botao = {"attrs": a, "texto": ""}
            self.botoes_menu.append(self._botao)
        if tag == "nav":
            self._nav_aberto = True
            self.nav = a
        if tag == "a":
            self.todos_links.append(a)
            if self._nav_aberto:
                self._link = {"href": a.get("href"), "texto": "", "atual": a.get("aria-current")}
                self.links_nav.append(self._link)

    def handle_endtag(self, tag):
        if not self.dentro:
            return
        if tag == "header":
            self.dentro = False
            return
        self._prof -= 1
        if tag == "nav":
            self._nav_aberto = False
        if tag == "a":
            self._link = None
        if tag == "button":
            self._botao = None

    def handle_data(self, data):
        if not self.dentro:
            return
        if self._link is not None:
            self._link["texto"] += data
        if self._botao is not None:
            self._botao["texto"] += data


def cabecalho(pagina: Path) -> Cabecalho:
    c = Cabecalho()
    c.feed(pagina.read_text(encoding="utf-8"))
    return c


class MenuDoCelular(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="site-menu-")
        cls.saida = Path(cls.tmp.name) / "site"
        cls.resultado = subprocess.run(
            [sys.executable, "-B", str(RAIZ / "scripts" / "gerar-site.py"), "--out", str(cls.saida)],
            capture_output=True, text=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def paginas(self):
        self.assertEqual(0, self.resultado.returncode, self.resultado.stderr)
        self.assertEqual(9, len(DESTINOS))
        for nome in DESTINOS:
            yield nome, self.saida / nome

    def test_as_nove_paginas_tem_o_botao_do_menu_com_os_atributos_certos(self):
        for nome, caminho in self.paginas():
            botoes = cabecalho(caminho).botoes_menu
            self.assertEqual(1, len(botoes), f"{nome}: deve haver um (e só um) botão de menu no cabeçalho")
            a, texto = botoes[0]["attrs"], botoes[0]["texto"].strip()
            self.assertEqual("button", a.get("type"), nome)
            self.assertEqual("false", a.get("aria-expanded"), f"{nome}: o menu nasce fechado")
            self.assertEqual("menu-topo", a.get("aria-controls"), nome)
            self.assertEqual("Menu", texto, f"{nome}: o rótulo do botão")
            self.assertNotIn("aria-label", a, f"{nome}: o nome acessível é o texto visível do botão")

    def test_o_botao_controla_o_nav_do_proprio_cabecalho_e_o_id_e_unico(self):
        for nome, caminho in self.paginas():
            pagina = caminho.read_text(encoding="utf-8")
            self.assertEqual(1, len(re.findall(r'\sid="menu-topo"', pagina)), f"{nome}: id menu-topo repetido ou ausente")
            c = cabecalho(caminho)
            self.assertEqual("menu-topo", (c.nav or {}).get("id"), f"{nome}: o <nav> do cabeçalho deve ter id=menu-topo")
            self.assertTrue((c.nav or {}).get("aria-label"), f"{nome}: o <nav> precisa de nome")

    def test_o_botao_fica_entre_a_marca_e_o_menu_e_o_menu_antes_das_acoes(self):
        for nome, caminho in self.paginas():
            ordem = [(tag, "marca" if "marca" in cl else "botao" if "menu-botao" in cl else "nav" if tag == "nav"
                      else "acoes" if "topo-acoes" in cl else "?") for tag, cl in cabecalho(caminho).filhos_diretos]
            self.assertEqual(["marca", "botao", "nav", "acoes"], [r for _, r in ordem], f"{nome}: ordem do cabeçalho")

    def test_o_painel_tem_os_destinos_certos_de_cada_pagina(self):
        for nome, caminho in self.paginas():
            esperado = DESTINOS[nome]
            obtido = [(l["href"], l["texto"].strip()) for l in cabecalho(caminho).links_nav]
            self.assertEqual(esperado, obtido, f"{nome}: destinos do menu")

    def test_so_o_link_da_propria_pagina_leva_aria_current(self):
        for nome, caminho in self.paginas():
            atuais = [l["href"] for l in cabecalho(caminho).links_nav if l["atual"]]
            self.assertEqual([PAGINA_ATUAL[nome]] if nome in PAGINA_ATUAL else [], atuais, nome)

    def test_ancoras_do_menu_da_pagina_inicial_existem_na_pagina(self):
        for nome in ("index.html", "en.html"):
            pagina = (self.saida / nome).read_text(encoding="utf-8")
            for href, _ in DESTINOS[nome]:
                if href.startswith("#"):
                    self.assertIn(f'id="{href[1:]}"', pagina, f"{nome}: a âncora {href} do menu não existe")

    def test_o_atalho_docs_do_celular_saiu(self):
        for nome, caminho in self.paginas():
            self.assertNotIn("docs-atalho", caminho.read_text(encoding="utf-8"), nome)
        css = (self.saida / "assets/css/site.css").read_text(encoding="utf-8")
        self.assertNotIn("docs-atalho", css)

    def test_o_idioma_e_o_tema_continuam_no_cabeçalho(self):
        for nome, caminho in self.paginas():
            c = cabecalho(caminho)
            self.assertEqual(1, sum(1 for a in c.todos_links if "idioma" in (a.get("class") or "").split()), nome)
            pagina = caminho.read_text(encoding="utf-8")
            self.assertEqual(1, pagina.count('class="tema"'), nome)

    def test_o_script_em_linha_marca_o_html_com_js_em_todas_as_paginas(self):
        for nome, caminho in self.paginas():
            pagina = caminho.read_text(encoding="utf-8")
            scripts = re.findall(r"<script>(.*?)</script>", pagina, re.S)
            self.assertTrue(any("classList.add('js')" in s for s in scripts), f"{nome}: o <html> precisa ganhar a classe js")

    def test_site_js_fecha_o_menu_com_esc_toque_fora_e_link_e_devolve_o_foco(self):
        js = (self.saida / "assets/js/site.js").read_text(encoding="utf-8")
        for trecho in ("aria-expanded", "'Escape'", "menu-aberto", "touchmove", "focus({ preventScroll: true })",
                       "closest('a')", "'focusin'"):
            self.assertIn(trecho, js, f"site.js sem {trecho}")

    def test_o_css_so_liga_o_menu_abaixo_de_961_px_e_respeita_prefers_reduced_motion(self):
        css = (self.saida / "assets/css/site.css").read_text(encoding="utf-8")
        self.assertRegex(css, r"\.menu-botao \{ display: none; \}")
        bloco = css[css.index("Menu do celular."):]
        self.assertIn("@media (max-width: 960px) {", bloco)
        self.assertIn("(max-width: 960px) and (prefers-reduced-motion: reduce)", bloco)
        self.assertIn("html:not(.js)", bloco)  # o caminho sem JavaScript


if __name__ == "__main__":
    unittest.main()

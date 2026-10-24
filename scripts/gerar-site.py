#!/usr/bin/env python3
"""
Monta o site público do KeyFlow (https://keyflow.arateki.com) em build/site/:

- a página inicial e seus arquivos, copiados de website/ (HTML, CSS e JS feitos à mão, sem passo de build);
- as páginas legais, geradas a partir dos Markdown de docs/loja/ (privacidade, termos, privacy, terms).

Depois valida tudo: títulos, idiomas, links internos e âncoras, url() do CSS, o hash do script em linha no
Content-Security-Policy, o orçamento da primeira carga e se cada arquivo tem um content-type conhecido.

Uso:
  python3 scripts/gerar-site.py [--out build/site]
  python3 scripts/gerar-site.py --tipos   # extensão, content-type e cache-control, para o site.yml
"""

import argparse
import base64
import hashlib
import html
import re
import shutil
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

# Content-type e cache de cada extensão publicada. O site.yml sincroniza um tipo por vez com esta tabela
# (o `aws s3 sync` sozinho chutaria os tipos, e o woff2 nem sempre é conhecido). Os HTML vão por último, para
# que a página nova só apareça depois dos arquivos que ela usa. Extensão fora daqui reprova a validação.
TIPOS = [
    ("woff2", "font/woff2", "public, max-age=604800"),
    ("webp", "image/webp", "public, max-age=86400"),
    ("png", "image/png", "public, max-age=86400"),
    ("jpg", "image/jpeg", "public, max-age=86400"),
    ("svg", "image/svg+xml", "public, max-age=3600"),
    ("txt", "text/plain; charset=utf-8", "public, max-age=3600"),
    ("xml", "application/xml; charset=utf-8", "public, max-age=3600"),
    ("css", "text/css; charset=utf-8", "public, max-age=300"),
    ("js", "text/javascript; charset=utf-8", "public, max-age=300"),
    ("html", "text/html; charset=utf-8", "public, max-age=300"),
]

# Páginas legais: as URLs são permanentes (o app e a Play apontam para elas) — não renomeie.
PAGINAS_LEGAIS = ("privacidade.html", "termos.html", "privacy.html", "terms.html")

# Primeira carga da página inicial (HTML + CSS + JS + fontes + favicon). As capturas do teclado são
# preguiçosas e ficam de fora.
ORCAMENTO_PRIMEIRA_CARGA = 400 * 1024

# Script em linha das páginas legais: aplica o tema claro escolhido na página inicial (o padrão é o escuro).
SCRIPT_TEMA = (
    "(function(){try{if(localStorage.getItem('kf-tema')==='claro')"
    "document.documentElement.setAttribute('data-theme','claro')}catch(e){}})();"
)

CSS = """
@font-face { font-family: "Space Grotesk"; src: url("assets/fonts/space-grotesk-400.woff2") format("woff2"); font-weight: 400; font-display: swap; }
@font-face { font-family: "Space Grotesk"; src: url("assets/fonts/space-grotesk-500.woff2") format("woff2"); font-weight: 500; font-display: swap; }
@font-face { font-family: "Space Grotesk"; src: url("assets/fonts/space-grotesk-300.woff2") format("woff2"); font-weight: 300; font-display: swap; }
@font-face { font-family: "Space Mono"; src: url("assets/fonts/space-mono-400.woff2") format("woff2"); font-weight: 400; font-display: swap; }
:root {
  color-scheme: dark;
  --bg: #12100d;
  --text: #f1e7c9;
  --text-muted: #b6ac8c;
  --border: rgba(241, 231, 201, 0.12);
  --heading: #f1e7c9;
  --link: #8fc3b3;
  --link-line: rgba(143, 195, 179, 0.35);
  --code-bg: rgba(241, 231, 201, 0.06);
}
:root[data-theme="claro"] {
  color-scheme: light;
  --bg: #f1e7c9;
  --text: #2b2720;
  --text-muted: #5c5340;
  --border: rgba(43, 39, 32, 0.14);
  --heading: #2b2720;
  --link: #2c6a59;
  --link-line: rgba(44, 106, 89, 0.35);
  --code-bg: rgba(43, 39, 32, 0.06);
}
@media (prefers-reduced-motion: no-preference) {
  @view-transition { navigation: auto; }
}
* { box-sizing: border-box; }
body {
  font-family: "Space Grotesk", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  font-size: 1.0625rem;
  line-height: 1.7;
  color: var(--text);
  background-color: var(--bg);
  margin: 0;
  padding: 0 1rem 4rem;
  -webkit-font-smoothing: antialiased;
}
.container {
  max-width: 760px;
  margin: 0 auto;
}
.nav-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background-color: var(--bg);
  border-bottom: 1px solid var(--border);
  padding: 1.1rem 0;
  margin-bottom: 3rem;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.92rem;
}
.nav-links a {
  margin-right: 1.25rem;
}
.lang-switch {
  font-family: "Space Mono", ui-monospace, monospace;
  font-size: 0.72rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--text-muted);
}
h1, h2, h3, h4 {
  color: var(--heading);
  line-height: 1.25;
}
h1 { font-weight: 300; font-size: clamp(2rem, 1.4rem + 2.4vw, 3rem); letter-spacing: -0.035em; line-height: 1.08; margin-top: 0; margin-bottom: 1rem; }
h2 { font-weight: 500; font-size: 1.3rem; letter-spacing: -0.015em; margin-top: 2.6rem; padding-top: 1.4rem; border-top: 1px solid var(--border); }
h3 { font-weight: 500; font-size: 1.08rem; margin-top: 1.6rem; }
p, ul, ol { margin: 1rem 0; }
li { margin: 0.35rem 0; }
ul, ol { padding-left: 1.5rem; }
ul ul, ol ol, ul ol, ol ul { margin: 0.25rem 0; }
strong { font-weight: 500; }
hr {
  border: 0;
  height: 1px;
  background: var(--border);
  margin: 2.5rem 0;
}
hr + h2 { margin-top: 0; padding-top: 0; border-top: 0; }
a { color: var(--link); text-decoration: none; border-bottom: 1px solid var(--link-line); }
a:hover { border-bottom-color: currentColor; }
a:focus-visible { outline: 2px solid var(--link); outline-offset: 3px; border-radius: 4px; }
code {
  background-color: var(--code-bg);
  padding: 0.15em 0.4em;
  border-radius: 6px;
  font-size: 0.85em;
  font-family: "Space Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
footer {
  margin-top: 4rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--border);
  font-size: 0.85rem;
  color: var(--text-muted);
  text-align: center;
}
"""


def render_markdown(md_text: str) -> str:
    lines = md_text.split('\n')
    out = []
    list_stack = []

    def close_lists_to_indent(target_indent: int):
        while list_stack and list_stack[-1][1] > target_indent:
            ltype, _ = list_stack.pop()
            out.append(f'</{ltype}>')

    def close_all_lists():
        while list_stack:
            ltype, _ = list_stack.pop()
            out.append(f'</{ltype}>')

    def format_inlines(t: str) -> str:
        t = html.escape(t, quote=False)
        t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
        t = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', t)
        t = re.sub(r'\*([^*]+)\*', r'<em>\1</em>', t)
        t = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', t)
        return t

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if stripped in ('---', '***', '___'):
            close_all_lists()
            out.append('<hr>')
            i += 1
            continue

        if stripped.startswith('#'):
            close_all_lists()
            level = len(re.match(r'^#+', stripped).group(0))
            heading_text = stripped[level:].strip()
            out.append(f'<h{level}>{format_inlines(heading_text)}</h{level}>')
            i += 1
            continue

        ul_match = re.match(r'^(\s*)[-*]\s+(.*)$', line)
        if ul_match:
            indent = len(ul_match.group(1))
            item_text = ul_match.group(2)
            if not list_stack or list_stack[-1][1] < indent:
                list_stack.append(('ul', indent))
                out.append('<ul>')
            elif list_stack[-1][1] > indent:
                close_lists_to_indent(indent)
                if not list_stack or list_stack[-1][0] != 'ul':
                    list_stack.append(('ul', indent))
                    out.append('<ul>')
            elif list_stack[-1][0] != 'ul':
                close_all_lists()
                list_stack.append(('ul', indent))
                out.append('<ul>')
            out.append(f'<li>{format_inlines(item_text)}</li>')
            i += 1
            continue

        ol_match = re.match(r'^(\s*)\d+\.\s+(.*)$', line)
        if ol_match:
            indent = len(ol_match.group(1))
            item_text = ol_match.group(2)
            if not list_stack or list_stack[-1][1] < indent:
                list_stack.append(('ol', indent))
                out.append('<ol>')
            elif list_stack[-1][1] > indent:
                close_lists_to_indent(indent)
                if not list_stack or list_stack[-1][0] != 'ol':
                    list_stack.append(('ol', indent))
                    out.append('<ol>')
            elif list_stack[-1][0] != 'ol':
                close_all_lists()
                list_stack.append(('ol', indent))
                out.append('<ol>')
            out.append(f'<li>{format_inlines(item_text)}</li>')
            i += 1
            continue

        close_all_lists()
        para_lines = [stripped]
        while i + 1 < len(lines):
            next_line = lines[i + 1]
            next_stripped = next_line.strip()
            if not next_stripped:
                break
            if next_stripped.startswith(('#', '---', '***')) or re.match(r'^\s*[-*]\s+', next_line) or re.match(r'^\s*\d+\.\s+', next_line):
                break
            i += 1
            para_lines.append(next_stripped)

        if all(p.startswith(('**Última', '**Vigência', '**URL', '**Last', '**Effective', '**Permanent')) for p in para_lines):
            para_text = '<br>\n'.join(format_inlines(p) for p in para_lines)
        else:
            para_text = ' '.join(format_inlines(p) for p in para_lines)
        out.append(f'<p>{para_text}</p>')
        i += 1

    close_all_lists()
    return '\n'.join(out)


def build_page(title: str, lang: str, nav_html: str, body_html: str, footer_html: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)}</title>
  <meta name="color-scheme" content="dark light">
  <meta name="theme-color" content="#12100d">
  <link rel="icon" href="favicon.svg" type="image/svg+xml">
  <link rel="icon" href="assets/img/favicon-32.png" sizes="32x32" type="image/png">
  <link rel="apple-touch-icon" href="assets/img/apple-touch-icon.png">
  <script>{SCRIPT_TEMA}</script>
  <style>{CSS.strip()}</style>
</head>
<body>
  <div class="container">
    <nav class="nav-header">
      {nav_html}
    </nav>
    <main>
      {body_html}
    </main>
    <footer>
      {footer_html}
    </footer>
  </div>
</body>
</html>
"""


def copy_website(repo_root: Path, output_dir: Path) -> list:
    """Copia website/ para a saída. Pastas e arquivos que começam com "_" ou "." são fonte, não vão ao ar."""
    origem = repo_root / "website"
    if not (origem / "index.html").exists():
        raise FileNotFoundError(f"Página inicial não encontrada: {origem / 'index.html'}")
    copiados = []
    for arquivo in sorted(origem.rglob("*")):
        relativo = arquivo.relative_to(origem)
        if any(parte.startswith(("_", ".")) for parte in relativo.parts) or arquivo.is_dir():
            continue
        if relativo.as_posix() in PAGINAS_LEGAIS:
            raise ValueError(f"website/{relativo} colide com uma página legal gerada")
        destino = output_dir / relativo
        destino.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(arquivo, destino)
        copiados.append(relativo.as_posix())
    return copiados


def limpar_saida(repo_root: Path, output_dir: Path):
    """Apaga a geração anterior (o sync usa --delete: sobra velha iria ao ar), com trava contra apagar o que não é."""
    saida = output_dir.resolve()
    raiz = repo_root.resolve()
    if saida == raiz or raiz.is_relative_to(saida) or saida in (raiz / "website", raiz / "docs"):
        raise SystemExit(f"Saída inválida: {saida}")
    if not saida.exists():
        return
    if any(saida.iterdir()) and not (saida / "privacidade.html").exists():
        raise SystemExit(f"{saida} não está vazio e não parece um site gerado; escolha outra saída.")
    shutil.rmtree(saida)


def generate_site(repo_root: Path, output_dir: Path) -> dict:
    docs_loja = repo_root / "docs" / "loja"
    limpar_saida(repo_root, output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    copy_website(repo_root, output_dir)

    footer_pt = """<p>KeyFlow &bull; Arateki LTDA (CNPJ 45.642.122/0001-10) &bull; Fortaleza/CE, Brasil</p>
<p><a href="privacidade.html">Política de Privacidade</a> &bull; <a href="termos.html">Termos de Uso</a> &bull; <a href="mailto:contato@arateki.com">contato@arateki.com</a></p>"""

    footer_en = """<p>KeyFlow &bull; Arateki LTDA (CNPJ 45.642.122/0001-10) &bull; Fortaleza/CE, Brazil</p>
<p><a href="privacy.html">Privacy Policy</a> &bull; <a href="terms.html">Terms of Use</a> &bull; <a href="mailto:contato@arateki.com">contato@arateki.com</a></p>"""

    # 1. privacidade.html
    priv_file = docs_loja / "politica-de-privacidade.md"
    if not priv_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {priv_file}")
    pt_priv_body = render_markdown(priv_file.read_text(encoding="utf-8"))
    nav_pt_priv = """<div class="nav-links">
  <a href="index.html">← Início</a>
  <a href="termos.html">Termos de Uso</a>
</div>
<div class="lang-switch">
  <span>Português</span> | <a href="privacy.html" hreflang="en">English</a>
</div>"""
    priv_html = build_page("Política de Privacidade — KeyFlow", "pt-BR", nav_pt_priv, pt_priv_body, footer_pt)
    (output_dir / "privacidade.html").write_text(priv_html, encoding="utf-8")

    # 2. termos.html
    termos_file = docs_loja / "termos-de-uso.md"
    if not termos_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {termos_file}")
    pt_termos_body = render_markdown(termos_file.read_text(encoding="utf-8"))
    nav_pt_termos = """<div class="nav-links">
  <a href="index.html">← Início</a>
  <a href="privacidade.html">Política de Privacidade</a>
</div>
<div class="lang-switch">
  <span>Português</span> | <a href="terms.html" hreflang="en">English</a>
</div>"""
    termos_html = build_page("Termos de Uso — KeyFlow", "pt-BR", nav_pt_termos, pt_termos_body, footer_pt)
    (output_dir / "termos.html").write_text(termos_html, encoding="utf-8")

    # 3. privacy.html
    privacy_file = docs_loja / "privacy-policy.md"
    if not privacy_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {privacy_file}")
    en_priv_body = render_markdown(privacy_file.read_text(encoding="utf-8"))
    nav_en_priv = """<div class="nav-links">
  <a href="en.html">← Home</a>
  <a href="terms.html">Terms of Use</a>
</div>
<div class="lang-switch">
  <a href="privacidade.html" hreflang="pt-BR">Português</a> | <span>English</span>
</div>"""
    privacy_html = build_page("Privacy Policy — KeyFlow", "en", nav_en_priv, en_priv_body, footer_en)
    (output_dir / "privacy.html").write_text(privacy_html, encoding="utf-8")

    # 4. terms.html
    terms_file = docs_loja / "terms-of-use.md"
    if not terms_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {terms_file}")
    en_terms_body = render_markdown(terms_file.read_text(encoding="utf-8"))
    nav_en_terms = """<div class="nav-links">
  <a href="en.html">← Home</a>
  <a href="privacy.html">Privacy Policy</a>
</div>
<div class="lang-switch">
  <a href="termos.html" hreflang="pt-BR">Português</a> | <span>English</span>
</div>"""
    terms_html = build_page("Terms of Use — KeyFlow", "en", nav_en_terms, en_terms_body, footer_en)
    (output_dir / "terms.html").write_text(terms_html, encoding="utf-8")

    return {nome: output_dir / nome for nome in ("index.html", "en.html") + PAGINAS_LEGAIS}


# ── Validação ──────────────────────────────────────────────────────────────────────────────────────

_ATRIBUTOS_URL = re.compile(r'\s(?:href|src)="([^"]*)"')
_SRCSET = re.compile(r'\ssrcset="([^"]*)"')
_IDS = re.compile(r'\sid="([^"]+)"')
_CSS_URL = re.compile(r'url\(\s*["\']?([^"\')]+)["\']?\s*\)')
_SCRIPTS_EM_LINHA = re.compile(r"<script>(.*?)</script>", re.S)
_CSP = re.compile(r'<meta http-equiv="Content-Security-Policy" content="([^"]+)"')


def _alvo_local(base: Path, url: str, saida: Path):
    """Arquivo que uma URL local aponta, ou None se ela é externa (http, mailto...) ou só uma âncora."""
    partes = urlsplit(url)
    if partes.scheme or partes.netloc or url.startswith("data:"):
        return None
    caminho = unquote(partes.path)
    if not caminho:
        return None
    alvo = (saida / caminho.lstrip("/")) if caminho.startswith("/") else (base.parent / caminho)
    if caminho.endswith("/"):
        alvo = alvo / "index.html"
    return alvo


def _sha256_csp(texto: str) -> str:
    return "'sha256-" + base64.b64encode(hashlib.sha256(texto.encode("utf-8")).digest()).decode() + "'"


def verify_generated_site(generated: dict, output_dir: Path):
    erros = []

    # 1. Páginas legais: títulos exatos e navegação entre elas (as URLs são permanentes).
    expected_titles = {
        "privacidade.html": "Política de Privacidade — KeyFlow",
        "termos.html": "Termos de Uso — KeyFlow",
        "privacy.html": "Privacy Policy — KeyFlow",
        "terms.html": "Terms of Use — KeyFlow",
    }
    expected_links = {
        "privacidade.html": ["index.html", "termos.html", "privacy.html"],
        "termos.html": ["index.html", "privacidade.html", "terms.html"],
        "privacy.html": ["en.html", "terms.html", "privacidade.html"],
        "terms.html": ["en.html", "privacy.html", "termos.html"],
        # a página inicial precisa levar às quatro páginas legais e ao outro idioma
        "index.html": ["privacidade.html", "termos.html", "privacy.html", "terms.html", "en.html"],
        "en.html": ["privacy.html", "terms.html", "privacidade.html", "termos.html", "index.html"],
    }
    expected_lang = {"index.html": "pt-BR", "en.html": "en", "privacidade.html": "pt-BR", "termos.html": "pt-BR",
                     "privacy.html": "en", "terms.html": "en"}
    for name, path in generated.items():
        if not path.exists():
            erros.append(f"Arquivo não gerado: {path}")
            continue
        content = path.read_text(encoding="utf-8")
        if len(content) < 500:
            erros.append(f"Arquivo muito pequeno: {name} ({len(content)} bytes)")
        if name in expected_titles and f"<title>{expected_titles[name]}</title>" not in content:
            erros.append(f"Título incorreto em {name}")
        if name not in expected_titles and not re.search(r"<title>KeyFlow\b[^<]*</title>", content):
            erros.append(f"Título ausente ou sem o nome KeyFlow em {name}")
        if f'<html lang="{expected_lang[name]}"' not in content:
            erros.append(f'Idioma (<html lang="{expected_lang[name]}">) ausente em {name}')
        for tag in ("</html>", "</main>"):
            if tag not in content:
                erros.append(f"Tag {tag} ausente em {name}")
        for link in expected_links[name]:
            if f'href="{link}"' not in content:
                erros.append(f"Link {link} ausente em {name}")

    # 2. Todo HTML publicado: links e recursos locais existem, âncoras existem, CSP confere com os scripts.
    for pagina in sorted(output_dir.rglob("*.html")):
        nome = pagina.relative_to(output_dir).as_posix()
        content = pagina.read_text(encoding="utf-8")
        ids = set(_IDS.findall(content))
        urls = _ATRIBUTOS_URL.findall(content)
        for srcset in _SRCSET.findall(content):
            urls += [item.strip().split(" ")[0] for item in srcset.split(",") if item.strip()]
        for url in urls:
            if url.startswith("#"):
                if len(url) > 1 and url[1:] not in ids:
                    erros.append(f"{nome}: âncora {url} sem id correspondente")
                continue
            alvo = _alvo_local(pagina, url, output_dir)
            if alvo is not None and not alvo.is_file():
                erros.append(f"{nome}: link interno quebrado → {url}")
        csp = _CSP.search(content)
        if csp:
            for script in _SCRIPTS_EM_LINHA.findall(content):
                if _sha256_csp(script) not in csp.group(1):
                    erros.append(f"{nome}: script em linha sem o hash {_sha256_csp(script)} no Content-Security-Policy")
            if "http" in csp.group(1):
                erros.append(f"{nome}: o Content-Security-Policy libera origem externa")

    # 3. CSS: todo url() local existe.
    for folha in sorted(output_dir.rglob("*.css")):
        texto = re.sub(r"""url\(\s*(["'])data:.*?\1\s*\)""", "", folha.read_text(encoding="utf-8"))  # data: não é arquivo
        for url in _CSS_URL.findall(texto):
            alvo = _alvo_local(folha, url, output_dir)
            if alvo is not None and not alvo.is_file():
                erros.append(f"{folha.relative_to(output_dir)}: url() quebrada → {url}")

    # 4. Nada de recurso externo na página inicial (privacidade: sem CDN, fontes remotas ou rastreadores).
    for nome in ("index.html", "en.html"):
        content = (output_dir / nome).read_text(encoding="utf-8")
        for url in re.findall(r'\s(?:src|href)="(https?://[^"]+)"', content):
            permitido = url.startswith(("https://keyflow.arateki.com", "https://arateki.com"))
            if not permitido:
                erros.append(f"{nome}: recurso ou link externo não previsto → {url}")
        if re.search(r'<script[^>]+src="https?://', content):
            erros.append(f"{nome}: script carregado de fora")
        for tag in re.findall(r"<link\s[^>]*>", content):
            rel = re.search(r'rel="([^"]+)"', tag)
            externo = re.search(r'href="https?://', tag)
            if externo and rel and rel.group(1) not in ("canonical", "alternate"):
                erros.append(f"{nome}: <link rel=\"{rel.group(1)}\"> carregado de fora")

    # 5. Content-type conhecido para cada arquivo (o site.yml publica por tipo).
    conhecidas = {ext for ext, _, _ in TIPOS}
    for arquivo in sorted(output_dir.rglob("*")):
        if arquivo.is_file() and arquivo.suffix.lstrip(".") not in conhecidas:
            erros.append(f"Extensão sem content-type em TIPOS: {arquivo.relative_to(output_dir)}")

    # 6. Orçamento da primeira carga da página inicial.
    primeira = [output_dir / "index.html", output_dir / "favicon.svg"]
    primeira += sorted((output_dir / "assets" / "css").glob("*.css"))
    primeira += sorted((output_dir / "assets" / "js").glob("*.js"))
    primeira += sorted((output_dir / "assets" / "fonts").glob("*.woff2"))
    total = sum(p.stat().st_size for p in primeira if p.exists())
    if total > ORCAMENTO_PRIMEIRA_CARGA:
        erros.append(f"Primeira carga com {total / 1024:.0f} KB, acima de {ORCAMENTO_PRIMEIRA_CARGA // 1024} KB")

    if erros:
        for erro in erros:
            print(f"ERRO: {erro}", file=sys.stderr)
        raise SystemExit(f"Validação do site falhou com {len(erros)} erro(s).")

    paginas = len(list(output_dir.rglob("*.html")))
    arquivos = len([p for p in output_dir.rglob("*") if p.is_file()])
    print(f"Verificação concluída com sucesso: {paginas} páginas e {arquivos} arquivos validados; "
          f"primeira carga da página inicial: {total / 1024:.0f} KB (sem compressão).")


def main():
    parser = argparse.ArgumentParser(description="Monta e valida o site público do KeyFlow.")
    parser.add_argument("--out", default="build/site", help="Diretório de saída (padrão: build/site)")
    parser.add_argument("--tipos", action="store_true",
                        help="Só imprime extensão, content-type e cache-control (separados por tab) e sai")
    args = parser.parse_args()

    if args.tipos:
        for ext, tipo, cache in TIPOS:
            print(f"{ext}\t{tipo}\t{cache}")
        return

    repo_root = Path(__file__).resolve().parent.parent
    output_dir = repo_root / args.out

    print(f"Montando o site a partir de {repo_root}/website/ e {repo_root}/docs/loja/ em {output_dir}...")
    generated = generate_site(repo_root, output_dir)
    verify_generated_site(generated, output_dir)
    print("Site gerado com sucesso!")


if __name__ == "__main__":
    main()

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
import json
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

# Script em linha das páginas legais: aplica o tema claro/escuro escolhido (o padrão segue o sistema).
SCRIPT_TEMA = (
    "(function(){try{var t=localStorage.getItem('kf-tema');if(t==='claro'||t==='escuro')"
    "document.documentElement.setAttribute('data-theme',t)}catch(e){}})();"
)


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
        # Protege e-mails contra ofuscação pela Cloudflare com <!--email_off-->...<!--/email_off-->
        t = re.sub(r'<a\s+[^>]*href=["\']mailto:[^"\' >]+["\'][^>]*>.*?</a>', r'<!--email_off-->\g<0><!--/email_off-->', t, flags=re.I)
        partes = re.split(r'(<!--email_off-->.*?<!--/email_off-->)', t, flags=re.S)
        for idx in range(0, len(partes), 2):
            partes[idx] = re.sub(r'\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b', r'<!--email_off-->\g<0><!--/email_off-->', partes[idx])
        return "".join(partes)

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


def build_page(
    title: str,
    lang: str,
    canonical_url: str,
    description: str,
    pt_url: str,
    en_url: str,
    breadcrumb_name: str,
    header_html: str,
    body_html: str,
    footer_html: str,
    skip_text: str = "Pular para o conteúdo",
) -> str:
    csp_hash = _sha256_csp(SCRIPT_TEMA)
    og_locale = "pt_BR" if lang == "pt-BR" else "en_US"
    og_alt_locale = "en_US" if lang == "pt-BR" else "pt_BR"
    home_url = "https://keyflow.arateki.com/" if lang == "pt-BR" else "https://keyflow.arateki.com/en"

    breadcrumb_json = json.dumps(
        {
            "@context": "https://schema.org",
            "@type": "BreadcrumbList",
            "itemListElement": [
                {
                    "@type": "ListItem",
                    "position": 1,
                    "name": "KeyFlow",
                    "item": home_url,
                },
                {
                    "@type": "ListItem",
                    "position": 2,
                    "name": breadcrumb_name,
                    "item": canonical_url,
                },
            ],
        },
        ensure_ascii=False,
        indent=2,
    )

    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' {csp_hash}; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<link rel="canonical" href="{canonical_url}">
<link rel="alternate" hreflang="pt-BR" href="{pt_url}">
<link rel="alternate" hreflang="en" href="{en_url}">
<link rel="alternate" hreflang="x-default" href="{pt_url}">
<meta name="color-scheme" content="dark light">
<meta name="theme-color" content="#12100d">
<meta property="og:type" content="article">
<meta property="og:site_name" content="KeyFlow">
<meta property="og:locale" content="{og_locale}">
<meta property="og:locale:alternate" content="{og_alt_locale}">
<meta property="og:url" content="{canonical_url}">
<meta property="og:title" content="{html.escape(title)}">
<meta property="og:description" content="{html.escape(description)}">
<meta property="og:image" content="https://keyflow.arateki.com/assets/img/og.jpg">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="KeyFlow">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="favicon.svg" type="image/svg+xml">
<link rel="icon" href="assets/img/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="assets/img/apple-touch-icon.png">
<link rel="preload" href="assets/fonts/space-grotesk-300.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="assets/fonts/space-grotesk-400.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="assets/css/site.css">
<script>{SCRIPT_TEMA}</script>
<script type="application/ld+json">
{breadcrumb_json}
</script>
<script src="assets/js/site.js" defer></script>
</head>
<body>
<a class="pular" href="#conteudo">{skip_text}</a>
{header_html}
<main id="conteudo">
  <article class="caixa legal-artigo">
{body_html}
  </article>
</main>
{footer_html}
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


def make_header(lang: str, current_page: str) -> str:
    if lang == "pt-BR":
        home_url = "index.html"
        nav_label = "Navegação"
        home_label = "Início"
        priv_label = "Política de Privacidade"
        termos_label = "Termos de Uso"
        alt_url = "privacy.html" if current_page == "privacidade.html" else "terms.html"
        alt_code = "en"
        alt_text = "EN"
        alt_reader = "English version"
        tema_label = "Alternar tema claro/escuro"
        priv_curr = ' aria-current="page"' if current_page == "privacidade.html" else ""
        termos_curr = ' aria-current="page"' if current_page == "termos.html" else ""
        priv_url = "privacidade.html"
        termos_url = "termos.html"
    else:
        home_url = "en.html"
        nav_label = "Navigation"
        home_label = "Home"
        priv_label = "Privacy Policy"
        termos_label = "Terms of Use"
        alt_url = "privacidade.html" if current_page == "privacy.html" else "termos.html"
        alt_code = "pt-BR"
        alt_text = "PT"
        alt_reader = "versão em português"
        tema_label = "Switch light/dark theme"
        priv_curr = ' aria-current="page"' if current_page == "privacy.html" else ""
        termos_curr = ' aria-current="page"' if current_page == "terms.html" else ""
        priv_url = "privacy.html"
        termos_url = "terms.html"

    return f"""<header class="topo">
  <div class="caixa topo-in">
    <a class="marca" href="{home_url}"><span class="marca-orbe" aria-hidden="true"></span>KeyFlow</a>
    <nav class="nav" aria-label="{nav_label}">
      <a href="{home_url}">{home_label}</a>
      <a href="{priv_url}"{priv_curr}>{priv_label}</a>
      <a href="{termos_url}"{termos_curr}>{termos_label}</a>
    </nav>
    <div class="topo-acoes">
      <a class="idioma" href="{alt_url}" hreflang="{alt_code}" lang="{alt_code}">{alt_text}<span class="so-leitor"> — {alt_reader}</span></a>
      <button class="tema" type="button" aria-pressed="false" aria-label="{tema_label}">
        <svg class="sol" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/></svg>
        <svg class="lua" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" aria-hidden="true"><path d="M20 14.6A8 8 0 0 1 9.4 4a8 8 0 1 0 10.6 10.6Z"/></svg>
      </button>
    </div>
  </div>
</header>"""


def generate_site(repo_root: Path, output_dir: Path) -> dict:
    docs_loja = repo_root / "docs" / "loja"
    limpar_saida(repo_root, output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    copy_website(repo_root, output_dir)

    footer_pt = """<footer class="rodape">
  <div class="caixa rodape-grade">
    <div class="rodape-marca">
      <a class="marca" href="index.html"><span class="marca-orbe" aria-hidden="true"></span>KeyFlow</a>
      <p>Um produto da <a href="https://arateki.com">Arateki</a>, feito em Fortaleza, Brasil.</p>
    </div>
    <nav aria-labelledby="rodape-docs">
      <h2 id="rodape-docs" class="rodape-titulo">Documentos</h2>
      <ul>
        <li><a href="privacidade.html">Política de Privacidade</a></li>
        <li><a href="termos.html">Termos de Uso</a></li>
        <li><a href="privacy.html" hreflang="en" lang="en">Privacy Policy</a></li>
        <li><a href="terms.html" hreflang="en" lang="en">Terms of Use</a></li>
      </ul>
    </nav>
    <div>
      <h2 class="rodape-titulo">Contato</h2>
      <ul><li><!--email_off--><a href="mailto:contato@arateki.com">contato@arateki.com</a><!--/email_off--></li></ul>
    </div>
    <div>
      <h2 class="rodape-titulo">Idioma</h2>
      <ul>
        <li><a href="index.html">Português</a></li>
        <li><a href="en.html" hreflang="en" lang="en">English</a></li>
      </ul>
    </div>
  </div>
  <div class="caixa rodape-base">
    <p>© 2026 Arateki LTDA · CNPJ 45.642.122/0001-10</p>
    <p>Google Play é marca da Google LLC.</p>
  </div>
  <p class="rodape-gigante" aria-hidden="true">KeyFlow</p>
</footer>"""

    footer_en = """<footer class="rodape">
  <div class="caixa rodape-grade">
    <div class="rodape-marca">
      <a class="marca" href="en.html"><span class="marca-orbe" aria-hidden="true"></span>KeyFlow</a>
      <p>A product by <a href="https://arateki.com">Arateki</a>, made in Fortaleza, Brazil.</p>
    </div>
    <nav aria-labelledby="rodape-docs">
      <h2 id="rodape-docs" class="rodape-titulo">Legal</h2>
      <ul>
        <li><a href="privacidade.html" hreflang="pt-BR" lang="pt-BR">Política de Privacidade</a></li>
        <li><a href="termos.html" hreflang="pt-BR" lang="pt-BR">Termos de Uso</a></li>
        <li><a href="privacy.html">Privacy Policy</a></li>
        <li><a href="terms.html">Terms of Use</a></li>
      </ul>
    </nav>
    <div>
      <h2 class="rodape-titulo">Contact</h2>
      <ul><li><!--email_off--><a href="mailto:contato@arateki.com">contato@arateki.com</a><!--/email_off--></li></ul>
    </div>
    <div>
      <h2 class="rodape-titulo">Language</h2>
      <ul>
        <li><a href="index.html" hreflang="pt-BR" lang="pt-BR">Português</a></li>
        <li><a href="en.html">English</a></li>
      </ul>
    </div>
  </div>
  <div class="caixa rodape-base">
    <p>© 2026 Arateki LTDA · CNPJ 45.642.122/0001-10</p>
    <p>Google Play is a trademark of Google LLC.</p>
  </div>
  <p class="rodape-gigante" aria-hidden="true">KeyFlow</p>
</footer>"""

    # 1. privacidade.html
    priv_file = docs_loja / "politica-de-privacidade.md"
    if not priv_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {priv_file}")
    pt_priv_body = render_markdown(priv_file.read_text(encoding="utf-8"))
    header_pt_priv = make_header("pt-BR", "privacidade.html")
    priv_html = build_page(
        "Política de Privacidade — KeyFlow",
        "pt-BR",
        "https://keyflow.arateki.com/privacidade",
        "Política de privacidade do KeyFlow: o teclado processa voz e digitação no aparelho, sem envio para servidores e sem necessidade de cadastro.",
        "https://keyflow.arateki.com/privacidade",
        "https://keyflow.arateki.com/privacy",
        "Política de Privacidade",
        header_pt_priv,
        pt_priv_body,
        footer_pt,
        "Pular para o conteúdo",
    )
    (output_dir / "privacidade.html").write_text(priv_html, encoding="utf-8")

    # 2. termos.html
    termos_file = docs_loja / "termos-de-uso.md"
    if not termos_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {termos_file}")
    pt_termos_body = render_markdown(termos_file.read_text(encoding="utf-8"))
    header_pt_termos = make_header("pt-BR", "termos.html")
    termos_html = build_page(
        "Termos de Uso — KeyFlow",
        "pt-BR",
        "https://keyflow.arateki.com/termos",
        "Termos de uso do KeyFlow: licença de uso do teclado, regras do serviço de voz e compromissos de privacidade da Arateki.",
        "https://keyflow.arateki.com/termos",
        "https://keyflow.arateki.com/terms",
        "Termos de Uso",
        header_pt_termos,
        pt_termos_body,
        footer_pt,
        "Pular para o conteúdo",
    )
    (output_dir / "termos.html").write_text(termos_html, encoding="utf-8")

    # 3. privacy.html
    privacy_file = docs_loja / "privacy-policy.md"
    if not privacy_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {privacy_file}")
    en_priv_body = render_markdown(privacy_file.read_text(encoding="utf-8"))
    header_en_priv = make_header("en", "privacy.html")
    privacy_html = build_page(
        "Privacy Policy — KeyFlow",
        "en",
        "https://keyflow.arateki.com/privacy",
        "KeyFlow Privacy Policy: on-device voice dictation and typing without server logging or required account registration.",
        "https://keyflow.arateki.com/privacidade",
        "https://keyflow.arateki.com/privacy",
        "Privacy Policy",
        header_en_priv,
        en_priv_body,
        footer_en,
        "Skip to content",
    )
    (output_dir / "privacy.html").write_text(privacy_html, encoding="utf-8")

    # 4. terms.html
    terms_file = docs_loja / "terms-of-use.md"
    if not terms_file.exists():
        raise FileNotFoundError(f"Arquivo não encontrado: {terms_file}")
    en_terms_body = render_markdown(terms_file.read_text(encoding="utf-8"))
    header_en_terms = make_header("en", "terms.html")
    terms_html = build_page(
        "Terms of Use — KeyFlow",
        "en",
        "https://keyflow.arateki.com/terms",
        "KeyFlow Terms of Use: keyboard software license, voice dictation service terms, and Arateki commitments.",
        "https://keyflow.arateki.com/termos",
        "https://keyflow.arateki.com/terms",
        "Terms of Use",
        header_en_terms,
        en_terms_body,
        footer_en,
        "Skip to content",
    )
    (output_dir / "terms.html").write_text(terms_html, encoding="utf-8")

    return {nome: output_dir / nome for nome in ("index.html", "en.html") + PAGINAS_LEGAIS}


# ── Validação ──────────────────────────────────────────────────────────────────────────────────────

_ATRIBUTOS_URL = re.compile(r'\s(?:href|src)=(?:"([^"]*)"|\'([^\']*)\')')
_SRCSET = re.compile(r'\ssrcset=(?:"([^"]*)"|\'([^\']*)\')')
_IDS = re.compile(r'\sid=(?:"([^"]+)"|\'([^\']+)\')')
_CSS_URL = re.compile(r'url\(\s*["\']?([^"\')]+)["\']?\s*\)')
_SCRIPTS_EM_LINHA = re.compile(r"<script>(.*?)</script>", re.S)
_CSP = re.compile(r'<meta\s+http-equiv=["\']Content-Security-Policy["\']\s+content=(?:"([^"]*)"|\'([^\']*)\')', re.I)
_META_TAG = re.compile(r'<meta\s+[^>]*>', re.I)
_META_CONTENT = re.compile(r'content=(?:"([^"]*)"|\'([^\']*)\')', re.I)
_STYLE_TAG = re.compile(r'<style\b', re.I)
_STYLE_ATTR = re.compile(r'\sstyle=(?:"([^"]*)"|\'([^\']*)\')', re.I)
_SITEMAP_LOC = re.compile(r'<loc>([^<]+)</loc>')
_XHTML_LINK_HREF = re.compile(r'<xhtml:link\s+[^>]*href=(?:"([^"]*)"|\'([^\']*)\')', re.I)


def _alvo_local(base: Path, url: str, saida: Path):
    """Arquivo que uma URL aponta se for local ou do próprio site (keyflow.arateki.com),
    ou None se for externa a keyflow.arateki.com (mailto, arateki.com...) ou data:.
    Para URLs de keyflow.arateki.com, aplica a regra da Cloudflare:
      '/' vira 'index.html';
      '/x' vira 'x.html';
      caminho com extensão vira o próprio arquivo."""
    partes = urlsplit(url)
    if partes.scheme in ("http", "https"):
        if partes.netloc != "keyflow.arateki.com":
            return None
        caminho = unquote(partes.path)
        if not caminho or caminho == "/":
            return saida / "index.html"
        caminho = caminho.lstrip("/")
        if caminho.endswith("/"):
            return saida / caminho / "index.html"
        alvo = saida / caminho
        if alvo.suffix:
            return alvo
        return saida / f"{caminho}.html"

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

        # Reprova estilo em linha (style="..." ou <style>) bloqueados pela CSP style-src 'self'
        if _STYLE_TAG.search(content):
            erros.append(f"{nome}: tag <style> em linha bloqueada pela Content-Security-Policy")
        if _STYLE_ATTR.search(content):
            erros.append(f"{nome}: atributo style em linha bloqueado pela Content-Security-Policy")

        ids = {g[0] or g[1] for g in _IDS.findall(content)}
        urls = [g[0] or g[1] for g in _ATRIBUTOS_URL.findall(content)]
        for srcset_t in _SRCSET.findall(content):
            srcset = srcset_t[0] or srcset_t[1]
            urls += [item.strip().split(" ")[0] for item in srcset.split(",") if item.strip()]

        for meta in _META_TAG.findall(content):
            m = _META_CONTENT.search(meta)
            if m:
                val = (m.group(1) or m.group(2)).strip()
                if val.startswith(("https://keyflow.arateki.com", "http://keyflow.arateki.com")):
                    urls.append(val)

        for url in urls:
            if url.startswith("#"):
                if len(url) > 1 and url[1:] not in ids:
                    erros.append(f"{nome}: âncora {url} sem id correspondente")
                continue
            alvo = _alvo_local(pagina, url, output_dir)
            if alvo is not None and not alvo.is_file():
                erros.append(f"{nome}: link interno ou URL do site quebrado → {url}")
        csp_match = _CSP.search(content)
        if csp_match:
            csp_content = csp_match.group(1) or csp_match.group(2)
            for script in _SCRIPTS_EM_LINHA.findall(content):
                if _sha256_csp(script) not in csp_content:
                    erros.append(f"{nome}: script em linha sem o hash {_sha256_csp(script)} no Content-Security-Policy")
            if "http" in csp_content:
                erros.append(f"{nome}: o Content-Security-Policy libera origem externa")

    # 2b. Sitemap: URLs absolutas do site em <loc> e <xhtml:link href> existem
    for sitemap in sorted(output_dir.rglob("*.xml")):
        s_nome = sitemap.relative_to(output_dir).as_posix()
        s_content = sitemap.read_text(encoding="utf-8")
        s_urls = _SITEMAP_LOC.findall(s_content)
        s_urls += [g[0] or g[1] for g in _XHTML_LINK_HREF.findall(s_content)]
        for s_url in s_urls:
            s_url = s_url.strip()
            alvo = _alvo_local(sitemap, s_url, output_dir)
            if alvo is not None and not alvo.is_file():
                erros.append(f"{s_nome}: URL no sitemap aponta para arquivo inexistente → {s_url}")

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
        for url in re.findall(r'''\s(?:src|href)=["'](https?://[^"']+)["']''', content):
            permitido = url.startswith(("https://keyflow.arateki.com", "https://arateki.com"))
            if not permitido:
                erros.append(f"{nome}: recurso ou link externo não previsto → {url}")
        if re.search(r'''<script[^>]+src=["']https?://''', content):
            erros.append(f"{nome}: script carregado de fora")
        for tag in re.findall(r"<link\s[^>]*>", content):
            rel = re.search(r'''rel=["']([^"']+)["']''', tag)
            externo = re.search(r'''href=["']https?://''', tag)
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

    # 7. SEO e Metadados Estruturados
    # a) Robots.txt e Sitemap
    robots_path = output_dir / "robots.txt"
    if not robots_path.exists():
        erros.append("robots.txt ausente")
    else:
        robots_txt = robots_path.read_text(encoding="utf-8")
        if "Sitemap: https://keyflow.arateki.com/sitemap.xml" not in robots_txt:
            erros.append("robots.txt sem a declaração do Sitemap")
        if "Content-Signal: search=yes, ai-input=yes, ai-train=yes" not in robots_txt:
            erros.append("robots.txt sem o Content-Signal permitindo treino e busca de IA")
        if "User-agent: *" not in robots_txt or "Allow: /" not in robots_txt:
            erros.append("robots.txt deve permitir acesso global (User-agent: * e Allow: /)")
        if "Disallow:" in robots_txt:
            erros.append("robots.txt contém regras Disallow proibidas (decisão do projeto é liberar todos os robôs)")

    sitemap_path = output_dir / "sitemap.xml"
    if not sitemap_path.exists():
        erros.append("sitemap.xml ausente")
    else:
        sitemap_txt = sitemap_path.read_text(encoding="utf-8")
        urls_sitemap = re.findall(r"<url>(.*?)</url>", sitemap_txt, re.S)
        if not urls_sitemap:
            erros.append("sitemap.xml sem tags <url>")
        for bloco in urls_sitemap:
            m_loc = re.search(r"<loc>(.*?)</loc>", bloco)
            m_lastmod = re.search(r"<lastmod>(.*?)</lastmod>", bloco)
            if not m_loc:
                erros.append("sitemap.xml: entrada <url> sem <loc>")
            if not m_lastmod:
                erros.append(f"sitemap.xml: entrada <url> sem <lastmod> para {m_loc.group(1) if m_loc else 'desconhecido'}")
            elif not re.match(r"^\d{4}-\d{2}-\d{2}$", m_lastmod.group(1).strip()):
                erros.append(f"sitemap.xml: <lastmod> com formato inválido '{m_lastmod.group(1)}' (esperado YYYY-MM-DD)")

    # b) 404.html
    p404 = output_dir / "404.html"
    if not p404.exists():
        erros.append("404.html ausente")
    else:
        c404 = p404.read_text(encoding="utf-8")
        if '<meta name="robots" content="noindex">' not in c404:
            erros.append("404.html sem <meta name=\"robots\" content=\"noindex\">")
        if 'href="index.html"' not in c404 or 'href="en.html"' not in c404:
            erros.append("404.html deve ter links para a página inicial em português e inglês")

    # c) SEO, JSON-LD, Imagens e Hierarquia por página HTML
    titulos_vistos = {}
    descricoes_vistas = {}

    _JSON_LD = re.compile(r'<script\s+type=["\']application/ld\+json["\']>(.*?)</script>', re.S | re.I)
    _IMG_TAG = re.compile(r'<img\b[^>]*>', re.I)
    _ALT_ATTR = re.compile(r'\salt=(?:"[^"]*"|\'[^\']*\')', re.I)

    for pagina in sorted(output_dir.rglob("*.html")):
        nome = pagina.relative_to(output_dir).as_posix()
        content = pagina.read_text(encoding="utf-8")
        content_limpo = re.sub(r"<!--.*?-->", "", content, flags=re.S)

        # Proteção contra ofuscação de e-mail pela Cloudflare
        # Todo e-mail fora de blocos <script> precisa estar dentro de <!--email_off-->...<!--/email_off-->
        sem_scripts = re.sub(r"<script\b[^>]*>.*?</script>", "", content, flags=re.S | re.I)
        sem_emails_protegidos = re.sub(r"<!--email_off-->.*?<!--/email_off-->", "", sem_scripts, flags=re.S)
        for em in re.findall(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", sem_emails_protegidos):
            erros.append(f"{nome}: e-mail '{em}' encontrado fora dos marcadores <!--email_off-->")

        # Título
        m_tit = re.search(r"<title>([^<]+)</title>", content_limpo)
        if not m_tit:
            erros.append(f"{nome}: tag <title> ausente")
        else:
            tit = m_tit.group(1).strip()
            if len(tit) < 10 or len(tit) > 70:
                erros.append(f"{nome}: título com tamanho inválido ({len(tit)} caracteres: '{tit}'); esperado 10 a 70")
            if "KeyFlow" not in tit:
                erros.append(f"{nome}: título sem o nome 'KeyFlow'")
            if nome != "404.html":
                if tit in titulos_vistos:
                    erros.append(f"{nome}: título duplicado com {titulos_vistos[tit]}")
                titulos_vistos[tit] = nome

        # Meta description
        m_desc = None
        for meta_match in re.finditer(r'<meta\b([^>]+)>', content_limpo, re.I):
            m_attrs = {k.lower(): (v1 or v2) for k, v1, v2 in re.findall(r'([\w:-]+)=(?:"([^"]*)"|\'([^\']*)\')', meta_match.group(1))}
            if m_attrs.get("name") == "description" and "content" in m_attrs:
                m_desc = m_attrs["content"].strip()
                break

        if not m_desc:
            erros.append(f"{nome}: meta description ausente")
        else:
            if len(m_desc) < 50 or len(m_desc) > 180:
                erros.append(f"{nome}: meta description com tamanho inválido ({len(m_desc)} caracteres); esperado 50 a 180")
            if nome != "404.html":
                if m_desc in descricoes_vistas:
                    erros.append(f"{nome}: meta description duplicada com {descricoes_vistas[m_desc]}")
                descricoes_vistas[m_desc] = nome

        # Imagens: todas precisam de alt
        for img in _IMG_TAG.findall(content_limpo):
            if not _ALT_ATTR.search(img):
                erros.append(f"{nome}: tag <img> sem atributo alt: {img}")

        # Títulos em hierarquia: exatamente um h1 e sem pular níveis
        h_levels = [int(m.group(1)) for m in re.finditer(r'<h([1-6])\b', content_limpo, re.I)]
        if not h_levels:
            erros.append(f"{nome}: nenhum título (h1-h6) encontrado")
        else:
            h1_count = h_levels.count(1)
            if h1_count != 1:
                erros.append(f"{nome}: esperado exatamente 1 <h1>, encontrado {h1_count}")
            prev = h_levels[0]
            for lvl in h_levels[1:]:
                if lvl > prev + 1:
                    erros.append(f"{nome}: pulou nível de cabeçalho de <h{prev}> para <h{lvl}>")
                prev = lvl

        # Canonical e Hreflang (apenas páginas publicadas do site, exceto 404)
        if nome != "404.html":
            can_url = None
            hreflangs = {}
            for link_match in re.finditer(r'<link\b([^>]+)>', content, re.I):
                l_attrs = {k.lower(): (v1 or v2) for k, v1, v2 in re.findall(r'([\w:-]+)=(?:"([^"]*)"|\'([^\']*)\')', link_match.group(1))}
                if l_attrs.get("rel") == "canonical" and "href" in l_attrs:
                    can_url = l_attrs["href"].strip()
                elif l_attrs.get("rel") == "alternate" and "hreflang" in l_attrs and "href" in l_attrs:
                    hreflangs[l_attrs["hreflang"].strip()] = l_attrs["href"].strip()

            if not can_url:
                erros.append(f"{nome}: canonical ausente")
            elif not can_url.startswith("https://keyflow.arateki.com"):
                erros.append(f"{nome}: canonical deve apontar para https://keyflow.arateki.com (foi '{can_url}')")

            for req_lang in ("pt-BR", "en", "x-default"):
                if req_lang not in hreflangs:
                    erros.append(f"{nome}: hreflang '{req_lang}' ausente")
                else:
                    h_target = _alvo_local(pagina, hreflangs[req_lang], output_dir)
                    if h_target is not None and not h_target.is_file():
                        erros.append(f"{nome}: hreflang '{req_lang}' aponta para arquivo inexistente → {hreflangs[req_lang]}")

        # JSON-LD
        blocos_jsonld = _JSON_LD.findall(content)
        if nome in ("index.html", "en.html", "privacidade.html", "termos.html", "privacy.html", "terms.html"):
            if not blocos_jsonld:
                erros.append(f"{nome}: bloco de dados estruturados JSON-LD ausente")

        for b_raw in blocos_jsonld:
            try:
                dados = json.loads(b_raw)
            except Exception as e:
                erros.append(f"{nome}: JSON-LD inválido ({e})")
                continue

            b_str = json.dumps(dados).lower()
            if '"offers"' in b_str or '"price"' in b_str or '"pricecurrency"' in b_str:
                erros.append(f"{nome}: JSON-LD contém campo de preço/offers proibido pela política do projeto")

            itens = dados.get("@graph", [dados]) if isinstance(dados, dict) else []
            for item in itens:
                if not isinstance(item, dict):
                    continue
                itype = item.get("@type")
                if not itype:
                    erros.append(f"{nome}: item no JSON-LD sem @type")
                    continue
                if itype == "Organization":
                    if not item.get("name") or not item.get("url"):
                        erros.append(f"{nome}: Organization no JSON-LD deve ter name e url")
                elif itype == "WebSite":
                    if not item.get("name") or not item.get("url"):
                        erros.append(f"{nome}: WebSite no JSON-LD deve ter name e url")
                elif itype in ("SoftwareApplication", "MobileApplication"):
                    if not item.get("name") or not item.get("operatingSystem") or not item.get("applicationCategory"):
                        erros.append(f"{nome}: {itype} no JSON-LD deve ter name, operatingSystem e applicationCategory")
                elif itype == "FAQPage":
                    perguntas = item.get("mainEntity", [])
                    if not isinstance(perguntas, list) or not perguntas:
                        erros.append(f"{nome}: FAQPage no JSON-LD sem perguntas em mainEntity")
                    for p in perguntas:
                        if not p.get("name") or not p.get("acceptedAnswer", {}).get("text"):
                            erros.append(f"{nome}: FAQPage tem pergunta sem name ou acceptedAnswer.text")
                elif itype == "BreadcrumbList":
                    elementos = item.get("itemListElement", [])
                    if not isinstance(elementos, list) or not elementos:
                        erros.append(f"{nome}: BreadcrumbList no JSON-LD sem elementos")
                    for el in elementos:
                        if "position" not in el or not el.get("name") or not el.get("item"):
                            erros.append(f"{nome}: BreadcrumbList item incompleto (precisa de position, name e item)")

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

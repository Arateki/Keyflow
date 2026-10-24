#!/usr/bin/env python3
"""
Gera as páginas estáticas públicas do KeyFlow a partir dos arquivos Markdown em docs/loja/.
Uso: python3 scripts/gerar-site.py [--out build/site]
"""

import argparse
import html
import os
import re
import sys
from pathlib import Path

CSS = """
:root {
  --bg: #ffffff;
  --text: #1f2328;
  --text-muted: #656d76;
  --border: #d0d7de;
  --heading: #0f1419;
  --link: #0969da;
  --link-hover: #0550ae;
  --code-bg: #f6f8fa;
  --nav-bg: #f6f8fa;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1117;
    --text: #e6edf3;
    --text-muted: #8b949e;
    --border: #30363d;
    --heading: #ffffff;
    --link: #4493f8;
    --link-hover: #79c0ff;
    --code-bg: #161b22;
    --nav-bg: #161b22;
  }
}
* { box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  line-height: 1.6;
  color: var(--text);
  background-color: var(--bg);
  margin: 0;
  padding: 1.5rem 1rem 3rem;
}
.container {
  max-width: 800px;
  margin: 0 auto;
}
.nav-header {
  border: 1px solid var(--border);
  background-color: var(--nav-bg);
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 2rem;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.9rem;
}
.nav-links a {
  margin-right: 1rem;
}
.lang-switch {
  font-weight: 500;
}
h1, h2, h3, h4 {
  color: var(--heading);
  line-height: 1.3;
}
h1 { font-size: 2rem; margin-top: 0; margin-bottom: 0.75rem; }
h2 { font-size: 1.35rem; margin-top: 2rem; border-bottom: 1px solid var(--border); padding-bottom: 0.3rem; }
h3 { font-size: 1.15rem; margin-top: 1.5rem; }
p, ul, ol { margin: 1rem 0; }
li { margin: 0.35rem 0; }
ul, ol { padding-left: 1.5rem; }
ul ul, ol ol, ul ol, ol ul { margin: 0.25rem 0; }
hr {
  border: 0;
  height: 1px;
  background: var(--border);
  margin: 2rem 0;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; }
code {
  background-color: var(--code-bg);
  padding: 0.2em 0.4em;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
}
footer {
  margin-top: 3rem;
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


def generate_site(repo_root: Path, output_dir: Path) -> dict:
    docs_loja = repo_root / "docs" / "loja"
    output_dir.mkdir(parents=True, exist_ok=True)

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
  <a href="index.html">← Home</a>
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
  <a href="index.html">← Home</a>
  <a href="privacy.html">Privacy Policy</a>
</div>
<div class="lang-switch">
  <a href="termos.html" hreflang="pt-BR">Português</a> | <span>English</span>
</div>"""
    terms_html = build_page("Terms of Use — KeyFlow", "en", nav_en_terms, en_terms_body, footer_en)
    (output_dir / "terms.html").write_text(terms_html, encoding="utf-8")

    # 5. index.html
    index_body = """
<h1>KeyFlow &mdash; Documentos Legais e Privacidade</h1>
<p>Documentos oficiais do aplicativo <strong>KeyFlow</strong>, desenvolvido por <strong>Arateki LTDA</strong> (CNPJ 45.642.122/0001-10, Fortaleza/CE, Brasil).</p>

<hr>

<h2>Português (Brasil)</h2>
<ul>
  <li><a href="privacidade.html"><strong>Política de Privacidade</strong></a> &mdash; Vigência a partir de 23 de outubro de 2026. Detalhes sobre o que tratamos (áudio efêmero sob demanda, raciocínio local ou nuvem, chaves protegidas no Keystore) e o que nunca coletamos.</li>
  <li><a href="termos.html"><strong>Termos de Uso</strong></a> &mdash; Vigência a partir de 23 de outubro de 2026. Condições de uso, compra única de R$ 15 no Google Play, regras de reembolso (CDC) e responsabilidades de skins e provedores externos.</li>
</ul>

<h2>English (Global)</h2>
<ul>
  <li><a href="privacy.html"><strong>Privacy Policy</strong></a> &mdash; Effective as of October 23, 2026. Transparency on audio processing, local Keystore encryption, zero keystroke collection, and third-party AI integrations.</li>
  <li><a href="terms.html"><strong>Terms of Use</strong></a> &mdash; Effective as of October 23, 2026. Licensing terms, one-time purchase, refund guidelines, and third-party integrations.</li>
</ul>
"""
    nav_index = """<div class="nav-links">
  <strong>KeyFlow</strong> &bull; Documentos Oficiais
</div>
<div class="lang-switch">
  <a href="privacidade.html">PT</a> | <a href="privacy.html">EN</a>
</div>"""
    index_html = build_page("KeyFlow — Políticas e Termos / Privacy & Terms", "pt-BR", nav_index, index_body, footer_pt)
    (output_dir / "index.html").write_text(index_html, encoding="utf-8")

    generated = {
        "privacidade.html": output_dir / "privacidade.html",
        "termos.html": output_dir / "termos.html",
        "privacy.html": output_dir / "privacy.html",
        "terms.html": output_dir / "terms.html",
        "index.html": output_dir / "index.html",
    }
    return generated


def verify_generated_site(generated: dict):
    expected_titles = {
        "privacidade.html": "Política de Privacidade — KeyFlow",
        "termos.html": "Termos de Uso — KeyFlow",
        "privacy.html": "Privacy Policy — KeyFlow",
        "terms.html": "Terms of Use — KeyFlow",
        "index.html": "KeyFlow — Políticas e Termos / Privacy &amp; Terms",
    }
    expected_links = {
        "privacidade.html": ["index.html", "termos.html", "privacy.html"],
        "termos.html": ["index.html", "privacidade.html", "terms.html"],
        "privacy.html": ["index.html", "terms.html", "privacidade.html"],
        "terms.html": ["index.html", "privacy.html", "termos.html"],
        "index.html": ["privacidade.html", "termos.html", "privacy.html", "terms.html"],
    }
    for name, path in generated.items():
        assert path.exists(), f"Arquivo não gerado: {path}"
        content = path.read_text(encoding="utf-8")
        assert len(content) > 500, f"Arquivo muito pequeno: {path} ({len(content)} bytes)"
        expected_title = expected_titles[name]
        assert f"<title>{expected_title}</title>" in content, f"Título incorreto em {name}"
        assert "</html>" in content, f"Tag </html> ausente em {name}"
        assert "</main>" in content, f"Tag </main> ausente em {name}"
        for link in expected_links[name]:
            assert f'href="{link}"' in content, f"Link {link} ausente em {name}"
    print(f"Verificação concluída com sucesso: {len(generated)} páginas geradas e validadas.")


def main():
    parser = argparse.ArgumentParser(description="Gera o site estático das políticas e termos do KeyFlow.")
    parser.add_argument("--out", default="build/site", help="Diretório de saída (padrão: build/site)")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    output_dir = repo_root / args.out

    print(f"Gerando site a partir de {repo_root}/docs/loja/ para {output_dir}...")
    generated = generate_site(repo_root, output_dir)
    verify_generated_site(generated)
    print("Site gerado com sucesso!")


if __name__ == "__main__":
    main()

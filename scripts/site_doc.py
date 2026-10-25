"""
Páginas de documentação do site do KeyFlow: /doc (português) e /docs (inglês).

O texto NÃO é escrito aqui nem em website/: vem de website/_fonte/doc.json, que o teste de unidade
`DocsSiteExportTest` gera a partir da aba Docs do app (DocsPanel.kt e as traduções). O teste falha quando o JSON
não bate com o app, então o site não fica para trás — e este módulo só transforma esse JSON em HTML, no desenho
da página inicial (site.css). Os textos de moldura da página (título, chamada, aviso de feedback) são do site e
ficam em TEXTOS, nos dois idiomas.

O gerar-site.py monta o cabeçalho, o rodapé e o <head>; aqui sai só o miolo do <main>.
"""

import html
import json
import re
from pathlib import Path
from urllib.parse import quote

ARQUIVO_JSON = Path("website") / "_fonte" / "doc.json"

CONTATO = "contato@arateki.com"

# Idiomas do site, na ordem do JSON. "arquivo" é o que vai para o R2; a Cloudflare serve /doc e /docs a partir
# de doc.html e docs.html (a mesma regra de /privacidade e /privacy).
IDIOMAS = ("pt", "en")

TEXTOS = {
    "pt": {
        "lang": "pt-BR",
        "arquivo": "doc.html",
        "url": "https://keyflow.arateki.com/doc",
        "titulo_pagina": "Documentação — KeyFlow",
        "descricao": "Guia do KeyFlow: modos, abas de configuração, gestos da orbe, teclado, pausa, erros, voz no aparelho, privacidade e licenças.",
        "migalha": "Documentação",
        "rotulo": "Documentação",
        "h1_a": "Como usar",
        "h1_b": "o KeyFlow.",
        "lide": "O mesmo guia que está na aba Docs do aplicativo, para ler no computador ou no celular: "
                "modos, ajustes, gestos, teclado e tudo o que acontece quando você fala.",
        "privacidade": ("Ler a política de privacidade", "privacidade.html"),
        "feedback": "Achou um problema ou tem uma ideia? Escreva para a gente contando o que aconteceu e o que você "
                    "esperava. Dentro do app, os botões de Feedback abrem o seu e-mail com a versão do app e o "
                    "modelo do aparelho já preenchidos.",
        "assunto": "KeyFlow — documentação",
    },
    "en": {
        "lang": "en",
        "arquivo": "docs.html",
        "url": "https://keyflow.arateki.com/docs",
        "titulo_pagina": "Documentation — KeyFlow",
        "descricao": "KeyFlow guide: modes, settings tabs, orb gestures, keyboard, pause, errors, on-device voice, privacy and licenses.",
        "migalha": "Documentation",
        "rotulo": "Documentation",
        "h1_a": "How to use",
        "h1_b": "KeyFlow.",
        "lide": "The same guide that lives in the app's Docs tab, to read on your computer or phone: "
                "modes, settings, gestures, the keyboard, and everything that happens when you speak.",
        "privacidade": ("Read the privacy policy", "privacy.html"),
        "feedback": "Found a problem or have an idea? Write to us and tell us what happened and what you expected. "
                    "Inside the app, the Feedback buttons open your email app with the app version and device "
                    "model already filled in.",
        "assunto": "KeyFlow — documentation",
    },
}

_ID = re.compile(r"^[a-z][a-z0-9-]*$")
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")


def carregar(repo_root: Path) -> dict:
    caminho = repo_root / ARQUIVO_JSON
    if not caminho.is_file():
        raise FileNotFoundError(
            f"{ARQUIVO_JSON} não existe: rode o DocsSiteExportTest com KEYFLOW_REGERAR_DOC_SITE=1 (ver o teste)"
        )
    return json.loads(caminho.read_text(encoding="utf-8"))


def validar(dados: dict) -> list:
    """Erros de estrutura do JSON (lista vazia = ok): o gerador não deve publicar uma página capenga."""
    erros = []
    idiomas = dados.get("idiomas")
    if not isinstance(idiomas, dict):
        return ["doc.json: falta o objeto 'idiomas'"]
    ids_por_idioma = {}
    for chave in IDIOMAS:
        d = idiomas.get(chave)
        if not isinstance(d, dict):
            erros.append(f"doc.json: idioma '{chave}' ausente")
            continue
        como = d.get("comoFunciona") or {}
        if not como.get("titulo") or not como.get("passos"):
            erros.append(f"doc.json[{chave}]: 'comoFunciona' sem título ou sem passos")
        for passo in como.get("passos", []):
            if not all(passo.get(c) for c in ("numero", "titulo", "texto")):
                erros.append(f"doc.json[{chave}]: passo incompleto {passo}")
        if not d.get("indice"):
            erros.append(f"doc.json[{chave}]: sem o título do índice")
        secoes = d.get("secoes") or []
        if len(secoes) < 5:
            erros.append(f"doc.json[{chave}]: seções demais faltando ({len(secoes)})")
        ids = [s.get("id", "") for s in secoes]
        ids_por_idioma[chave] = ids
        if len(set(ids)) != len(ids):
            erros.append(f"doc.json[{chave}]: ids de seção repetidos")
        for s in secoes:
            if not _ID.match(s.get("id", "")):
                erros.append(f"doc.json[{chave}]: id de seção inválido '{s.get('id')}'")
            if not s.get("titulo"):
                erros.append(f"doc.json[{chave}]: seção '{s.get('id')}' sem título")
            if not s.get("blocos"):
                erros.append(f"doc.json[{chave}]: seção '{s.get('id')}' sem blocos")
            for b in s.get("blocos", []):
                tipo = b.get("tipo")
                if tipo == "paragrafo":
                    ok = bool(b.get("texto"))
                elif tipo == "rotulado":
                    ok = bool(b.get("rotulo")) and bool(b.get("texto"))
                else:
                    ok = False
                if not ok:
                    erros.append(f"doc.json[{chave}]: bloco inválido em '{s.get('id')}': {b}")
        if sum(1 for s in secoes if s.get("feedback")) != 1:
            erros.append(f"doc.json[{chave}]: esperada exatamente 1 seção de feedback")
    if len(ids_por_idioma) == len(IDIOMAS) and len({tuple(v) for v in ids_por_idioma.values()}) != 1:
        erros.append("doc.json: português e inglês com seções diferentes (ids ou ordem)")
    return erros


def _t(texto: str) -> str:
    """Texto do JSON para dentro do HTML: escapa e protege e-mails da ofuscação da Cloudflare."""
    seguro = html.escape(texto, quote=False)
    return _EMAIL.sub(lambda m: f"<!--email_off-->{m.group(0)}<!--/email_off-->", seguro)


def _numero(i: int) -> str:
    return f"{i + 1:02d}"


def _indice(secoes: list) -> str:
    itens = "\n".join(
        f'        <li><a href="#{s["id"]}"><span class="doc-n" aria-hidden="true">{_numero(i)}</span>'
        f'<span>{_t(s["titulo"])}</span></a></li>'
        for i, s in enumerate(secoes)
    )
    return itens


def _blocos(blocos: list) -> str:
    """Parágrafos soltos e, em sequência, as linhas rotuladas agrupadas numa lista de definição."""
    saida = []
    grupo = []

    def fechar():
        if grupo:
            itens = "\n".join(
                f'        <div><dt>{_t(r)}</dt><dd>{_t(x)}</dd></div>' for r, x in grupo
            )
            saida.append(f'      <dl class="doc-lista">\n{itens}\n      </dl>')
            grupo.clear()

    for b in blocos:
        if b["tipo"] == "rotulado":
            grupo.append((b["rotulo"], b["texto"]))
        else:
            fechar()
            saida.append(f'      <p>{_t(b["texto"])}</p>')
    fechar()
    return "\n".join(saida)


def _secao(i: int, s: dict, txt: dict) -> str:
    extra = ""
    if s["id"] == "privacidade":
        rotulo, destino = txt["privacidade"]
        extra = f'\n      <a class="link-seta" href="{destino}">{_t(rotulo)} <span aria-hidden="true">→</span></a>'
    if s.get("feedback"):
        # No app esta seção tem botões que abrem o e-mail com o diagnóstico; no site o contato é o do produto.
        corpo = f'      <p>{_t(txt["feedback"])}</p>'
        extra = (
            f'\n      <div class="acoes"><!--email_off--><a class="botao" href="mailto:{CONTATO}?subject={quote(txt["assunto"])}">'
            f'<span>{CONTATO}</span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" '
            f'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg></a><!--/email_off--></div>'
        )
    else:
        corpo = _blocos(s["blocos"])
    return f"""    <section id="{s['id']}" class="doc-secao" aria-labelledby="{s['id']}-titulo">
      <p class="doc-n" aria-hidden="true">{_numero(i)}</p>
      <h2 id="{s['id']}-titulo" class="doc-titulo">{_t(s['titulo'])}</h2>
      <div class="doc-cartao">
{corpo}{extra}
      </div>
    </section>"""


def renderizar(chave: str, dados: dict) -> str:
    """Miolo do <main> da página de documentação em `chave` ("pt" ou "en")."""
    txt = TEXTOS[chave]
    d = dados["idiomas"][chave]
    secoes = d["secoes"]
    indice = _indice(secoes)
    como = d["comoFunciona"]
    passos = "\n".join(
        f'          <li><span class="passo-n" aria-hidden="true">{_t(p["numero"])}</span>'
        f'<div><h3>{_t(p["titulo"])}</h3><p>{_t(p["texto"])}</p></div></li>'
        for p in como["passos"]
    )
    blocos_secoes = "\n".join(_secao(i, s, txt) for i, s in enumerate(secoes))
    return f"""  <header class="doc-topo">
    <div class="caixa">
      <div class="doc-orbe" aria-hidden="true"></div>
      <p class="selo"><span class="selo-ponto" aria-hidden="true"></span>{_t(txt['rotulo'])}</p>
      <h1 class="titulo-secao">{_t(txt['h1_a'])} <span class="tenue">{_t(txt['h1_b'])}</span></h1>
      <p class="cabeca-texto">{_t(txt['lide'])}</p>
    </div>
  </header>

  <div class="caixa doc-grade">
    <aside class="doc-lateral">
      <nav class="doc-indice" aria-labelledby="doc-indice-titulo">
        <h2 id="doc-indice-titulo" class="doc-indice-titulo">{_t(d['indice'])}</h2>
        <ol>
{indice}
        </ol>
      </nav>
    </aside>

    <div class="doc-conteudo">
      <section class="doc-como" aria-labelledby="doc-como-titulo">
        <h2 id="doc-como-titulo" class="doc-indice-titulo">{_t(como['titulo'])}</h2>
        <ol class="passos">
{passos}
        </ol>
      </section>

      <nav class="doc-indice-movel" aria-label="{html.escape(d['indice'])}">
        <details>
          <summary>{_t(d['indice'])}</summary>
          <ol>
{indice}
          </ol>
        </details>
      </nav>

{blocos_secoes}
    </div>
  </div>"""

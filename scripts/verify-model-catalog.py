#!/usr/bin/env python3
"""Confere o catálogo de modelos (ModelCatalog.kt) contra a API pública do Hugging Face.

Para cada `ModelFile` do catálogo, sem token (é assim que o app baixa):
  1. a URL tem a forma https://huggingface.co/<dono>/<repo>/resolve/<commit de 40 hex>/<arquivo>;
  2. o repositório existe, não é gated e a licença do cartão não é GPL/AGPL/LGPL nem "não comercial";
  3. o commit existe e o arquivo está nele (`/api/models/<repo>/tree/<commit>`);
  4. o tamanho bate; o SHA-256 bate (arquivo LFS: o `lfs.oid` da API é o SHA-256 do conteúdo; arquivo
     fora do LFS e pequeno: baixa e calcula);
  5. a URL aceita um pedido de faixa (`Range: bytes=0-0` → 206), que é o que a retomada do download usa.

Depende de rede, por isso não faz parte do `testDebugUnitTest`: roda à mão ou no workflow
`.github/workflows/catalogo-modelos.yml` (não bloqueia PR). O teste de unidade `PinSanityTest` cobre, sem rede, a
forma dos pins (40 hex, sem padrão sequencial).

Uso:
    python3 scripts/verify-model-catalog.py [--catalogo CAMINHO] [--estrito]

Saída 0 = tudo conferido; 1 = alguma divergência ou entrada inexistente. Com --estrito, licença a revisar (sem
licença no cartão, termos próprios como Llama e Gemma) também sai com 1.
Nunca usa HF_TOKEN nem nenhuma credencial: um download que só funciona com login falha aqui, como falharia no app.
"""
import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HF = "https://huggingface.co"
CATALOGO_PADRAO = Path(__file__).resolve().parent.parent / "app/src/main/java/com/keyflow/data/models/ModelCatalog.kt"
# Arquivo fora do LFS só é baixado inteiro para o SHA-256 se for pequeno (tokens.txt e afins).
LIMITE_SEM_LFS = 8 * 1024 * 1024
UA = "keyflow-verify-model-catalog/1"

URL_RE = re.compile(r"^https://huggingface\.co/([^/\s]+/[^/\s]+)/resolve/([0-9a-f]{40})/(.+)$")
# Licença proibida pela regra do produto: copyleft forte/fraco ou "não comercial".
LICENCA_PROIBIDA = re.compile(r"(^|[-_])(a|l)?gpl|(^|[-_])nc([-_]|$)|non-?commercial|noncommercial", re.I)
LICENCA_LIVRE = re.compile(r"^(apache-2\.0|mit|bsd-[0-9]-clause|isc|cc0-1\.0|cc-by-4\.0|cc-by-sa-4\.0|unlicense|zlib)$", re.I)
# Repositórios de conversão do sherpa-onnx não trazem licença no cartão: vale a do modelo original.
ORIGEM_DA_LICENCA = {
    "csukuangfj/sherpa-onnx-whisper-base": "openai/whisper-base",
    "csukuangfj/sherpa-onnx-whisper-small": "openai/whisper-small",
    "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8": "nvidia/parakeet-tdt-0.6b-v3",
}


# ── Leitura do ModelCatalog.kt ──────────────────────────────────────────────────────────────────────────────


def argumentos_de_chamada(texto: str, abre: int) -> tuple[list[str], int]:
    """Argumentos de nível zero da chamada cujo '(' está em `abre`; devolve também a posição depois do ')'."""
    args, atual, fundo, i, em_texto = [], [], 0, abre + 1, False
    while i < len(texto):
        c = texto[i]
        if em_texto:
            atual.append(c)
            if c == "\\":
                atual.append(texto[i + 1])
                i += 1
            elif c == '"':
                em_texto = False
        elif c == '"':
            em_texto = True
            atual.append(c)
        elif c == "(":
            fundo += 1
            atual.append(c)
        elif c == ")":
            if fundo == 0:
                ultimo = "".join(atual).strip()
                if ultimo:  # vírgula final antes do ')' não é argumento
                    args.append(ultimo)
                return args, i + 1
            fundo -= 1
            atual.append(c)
        elif c == "," and fundo == 0:
            args.append("".join(atual).strip())
            atual = []
        else:
            atual.append(c)
        i += 1
    raise ValueError("chamada ModelFile( sem fechar")


def ler_catalogo(caminho: Path) -> list[dict]:
    texto = caminho.read_text(encoding="utf-8")
    consts: dict[str, str] = {}
    for m in re.finditer(r'const val (\w+)\s*=\s*("(?:[^"\\]|\\.)*"|[0-9_]+L?)', texto):
        valor = m.group(2)
        consts[m.group(1)] = valor[1:-1] if valor.startswith('"') else valor

    def resolve(expr: str) -> str:
        expr = expr.strip()
        if expr.startswith('"') and expr.endswith('"'):
            return re.sub(r"\$(\w+)", lambda r: resolve(r.group(1)), expr[1:-1])
        if expr in consts:
            return consts[expr]
        return expr

    specs = [(m.start(), m.group(1)) for m in re.finditer(r"val (\w+)\s*=\s*ModelSpec\(", texto)]
    arquivos = []
    for m in re.finditer(r"(?<!class )\bModelFile\(", texto):
        args, _ = argumentos_de_chamada(texto, m.end() - 1)
        if len(args) != 5:
            raise ValueError(f"ModelFile com {len(args)} argumentos perto de {texto[m.start():m.start() + 60]!r}")
        dono = next((nome for pos, nome in reversed(specs) if pos < m.start()), "?")
        arquivos.append({
            "modelo": dono,
            "papel": args[0].replace("FileRole.", ""),
            "arquivo": resolve(args[1]),
            "url": resolve(args[2]),
            "sha256": resolve(args[3]),
            "bytes": int(resolve(args[4]).rstrip("L").replace("_", "")),
        })
    # Sentinela: se o catálogo passar a declarar arquivo de um jeito que este leitor não entende, falha em vez de
    # conferir menos do que existe.
    esperados = len(re.findall(r"(?<!class )\bModelFile\(", texto))
    if esperados != len(arquivos) or not arquivos:
        raise ValueError(f"li {len(arquivos)} arquivos, mas o fonte tem {esperados} chamadas ModelFile(")
    return arquivos


# ── Rede ────────────────────────────────────────────────────────────────────────────────────────────────────


def pedir(url: str, cabecalho_extra: dict | None = None):
    """GET anônimo com 3 tentativas para 5xx/rede. Devolve (status, corpo-ou-resposta, cabeçalhos)."""
    ultimo = None
    for tentativa in range(3):
        req = urllib.request.Request(url, headers={"User-Agent": UA, **(cabecalho_extra or {})})
        try:
            resp = urllib.request.urlopen(req, timeout=60)
            return resp.status, resp, dict(resp.headers)
        except urllib.error.HTTPError as e:
            if e.code < 500:
                return e.code, None, dict(e.headers)
            ultimo = e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            ultimo = e
        time.sleep(1.5 * (tentativa + 1))
    raise RuntimeError(f"sem resposta de {url}: {ultimo}")


def json_de(url: str):
    status, resp, cab = pedir(url)
    if status != 200:
        return status, None, cab
    with resp:
        return status, json.load(resp), cab


def arvore(repo: str, commit: str):
    """Todos os arquivos do commit (a API pagina por `Link: rel=next`)."""
    url = f"{HF}/api/models/{repo}/tree/{commit}?recursive=true"
    itens = []
    while url:
        status, dados, cab = json_de(url)
        if status != 200:
            return status, None
        itens.extend(dados)
        link = cab.get("Link") or cab.get("link") or ""
        proximo = re.search(r'<([^>]+)>;\s*rel="next"', link)
        url = proximo.group(1) if proximo else None
    return 200, {i["path"]: i for i in itens if i.get("type") == "file"}


def sha256_baixando(url: str) -> tuple[str, int]:
    status, resp, _ = pedir(url)
    if status != 200:
        raise RuntimeError(f"download de {url} devolveu {status}")
    h, total = hashlib.sha256(), 0
    with resp:
        while chunk := resp.read(1 << 20):
            h.update(chunk)
            total += len(chunk)
    return h.hexdigest(), total


# ── Conferência ─────────────────────────────────────────────────────────────────────────────────────────────


def licenca_de(repo: str, cartao: dict) -> tuple[str, str, str | None]:
    """(nível, texto, origem): nível é 'ok', 'revisar' ou 'proibida'."""
    licenca = (cartao.get("cardData") or {}).get("license")
    origem = None
    if not licenca and repo in ORIGEM_DA_LICENCA:
        origem = ORIGEM_DA_LICENCA[repo]
        status, dados, _ = json_de(f"{HF}/api/models/{origem}")
        licenca = ((dados or {}).get("cardData") or {}).get("license") if status == 200 else None
    if not licenca:
        return "revisar", "sem licença no cartão", origem
    if isinstance(licenca, list):
        licenca = ",".join(licenca)
    if LICENCA_PROIBIDA.search(licenca):
        return "proibida", licenca, origem
    if LICENCA_LIVRE.match(licenca):
        return "ok", licenca, origem
    return "revisar", f"{licenca} (termos próprios)", origem


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--catalogo", type=Path, default=CATALOGO_PADRAO)
    ap.add_argument("--estrito", action="store_true", help="licença a revisar também reprova")
    opcoes = ap.parse_args()

    arquivos = ler_catalogo(opcoes.catalogo)
    print(f"Catálogo: {opcoes.catalogo} — {len(arquivos)} arquivos em {len({a['modelo'] for a in arquivos})} modelos\n")

    falhas, avisos = 0, 0
    cartoes: dict[str, tuple] = {}
    arvores: dict[tuple[str, str], tuple] = {}
    licencas_vistas: dict[str, tuple] = {}

    def falhou(a, motivo):
        nonlocal falhas
        falhas += 1
        print(f"  FALHOU  {a['modelo']:<18} {a['arquivo']}: {motivo}")

    for a in arquivos:
        m = URL_RE.match(a["url"])
        if not m:
            falhou(a, "URL fora do padrão https://huggingface.co/<dono>/<repo>/resolve/<commit de 40 hex>/<arquivo>")
            continue
        repo, commit, caminho = m.groups()
        if urllib.parse.unquote(caminho) != a["arquivo"] and not caminho.endswith("/" + a["arquivo"]):
            falhou(a, f"a URL termina em {caminho!r}, o catálogo diz {a['arquivo']!r}")
            continue
        if not re.fullmatch(r"[0-9a-f]{64}", a["sha256"]):
            falhou(a, "sha256 não tem 64 hex minúsculos")
            continue

        if repo not in cartoes:
            status, dados, _ = json_de(f"{HF}/api/models/{repo}")
            cartoes[repo] = (status, dados)
        status, cartao = cartoes[repo]
        if status != 200:
            falhou(a, f"repositório {repo} não existe ou exige login (HTTP {status})")
            continue
        if cartao.get("gated"):
            falhou(a, f"repositório {repo} é gated ({cartao['gated']}): o app baixa sem token")
            continue
        if repo not in licencas_vistas:
            licencas_vistas[repo] = licenca_de(repo, cartao)
        nivel = licencas_vistas[repo][0]
        if nivel == "proibida":
            falhou(a, f"licença proibida: {licencas_vistas[repo][1]}")
            continue

        if (repo, commit) not in arvores:
            arvores[(repo, commit)] = arvore(repo, commit)
        status, arq = arvores[(repo, commit)]
        if status != 200:
            falhou(a, f"o commit {commit[:12]} não existe em {repo} (HTTP {status})")
            continue
        entrada = arq.get(caminho)
        if entrada is None:
            falhou(a, f"o arquivo {caminho!r} não está no commit {commit[:12]}")
            continue

        problemas = []
        if entrada["size"] != a["bytes"]:
            problemas.append(f"tamanho: catálogo {a['bytes']}, HF {entrada['size']}")
        lfs = entrada.get("lfs")
        if lfs:
            real, origem_sha = lfs["oid"], "LFS"
        elif entrada["size"] <= LIMITE_SEM_LFS:
            real, tam = sha256_baixando(a["url"])
            origem_sha = "baixado"
            if tam != a["bytes"]:
                problemas.append(f"tamanho baixado: {tam}, catálogo {a['bytes']}")
        else:
            falhou(a, f"arquivo de {entrada['size']} B fora do LFS: sem SHA-256 público para conferir")
            continue
        if real != a["sha256"]:
            problemas.append(f"sha256: catálogo {a['sha256'][:16]}…, HF ({origem_sha}) {real[:16]}…")

        status, resp, cab = pedir(a["url"], cabecalho_extra={"Range": "bytes=0-0"})
        if resp is not None:
            resp.close()
        if status != 206:
            problemas.append(f"pedido de faixa devolveu {status}, o downloader espera 206")

        if problemas:
            falhou(a, "; ".join(problemas))
        else:
            print(f"  ok      {a['modelo']:<18} {a['arquivo']:<44} {a['bytes']:>14,} B  sha256 {origem_sha:<7} faixa 206".replace(",", "."))

    print("\nLicenças (cartão do Hugging Face; repositório de conversão herda a do modelo original):")
    for repo, (nivel, texto, origem) in licencas_vistas.items():
        marca = {"ok": "ok     ", "revisar": "REVISAR", "proibida": "PROIBIDA"}[nivel]
        extra = f" (via {origem})" if origem else ""
        print(f"  {marca} {repo}: {texto}{extra}")
        if nivel == "revisar":
            avisos += 1

    print(f"\n{len(arquivos)} arquivos conferidos: {falhas} falha(s), {avisos} licença(s) a revisar.")
    if falhas or (opcoes.estrito and avisos):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Gera o ícone do KeyFlow a partir da orbe parada (estado Idle do estilo Ateliê).

A orbe é desenhada em `VoiceOrb.kt` (`drawOrb`): um brilho, o corpo e o miolo, três blobs vivos com degradê
radial, mais um pontinho de luz. Este script reproduz esse desenho (mesmas cores, degradês, proporções e o mesmo
instante da galeria de verificação, `GALLERY_ORB_TIME_MS`, para a forma bater com `verification/golden/orb-idle.png`)
e produz, de uma só fonte:

  - os VectorDrawables do ícone adaptativo (API 26+):
      app/src/main/res/drawable/ic_launcher_background.xml
      app/src/main/res/drawable/ic_launcher_foreground.xml
      app/src/main/res/drawable/ic_launcher_monochrome.xml   (ícone temático do Android 13+)
  - os PNGs legados (API 24 e 25), quadrado e redondo, de mdpi a xxxhdpi:
      app/src/main/res/mipmap-<densidade>/ic_launcher.png e ic_launcher_round.png
  - o ícone da Play Store (512x512, 32 bits, opaco; não entra no APK):
      docs/loja/icone-512.png
  - opcionalmente a prancha de prévia (`--previa`), com as máscaras círculo, squircle e quadrado arredondado.

Os XMLs `mipmap-anydpi-v26/ic_launcher.xml` e `ic_launcher_round.xml` são fixos (só apontam para os três
drawables) e não passam por aqui.

Uso (do diretório do repositório; só precisa do Pillow):

    python3 scripts/gerar-icone.py                     # escreve XMLs, PNGs e o ícone da loja
    python3 scripts/gerar-icone.py --fundo claro       # a variante de fundo creme
    python3 scripts/gerar-icone.py --previa /tmp/keyflow-icone/previa.png --so-previa

Zona segura: o adaptativo tem 108 dp, a máscara do launcher mostra no máximo 72 dp no centro e só 66 dp (círculo)
é garantido. O script confere que o brilho da orbe cabe nos 66 dp.

Para trocar a cor do fundo ou o tamanho da orbe, mexa nas constantes `FUNDOS` e `RAIO_CORPO` aqui e rode de novo:
os XMLs e os PNGs saem sempre juntos, para nunca divergirem.
"""

import argparse
import math
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("Falta o Pillow (python3 -m pip install pillow, de preferência num venv).")

RAIZ = Path(__file__).resolve().parent.parent
RES = RAIZ / "app/src/main/res"
LOJA = RAIZ / "docs/loja"

# --- A orbe (VoiceOrb.kt, drawOrb) ---------------------------------------------------------------------------------
# Paleta Ateliê (paleta A). ARGB.
BRILHO_DENTRO, BRILHO_FORA = 0x667EB8A8, 0x007EB8A8
CORPO_DENTRO, CORPO_FORA = 0xFF9AD0BF, 0xFF4E9683
MIOLO_DENTRO, MIOLO_FORA = 0xFFF4ECCF, 0xFFBFE0D4
ALFA_CORPO, ALFA_MIOLO, ALFA_LUZ = 0.96, 0.95, 0.30

# Idle: amplitude e velocidade base, e o nível de `orbLevel` no instante da galeria (t = 1234 ms).
T_MS = 1234.0
AMP, VEL = 0.05, 0.55
NIVEL = 0.55 + 0.08 * math.sin(T_MS * 0.002)

# --- O ícone -------------------------------------------------------------------------------------------------------
VIEW = 108.0  # dp do canvas adaptativo
CENTRO = VIEW / 2
JANELA_DP = 72.0  # o que a máscara mais larga (quadrado arredondado) deixa ver
ZONA_SEGURA_DP = 66.0  # o círculo garantido
RAIO_CORPO = 26.0  # raio do corpo da orbe, em dp (no canvas de 108)

# Fundo: degradê radial suave, na mesma família do papel do app (Palette.kt: bg escuro 2B2720 e claro F1E7C9).
FUNDOS = {
    "escuro": dict(dentro=0xFF3A342B, fora=0xFF211E19, cx=0.40, cy=0.36, raio=0.95),
    "claro": dict(dentro=0xFFF7EFD6, fora=0xFFE3D6AE, cx=0.40, cy=0.36, raio=0.95),
}

# Ícone temático (monochrome): só o alfa conta; o sistema pinta com a cor do tema.
MONO_ALFA_BRILHO, MONO_ALFA_MIOLO = 0.22, 0.34

K = 24  # px por dp do mestre (2592 px)
SUPER = 2  # as máscaras dos blobs saem em 2x e descem para o mestre (serrilhado some)
ESCALA_PNG_LEGADO = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
MARGEM_LEGADO = 0.92  # a forma ocupa 44/48 do lado, como o Image Asset do Android Studio


# --- Geometria -----------------------------------------------------------------------------------------------------
def raio_blob(a, amp, vel, semente):
    """Raio relativo do blob no ângulo `a` (blobPath de VoiceOrb.kt: três senoides defasadas)."""
    return 1.0 + amp * (
        math.sin(a * 3 + T_MS * 0.0016 * vel + semente)
        + 0.5 * math.sin(a * 5 - T_MS * 0.0021 * vel + semente * 1.7)
        + 0.3 * math.sin(a * 2 + T_MS * 0.0027 * vel)
    )


class Camada:
    """Um dos três blobs: raio (dp), amplitude, velocidade, semente, cores do degradê e alfa."""

    def __init__(self, nome, raio, amp, vel, semente, dentro, fora, alfa):
        self.nome, self.raio, self.amp, self.vel, self.semente = nome, raio, amp, vel, semente
        self.dentro, self.fora, self.alfa = dentro, fora, alfa

    def ponto(self, a):
        r = self.raio * raio_blob(a, self.amp, self.vel, self.semente)
        return CENTRO + math.cos(a) * r, CENTRO + math.sin(a) * r

    def pontos(self, n):
        return [self.ponto(2 * math.pi * i / n) for i in range(n)]

    def degrade(self):
        """Centro e raio do degradê (drawLayer: centro deslocado em (-0,25r, -0,30r), raio 1,25r)."""
        return CENTRO - self.raio * 0.25, CENTRO - self.raio * 0.30, self.raio * 1.25


def camadas():
    r = RAIO_CORPO
    return [
        Camada("brilho", r * 1.12, AMP * 0.7, VEL * 0.8, 5.0, BRILHO_DENTRO, BRILHO_FORA, 1.0),
        Camada("corpo", r, AMP * NIVEL, VEL, 2.0, CORPO_DENTRO, CORPO_FORA, ALFA_CORPO),
        Camada("miolo", r * 0.66, AMP * 0.7, VEL * 1.35, 9.0, MIOLO_DENTRO, MIOLO_FORA, ALFA_MIOLO),
    ]


def luz():
    """O pontinho de luz no canto do corpo: (cx, cy, raio) em dp."""
    r = RAIO_CORPO
    return CENTRO - r * 0.28, CENTRO - r * 0.32, r * 0.16


def com_alfa(argb, alfa):
    a = round(((argb >> 24) & 0xFF) * alfa)
    return (a << 24) | (argb & 0xFFFFFF)


# --- XML -----------------------------------------------------------------------------------------------------------
def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def caminho_bezier(pts):
    """Curva fechada suave por todos os pontos (Catmull-Rom → Bézier cúbica)."""
    n = len(pts)
    out = [f"M{num(pts[0][0])},{num(pts[0][1])}"]
    for i in range(n):
        p0, p1, p2, p3 = pts[(i - 1) % n], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append(f"C{num(c1[0])},{num(c1[1])} {num(c2[0])},{num(c2[1])} {num(p2[0])},{num(p2[1])}")
    out.append("Z")
    return "".join(out)


def caminho_circulo(cx, cy, r):
    return f"M{num(cx - r)},{num(cy)}a{num(r)},{num(r)} 0 1,0 {num(2 * r)},0a{num(r)},{num(r)} 0 1,0 {num(-2 * r)},0Z"


N_BEZIER = 60


def erro_bezier(camada):
    """Maior distância (dp) entre a curva Bézier do XML e o blob exato (amostrado denso)."""
    pts = camada.pontos(N_BEZIER)
    n = len(pts)
    pior = 0.0
    for i in range(n):
        p0, p1, p2, p3 = pts[(i - 1) % n], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        for k in range(1, 8):
            t = k / 8
            u = 1 - t
            x = u**3 * p1[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t**3 * p2[0]
            y = u**3 * p1[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t**3 * p2[1]
            a = math.atan2(y - CENTRO, x - CENTRO)
            exato = camada.raio * raio_blob(a, camada.amp, camada.vel, camada.semente)
            pior = max(pior, abs(math.hypot(x - CENTRO, y - CENTRO) - exato))
    return pior


CABECALHO = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<!-- Gerado por scripts/gerar-icone.py a partir da orbe parada (VoiceOrb.kt). Não edite à mão: mude o script. -->\n"
)
NS_VETOR = (
    'xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108"'
)


def cor_xml(argb):
    return f"#{argb & 0xFFFFFFFF:08X}"


def preenchimento_radial(cx, cy, raio, dentro, fora, recuo):
    pad = " " * recuo
    return (
        f'{pad}<aapt:attr name="android:fillColor">\n'
        f'{pad}    <gradient\n'
        f'{pad}        android:type="radial"\n'
        f'{pad}        android:centerX="{num(cx)}"\n'
        f'{pad}        android:centerY="{num(cy)}"\n'
        f'{pad}        android:gradientRadius="{num(raio)}">\n'
        f'{pad}        <item android:offset="0" android:color="{cor_xml(dentro)}" />\n'
        f'{pad}        <item android:offset="1" android:color="{cor_xml(fora)}" />\n'
        f'{pad}    </gradient>\n'
        f'{pad}</aapt:attr>\n'
    )


def xml_fundo(tema):
    f = FUNDOS[tema]
    return (
        CABECALHO
        + f"<vector {NS_VETOR}>\n"
        + '    <path android:pathData="M0,0h108v108h-108z">\n'
        + preenchimento_radial(f["cx"] * VIEW, f["cy"] * VIEW, f["raio"] * VIEW, f["dentro"], f["fora"], 8)
        + "    </path>\n</vector>\n"
    )


def xml_frente():
    out = [CABECALHO, f"<vector {NS_VETOR}>\n"]
    for c in camadas():
        cx, cy, raio = c.degrade()
        out.append(f'    <!-- {c.nome} -->\n    <path android:pathData="{caminho_bezier(c.pontos(N_BEZIER))}">\n')
        out.append(preenchimento_radial(cx, cy, raio, com_alfa(c.dentro, c.alfa), com_alfa(c.fora, c.alfa), 8))
        out.append("    </path>\n")
    lx, ly, lr = luz()
    out.append(
        f'    <!-- ponto de luz -->\n    <path\n        android:fillColor="{cor_xml(com_alfa(0xFFFFFFFF, ALFA_LUZ))}"\n'
        f'        android:pathData="{caminho_circulo(lx, ly, lr)}" />\n'
    )
    out.append("</vector>\n")
    return "".join(out)


def xml_mono():
    brilho, corpo, miolo = camadas()
    lx, ly, lr = luz()
    anel = caminho_bezier(corpo.pontos(N_BEZIER)) + caminho_bezier(miolo.pontos(N_BEZIER))
    return (
        CABECALHO
        + f"<vector {NS_VETOR}>\n"
        + f'    <!-- brilho, bem fraco -->\n    <path\n        android:fillColor="#FF000000"\n        android:fillAlpha="{MONO_ALFA_BRILHO}"\n'
        + f'        android:pathData="{caminho_bezier(brilho.pontos(N_BEZIER))}" />\n'
        + f'    <!-- corpo menos o miolo: o aro -->\n    <path\n        android:fillColor="#FF000000"\n        android:fillType="evenOdd"\n'
        + f'        android:pathData="{anel}" />\n'
        + f'    <!-- miolo, translúcido -->\n    <path\n        android:fillColor="#FF000000"\n        android:fillAlpha="{MONO_ALFA_MIOLO}"\n'
        + f'        android:pathData="{caminho_bezier(miolo.pontos(N_BEZIER))}" />\n'
        + f'    <!-- ponto de luz -->\n    <path\n        android:fillColor="#FF000000"\n'
        + f'        android:pathData="{caminho_circulo(lx, ly, lr)}" />\n'
        + "</vector>\n"
    )


# --- Raster (Pillow) -----------------------------------------------------------------------------------------------
W = int(VIEW * K)


def mascara_poligono(pts):
    """Máscara 'L' do mestre para um polígono (em dp), com antisserrilhado por supersampling."""
    g = Image.new("L", (W * SUPER, W * SUPER), 0)
    ImageDraw.Draw(g).polygon([(x * K * SUPER, y * K * SUPER) for x, y in pts], fill=255)
    return g.resize((W, W), Image.LANCZOS)


def mascara_circulo(cx, cy, r):
    g = Image.new("L", (W * SUPER, W * SUPER), 0)
    ImageDraw.Draw(g).ellipse(
        [(cx - r) * K * SUPER, (cy - r) * K * SUPER, (cx + r) * K * SUPER, (cy + r) * K * SUPER], fill=255
    )
    return g.resize((W, W), Image.LANCZOS)


# `Image.radial_gradient` vale distância × √2 (0 no centro, 255 no canto): na borda do quadrado, onde fica o raio, são 181.
RAIO_NO_GRADIENTE = 128 * math.sqrt(2)


def lut(c0, c1):
    """Quatro tabelas (R, G, B, A) de 256 entradas, interpolando as cores sem pré-multiplicar (como o Skia)."""
    canais = []
    for sh in (16, 8, 0, 24):
        a, b = (c0 >> sh) & 0xFF, (c1 >> sh) & 0xFF
        canais.append([round(a + (b - a) * min(1.0, v / RAIO_NO_GRADIENTE)) for v in range(256)])
    return canais


def degrade_radial(cx, cy, raio, c0, c1):
    """Imagem RGBA do mestre: degradê radial `c0`→`c1` (grampeado fora do raio), centro e raio em dp."""
    d = max(2, int(round(2 * raio * K)))
    g = Image.radial_gradient("L").resize((d, d), Image.BICUBIC)
    base = Image.new("L", (W, W), 255)
    base.paste(g, (int(round((cx - raio) * K)), int(round((cy - raio) * K))))
    t = lut(c0, c1)
    return Image.merge("RGBA", [base.point(t[0]), base.point(t[1]), base.point(t[2]), base.point(t[3])])


def aplicar_mascara(rgba, mascara):
    r, g, b, a = rgba.split()
    return Image.merge("RGBA", (r, g, b, ImageChops.multiply(a, mascara)))


def raster_fundo(tema):
    f = FUNDOS[tema]
    return degrade_radial(f["cx"] * VIEW, f["cy"] * VIEW, f["raio"] * VIEW, f["dentro"], f["fora"])


def raster_frente():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    for c in camadas():
        cx, cy, raio = c.degrade()
        cor = degrade_radial(cx, cy, raio, com_alfa(c.dentro, c.alfa), com_alfa(c.fora, c.alfa))
        img = Image.alpha_composite(img, aplicar_mascara(cor, mascara_poligono(c.pontos(720))))
    lx, ly, lr = luz()
    branco = Image.new("RGBA", (W, W), (255, 255, 255, round(255 * ALFA_LUZ)))
    return Image.alpha_composite(img, aplicar_mascara(branco, mascara_circulo(lx, ly, lr)))


def raster_mono():
    """Alfa do ícone temático (imagem 'L'): o mesmo que o XML do monochrome."""
    brilho, corpo, miolo = camadas()
    m_brilho = mascara_poligono(brilho.pontos(720)).point(lambda v: round(v * MONO_ALFA_BRILHO))
    m_corpo, m_miolo = mascara_poligono(corpo.pontos(720)), mascara_poligono(miolo.pontos(720))
    anel = ImageChops.subtract(m_corpo, m_miolo)
    lx, ly, lr = luz()
    alfa = ImageChops.lighter(m_brilho, anel)
    alfa = ImageChops.lighter(alfa, m_miolo.point(lambda v: round(v * MONO_ALFA_MIOLO)))
    return ImageChops.lighter(alfa, mascara_circulo(lx, ly, lr))


def mestre(tema):
    return Image.alpha_composite(raster_fundo(tema), raster_frente())


def mono_pintado(alfa, fundo, tinta):
    """O ícone temático como o launcher o mostra: fundo chapado e o alfa pintado com a cor do tema."""
    base = Image.new("RGBA", (W, W), fundo)
    return Image.alpha_composite(base, aplicar_mascara(Image.new("RGBA", (W, W), tinta), alfa))


# --- Máscaras e exportação -----------------------------------------------------------------------------------------
def janela(img, dp=JANELA_DP):
    px = int(round(dp * K))
    off = (W - px) // 2
    return img.crop((off, off, off + px, off + px))


def forma(nome, tam, ss=4):
    """Máscara 'L' (tam x tam) de uma forma de ícone, ocupando o quadrado todo."""
    g = Image.new("L", (tam * ss, tam * ss), 0)
    d = ImageDraw.Draw(g)
    s = tam * ss
    if nome == "circulo":
        d.ellipse([0, 0, s - 1, s - 1], fill=255)
    elif nome == "squircle":
        n = 4.5
        pts = []
        for i in range(720):
            a = 2 * math.pi * i / 720
            c, sn = math.cos(a), math.sin(a)
            x = math.copysign(abs(c) ** (2 / n), c)
            y = math.copysign(abs(sn) ** (2 / n), sn)
            pts.append(((x + 1) / 2 * (s - 1), (y + 1) / 2 * (s - 1)))
        d.polygon(pts, fill=255)
    elif nome == "quadrado":
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.24), fill=255)
    else:
        raise ValueError(nome)
    return g.resize((tam, tam), Image.LANCZOS)


def recortar(img_janela, nome, tam):
    """A janela de 72 dp reduzida a `tam` px e recortada pela forma (RGBA com transparência fora)."""
    pequena = img_janela.resize((tam, tam), Image.LANCZOS)
    r, g, b, a = pequena.split()
    return Image.merge("RGBA", (r, g, b, ImageChops.multiply(a, forma(nome, tam))))


def legado(img_janela, nome, tam):
    """PNG legado: forma com margem, sobre canvas transparente."""
    lado = int(round(tam * MARGEM_LEGADO))
    peca = recortar(img_janela, nome, lado)
    tela = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    off = (tam - lado) // 2
    tela.paste(peca, (off, off))
    return tela


def conferir_zona_segura():
    pior = max(
        max(math.hypot(x - CENTRO, y - CENTRO) for x, y in c.pontos(720)) for c in camadas()
    )
    if pior > ZONA_SEGURA_DP / 2:
        sys.exit(f"O brilho da orbe passa da zona segura: {pior:.1f} dp de raio > {ZONA_SEGURA_DP / 2:.0f}.")
    return pior


# --- Escrita -------------------------------------------------------------------------------------------------------
def escrever(caminho, texto):
    caminho.parent.mkdir(parents=True, exist_ok=True)
    caminho.write_text(texto, encoding="utf-8")


def gerar_repositorio(tema):
    escrever(RES / "drawable/ic_launcher_background.xml", xml_fundo(tema))
    escrever(RES / "drawable/ic_launcher_foreground.xml", xml_frente())
    escrever(RES / "drawable/ic_launcher_monochrome.xml", xml_mono())
    jan = janela(mestre(tema))
    for dens, tam in ESCALA_PNG_LEGADO.items():
        destino = RES / f"mipmap-{dens}"
        destino.mkdir(parents=True, exist_ok=True)
        legado(jan, "quadrado", tam).save(destino / "ic_launcher.png", optimize=True)
        legado(jan, "circulo", tam).save(destino / "ic_launcher_round.png", optimize=True)
    LOJA.mkdir(parents=True, exist_ok=True)
    loja = jan.resize((512, 512), Image.LANCZOS).convert("RGBA")  # 32 bits; o fundo já é opaco (alfa 255)
    loja.save(LOJA / "icone-512.png", optimize=True)


# --- Prévia --------------------------------------------------------------------------------------------------------
def fonte(tam, negrito=False):
    nome = "space_grotesk_semibold.ttf" if negrito else "space_grotesk_medium.ttf"
    try:
        return ImageFont.truetype(str(RES / "font" / nome), tam)
    except OSError:
        return ImageFont.load_default()


def gerar_previa(destino, tema):
    claro, escuro = "claro", "escuro"
    outro = escuro if tema == claro else claro
    jan = {t: janela(mestre(t)) for t in (claro, escuro)}
    alfa = raster_mono()

    def mono_janela(fundo, tinta):
        return janela(mono_pintado(alfa, fundo, tinta))

    # Temas do Android 13+ (tons Material You de exemplo): fundo tonal e tinta.
    mono_claro = mono_janela((214, 232, 224, 255), (14, 62, 50, 255))
    mono_escuro = mono_janela((38, 66, 58, 255), (186, 234, 218, 255))
    mono_alfa = mono_janela((255, 255, 255, 255), (0, 0, 0, 255))

    tam, marg, colunas = 168, 36, 4
    larg = marg + colunas * (tam + marg)
    painel_h = tam + 74
    titulos = 5
    ultimo_h = 96 + 38 + 40  # o painel dos tamanhos reais é mais baixo
    pag = Image.new("RGB", (larg, 120 + (titulos - 1) * (painel_h + 14) + ultimo_h + 28), (245, 243, 238))
    d = ImageDraw.Draw(pag)
    d.text((marg, 28), "KeyFlow — ícone do app", font=fonte(34, True), fill=(30, 30, 28))
    d.text(
        (marg, 74),
        f"Orbe parada (Ateliê), fundo {tema}, nas máscaras círculo, squircle e quadrado arredondado.",
        font=fonte(17),
        fill=(90, 88, 80),
    )

    y = 120

    def painel(titulo, cor_fundo, cor_texto, itens):
        nonlocal y
        d.rounded_rectangle([marg - 14, y, larg - marg + 14, y + painel_h], radius=18, fill=cor_fundo)
        d.text((marg, y + 10), titulo, font=fonte(17, True), fill=cor_texto)
        x = marg
        for rotulo, img in itens:
            pag.paste(img, (x, y + 38), img)
            d.text((x, y + 38 + tam + 4), rotulo, font=fonte(14), fill=cor_texto)
            x += tam + marg
        y += painel_h + 14

    def formas(img_janela, com_loja=True):
        itens = [
            ("círculo", recortar(img_janela, "circulo", tam)),
            ("squircle", recortar(img_janela, "squircle", tam)),
            ("quadrado arredondado", recortar(img_janela, "quadrado", tam)),
        ]
        if com_loja:  # o 512 da Play Store é quadrado e opaco; a loja aplica a máscara dela
            itens.append(("Play Store (512 px)", img_janela.resize((tam, tam), Image.LANCZOS)))
        return itens

    claro_pw, claro_tx = (233, 238, 242), (40, 44, 48)
    escuro_pw, escuro_tx = (24, 26, 30), (214, 218, 222)
    painel(f"Fundo {tema}, sobre papel de parede claro", claro_pw, claro_tx, formas(jan[tema]))
    painel(f"Fundo {tema}, sobre papel de parede escuro", escuro_pw, escuro_tx, formas(jan[tema], com_loja=False))
    painel(
        "Ícone temático (Android 13+): só o alfa da camada monochrome, pintado com a cor do tema",
        claro_pw,
        claro_tx,
        [
            ("tema claro", recortar(mono_claro, "circulo", tam)),
            ("tema escuro", recortar(mono_escuro, "circulo", tam)),
            ("camada monochrome (alfa)", recortar(mono_alfa, "circulo", tam)),
        ],
    )
    painel(f"Alternativa: fundo {outro}", claro_pw, claro_tx, formas(jan[outro]))

    # Legibilidade nos tamanhos reais de uso (círculo): 96, 72 e 48 px, nos dois fundos.
    d.rounded_rectangle([marg - 14, y, larg - marg + 14, y + ultimo_h], radius=18, fill=claro_pw)
    d.text((marg, y + 10), "Tamanhos reais (círculo): 96, 72 e 48 px — fundo escuro, depois fundo claro", font=fonte(17, True), fill=claro_tx)
    x = marg
    for t in (escuro, claro):
        for lado in (96, 72, 48):
            peca = recortar(jan[t], "circulo", lado)
            pag.paste(peca, (x, y + 38), peca)
            d.text((x, y + 38 + lado + 4), f"{lado} px", font=fonte(14), fill=claro_tx)
            x += lado + 24
        x += 40
    destino.parent.mkdir(parents=True, exist_ok=True)
    pag.save(destino, optimize=True)


def main():
    ap = argparse.ArgumentParser(description="Gera o ícone do KeyFlow a partir da orbe.")
    ap.add_argument("--fundo", choices=sorted(FUNDOS), default="escuro", help="cor do fundo do ícone (padrão: escuro)")
    ap.add_argument("--previa", metavar="PNG", help="escreve também a prancha de prévia neste caminho")
    ap.add_argument("--so-previa", action="store_true", help="só a prévia: não escreve nada no repositório")
    args = ap.parse_args()

    pior = conferir_zona_segura()
    erro = max(erro_bezier(c) for c in camadas())
    print(f"Orbe: raio máximo {pior:.1f} dp (zona segura {ZONA_SEGURA_DP / 2:.0f}); erro da Bézier {erro:.4f} dp")
    if erro > 0.05:
        sys.exit("A curva Bézier se afastou demais do blob: aumente N_BEZIER.")
    if not args.so_previa:
        gerar_repositorio(args.fundo)
        print(f"Escrito em {RES} e {LOJA / 'icone-512.png'}")
    if args.previa:
        gerar_previa(Path(args.previa), args.fundo)
        print(f"Prévia: {args.previa}")


if __name__ == "__main__":
    main()

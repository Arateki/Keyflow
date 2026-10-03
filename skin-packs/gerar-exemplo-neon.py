#!/usr/bin/env python3
"""Gera os SVGs do pacote de exemplo `exemplo-neon/` (a skin "Neon Noturno") — orbe com os 4 estados e teclado completo.

Os SVGs gerados ESTÃO commitados em `skin-packs/exemplo-neon/`; este script só existe para o desenho ficar reproduzível e ajustável (cada frame da
orbe é a mesma cena com uma fase diferente — escrever 45 arquivos à mão não é razoável). Rode da raiz do repositório:

    python3 skin-packs/gerar-exemplo-neon.py

Respeita o FORMAT.md §4: só caminhos, formas, degradês, opacidade e `stroke-dasharray`. Nada de `<filter>`, blend, SMIL ou CSS animado; todo SVG tem
`viewBox`. O "brilho neon" é feito com anéis concêntricos de opacidade decrescente e degradês radiais, que o AndroidSVG desenha.

Os frames de cada estado fecham o laço sem emenda (o último é um passo antes do primeiro): os pontos que giram na `idle` repetem a cada 120°, o
equalizador do `listening` só usa ondas com número inteiro de ciclos, e o cometa do `transcribing` avança de 45° por frame.
"""
import math
import os

PASTA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "exemplo-neon")

CIANO = "#19E6FF"
MAGENTA = "#FF3DCB"
VIOLETA = "#7B5CFF"
ROSA = "#FF4D8D"
LARANJA = "#FFB347"

MANIFEST = """{
  "schemaVersion": 1,
  "name": "Neon Noturno",
  "author": "KeyFlow (exemplo)",
  "orb": {
    "centerInk": "#FFFFFF",
    "fps": 12,
    "states": {
      "listening": { "fps": 24 },
      "transcribing": { "fps": 12 }
    }
  },
  "keyboard": {
    "ink": "#E8F7FF",
    "popup": "#171A33",
    "trayColor": "#0B0B1A",
    "navigationBarColor": "#0B0B1A",
    "slice": { "left": 24, "top": 24, "right": 24, "bottom": 24 }
  }
}
"""


def num(v):
    """Número curto para o SVG: no máximo 2 casas, sem zeros à toa."""
    texto = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if texto in ("-0", "") else texto


def polar(r, graus, cx=128.0, cy=128.0):
    a = math.radians(graus)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def mistura(a, b, t):
    """Cor entre as duas `#RRGGBB`, com t de 0 (a) a 1 (b)."""
    ca = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    cb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(ca, cb))


# ── a orbe ──────────────────────────────────────────────────────────────────────

def orbe_defs(halo, aro_a, aro_b, halo_alfa=1.0):
    """Degradês comuns: halo, corpo escuro (o tempo e os glifos do app ficam por cima, em branco) e aro neon."""
    return f"""<defs>
<radialGradient id="halo" cx="128" cy="128" r="128" gradientUnits="userSpaceOnUse"><stop offset="0.58" stop-color="{halo}" stop-opacity="{num(0.55 * halo_alfa)}"/><stop offset="0.74" stop-color="{halo}" stop-opacity="{num(0.2 * halo_alfa)}"/><stop offset="1" stop-color="{halo}" stop-opacity="0"/></radialGradient>
<radialGradient id="corpo" cx="104" cy="96" r="104" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#2E3274"/><stop offset="0.6" stop-color="#15173F"/><stop offset="1" stop-color="#090A22"/></radialGradient>
<linearGradient id="aro" x1="52" y1="52" x2="204" y2="204" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{aro_a}"/><stop offset="1" stop-color="{aro_b}"/></linearGradient>
<linearGradient id="brilho" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
</defs>"""


def orbe_corpo(raio=78.0, aro=5.0, aro_alfa=1.0, tracejado=None):
    traco = f' stroke-dasharray="{tracejado}"' if tracejado else ""
    return (
        f'<circle cx="128" cy="128" r="{num(raio)}" fill="url(#corpo)"/>'
        f'<circle cx="128" cy="128" r="{num(raio + 1)}" fill="none" stroke="url(#aro)" stroke-opacity="{num(0.28 * aro_alfa)}" stroke-width="{num(aro + 7)}"/>'
        f'<circle cx="128" cy="128" r="{num(raio)}" fill="none" stroke="url(#aro)" stroke-opacity="{num(aro_alfa)}" stroke-width="{num(aro)}"{traco}/>'
        f'<circle cx="128" cy="128" r="{num(raio - 7)}" fill="none" stroke="#FFFFFF" stroke-opacity="0.1" stroke-width="1.5"/>'
        f'<ellipse cx="124" cy="86" rx="46" ry="21" fill="url(#brilho)"/>'
    )


def svg_orbe(defs, corpo, extra=""):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">\n'
        f"{defs}\n"
        '<circle cx="128" cy="128" r="128" fill="url(#halo)"/>\n'
        f"{corpo}\n{extra}</svg>\n"
    )


def ponto_brilhante(x, y, cor):
    """Um ponto com brilho: o núcleo branco e dois anéis de luz de opacidade decrescente (o `<filter>` de desfoque não existe aqui)."""
    return (
        f'<circle cx="{num(x)}" cy="{num(y)}" r="13" fill="{cor}" fill-opacity="0.1"/>'
        f'<circle cx="{num(x)}" cy="{num(y)}" r="8.5" fill="{cor}" fill-opacity="0.3"/>'
        f'<circle cx="{num(x)}" cy="{num(y)}" r="4.5" fill="#FFFFFF"/>\n'
    )


def frame_idle(i, total=12):
    """Orbe parada: respira (halo e aro) e três pontos de luz percorrem o aro — 10° por frame, 120° no laço de 12 (os três pontos se repetem)."""
    respira = 0.5 + 0.5 * math.sin(2 * math.pi * i / total)
    defs = orbe_defs(CIANO, CIANO, MAGENTA, halo_alfa=0.7 + 0.3 * respira)
    corpo = orbe_corpo(aro=5 + 1.2 * respira)
    pontos = "".join(ponto_brilhante(*polar(78, -90 + i * 10 + k * 120), CIANO if k != 1 else MAGENTA) for k in range(3))
    return svg_orbe(defs, corpo, pontos)


def cor_da_barra(x, y):
    """A cor de um ponto na diagonal ciano → magenta → laranja (o degradê do equalizador, já resolvido: uma cor lisa por barra custa menos para desenhar
    do que um degradê por barra)."""
    t = max(0.0, min(1.0, (x + y - 2 * 40) / (2 * (216 - 40))))
    return mistura(CIANO, MAGENTA, t * 2) if t < 0.5 else mistura(MAGENTA, LARANJA, (t - 0.5) * 2)


def frame_listening(i, total=24, barras=36):
    """Gravando: o aro vira um equalizador de 36 barras (duas ondas com ciclos inteiros, para o laço fechar) e o corpo pulsa."""
    pulsa = 0.5 + 0.5 * math.sin(2 * math.pi * i / total)
    defs = orbe_defs(ROSA, ROSA, LARANJA, halo_alfa=0.85 + 0.15 * pulsa)
    corpo = orbe_corpo(raio=75 + 3 * pulsa, aro=4.5 + 1.5 * pulsa)
    linhas = []
    for j in range(barras):
        onda = 0.5 + 0.5 * math.sin(2 * math.pi * i / total + 2 * math.pi * 3 * j / barras)
        onda2 = 0.5 + 0.5 * math.sin(2 * math.pi * 2 * i / total - 2 * math.pi * 5 * j / barras)
        altura = 5 + 15 * onda + 6 * onda2
        x1, y1 = polar(88, j * 360 / barras)
        x2, y2 = polar(88 + altura, j * 360 / barras)
        cor = cor_da_barra((x1 + x2) / 2, (y1 + y2) / 2)
        linhas.append(f'<line x1="{num(x1)}" y1="{num(y1)}" x2="{num(x2)}" y2="{num(y2)}" stroke="{cor}"/>')
    barras_svg = '<g stroke-width="4.2" stroke-linecap="round">' + "".join(linhas) + "</g>\n"
    return svg_orbe(defs, corpo, barras_svg)


def frame_paused():
    """Pausada: tudo mais apagado, o aro tracejado e sem pontos de luz."""
    defs = orbe_defs("#8A8FC9", "#8A8FC9", "#5F6399", halo_alfa=0.45)
    return svg_orbe(defs, orbe_corpo(aro=4, aro_alfa=0.85, tracejado="9 7"))


def frame_transcribing(i, total=8, segmentos=24, cauda=210.0):
    """Enviando: um cometa gira em volta do corpo — 45° por frame, 360° no laço de 8; a cauda some em ~210°."""
    defs = orbe_defs(VIOLETA, CIANO, VIOLETA, halo_alfa=0.8)
    corpo = orbe_corpo(aro=3.2, aro_alfa=0.7)
    cabeca = -90 + i * (360 / total)
    arcos = []
    passo = 360 / segmentos
    for k in range(segmentos):
        angulo = cabeca - k * passo
        t = (k * passo) / cauda
        if t >= 1:
            continue
        alfa = (1 - t) ** 1.4
        cor = mistura(CIANO, VIOLETA, min(1.0, t * 1.2))
        a0 = angulo - passo * 0.82
        x0, y0 = polar(96, a0)
        x1, y1 = polar(96, angulo)
        arcos.append(f'<path d="M{num(x0)} {num(y0)}A96 96 0 0 1 {num(x1)} {num(y1)}" stroke="{cor}" stroke-opacity="{num(alfa)}"/>')
    hx, hy = polar(96, cabeca)
    extra = '<g fill="none" stroke-width="7" stroke-linecap="round">' + "".join(arcos) + "</g>\n" + ponto_brilhante(hx, hy, CIANO)
    return svg_orbe(defs, corpo, extra)


# ── o teclado ───────────────────────────────────────────────────────────────────

def face(topo, base, borda_a, borda_b, brilho, deslocamento=0.0, luz=0.18, luz_borda=0.7):
    """Uma tecla em 96×96 (a fatia 3×3 de 24 mantém os cantos): sombra no chão, halo neon, corpo em degradê, borda em degradê e um brilho de vidro.
    Só há degradês verticais e linhas retas no miolo, que é o que estica."""
    d = deslocamento
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">
<defs>
<linearGradient id="c" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{topo}"/><stop offset="1" stop-color="{base}"/></linearGradient>
<linearGradient id="b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{borda_a}" stop-opacity="{num(luz_borda)}"/><stop offset="1" stop-color="{borda_b}" stop-opacity="{num(luz_borda * 0.7)}"/></linearGradient>
<linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="{num(brilho)}"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
</defs>
<rect x="6" y="{num(9 + d)}" width="84" height="82" rx="18" fill="#000000" fill-opacity="0.45"/>
<rect x="5" y="{num(5 + d)}" width="86" height="84" rx="19" fill="none" stroke="{borda_a}" stroke-opacity="{num(luz)}" stroke-width="5"/>
<rect x="6" y="{num(6 + d)}" width="84" height="82" rx="18" fill="url(#c)" stroke="url(#b)" stroke-width="2"/>
<rect x="12" y="{num(10 + d)}" width="72" height="26" rx="13" fill="url(#g)"/>
</svg>
"""


def bandeja():
    """O fundo do teclado inteiro: azul-noite em degradê e um fio neon no topo (esticado até a largura toda; só linhas horizontais)."""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" preserveAspectRatio="none">
<defs>
<linearGradient id="f" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#14152E"/><stop offset="1" stop-color="#090913"/></linearGradient>
<linearGradient id="n" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{CIANO}" stop-opacity="0"/><stop offset="0.25" stop-color="{CIANO}"/><stop offset="0.75" stop-color="{MAGENTA}"/><stop offset="1" stop-color="{MAGENTA}" stop-opacity="0"/></linearGradient>
</defs>
<rect width="100" height="100" fill="url(#f)"/>
<rect width="100" height="2.4" fill="url(#n)" fill-opacity="0.12"/>
<rect width="100" height="0.45" fill="url(#n)" fill-opacity="0.85"/>
</svg>
"""


def escreve(nome, texto):
    with open(os.path.join(PASTA, nome), "w", encoding="utf-8", newline="\n") as f:
        f.write(texto)


def main():
    os.makedirs(PASTA, exist_ok=True)
    escreve("manifest.json", MANIFEST)
    for i in range(12):
        escreve(f"orb_idle_{i + 1:02d}.svg", frame_idle(i))
    for i in range(24):
        escreve(f"orb_listening_{i + 1:02d}.svg", frame_listening(i))
    escreve("orb_paused_01.svg", frame_paused())
    for i in range(8):
        escreve(f"orb_transcribing_{i + 1:02d}.svg", frame_transcribing(i))
    escreve("keyboard_tray.svg", bandeja())
    escreve("keyboard_key_normal.svg", face("#252A55", "#131631", CIANO, VIOLETA, 0.16))
    escreve(
        "keyboard_key_pressed.svg",
        face("#1D4F70", "#10304A", CIANO, CIANO, 0.07, deslocamento=1.5, luz=0.38, luz_borda=1.0),
    )
    escreve("keyboard_modifier_normal.svg", face("#32204F", "#190F2E", MAGENTA, VIOLETA, 0.14))
    escreve(
        "keyboard_modifier_pressed.svg",
        face("#6A2670", "#3A1448", MAGENTA, MAGENTA, 0.07, deslocamento=1.5, luz=0.4, luz_borda=1.0),
    )
    total = len([n for n in os.listdir(PASTA) if n.endswith(".svg")])
    print(f"{total} SVGs e o manifest.json em {PASTA}")


if __name__ == "__main__":
    main()

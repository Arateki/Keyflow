#!/usr/bin/env python3
"""Gera imagens e casos de OCR e objetos da bancada, com semente fixa.

Requer Pillow 12.3.0. A fonte Space Mono já está no projeto (SIL OFL 1.1;
app/licenses/OFL_SpaceMono.txt). As imagens e os textos são da equipe KeyFlow.
"""
import argparse
import hashlib
import io
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

RAIZ = Path(__file__).resolve().parent.parent
DESTINO = RAIZ / 'app/src/main/assets/bancada/v1'
FONTE = RAIZ / 'app/src/main/res/font/space_mono_regular.ttf'
SHA_FONTE = '95837e182baeeada83368f7748db28357f0a1b75c6b84ff7065b5edf933c8e18'
SEMENTE = 20261029
TEXTOS = ['Texto de teste do KeyFlow', 'A reunião será às 14:30.', 'Código: 731-204', 'Olá, mundo!', 'Valor total: R$ 42,50', 'Entrada liberada', 'Segunda-feira, 28 de outubro', 'A senha não está nesta imagem.', 'Linha um\nLinha dois', 'Rua das Flores, 17']
ROTULOS = {
    'sol': ['sun'], 'árvore': ['arvore', 'tree'], 'relógio': ['relogio', 'clock'],
    'livro': ['book'], 'copo': ['glass', 'cup'],
}


def png(imagem):
    buf = io.BytesIO()
    imagem.save(buf, format='PNG', optimize=False, compress_level=9)
    return buf.getvalue()


def objeto(d, nome, x, y):
    preto = '#192431'
    if nome == 'sol':
        d.ellipse((x+34,y+34,x+114,y+114), fill='#f6c743', outline=preto, width=4)
        for dx,dy in [(74,0),(74,130),(0,74),(130,74),(20,20),(120,120),(20,120),(120,20)]:
            d.line((x+74,y+74,x+dx,y+dy), fill='#e89817', width=5)
        d.ellipse((x+34,y+34,x+114,y+114), fill='#f6c743', outline=preto, width=4)
    elif nome == 'árvore':
        d.rectangle((x+63,y+80,x+86,y+135), fill='#84532d', outline=preto, width=3)
        d.ellipse((x+22,y+15,x+125,y+95), fill='#50a553', outline=preto, width=4)
    elif nome == 'relógio':
        d.ellipse((x+20,y+15,x+130,y+125), fill='white', outline=preto, width=5)
        d.line((x+75,y+70,x+75,y+35), fill=preto, width=4)
        d.line((x+75,y+70,x+102,y+82), fill=preto, width=4)
    elif nome == 'livro':
        d.polygon([(x+20,y+25),(x+72,y+35),(x+72,y+125),(x+20,y+115)], fill='#e1e8ef', outline=preto, width=4)
        d.polygon([(x+72,y+35),(x+125,y+25),(x+125,y+115),(x+72,y+125)], fill='#d2e0ef', outline=preto, width=4)
        d.line((x+72,y+35,x+72,y+125), fill=preto, width=3)
    else:
        d.polygon([(x+30,y+25),(x+120,y+25),(x+105,y+130),(x+45,y+130)], fill='#b6ddf1', outline=preto, width=4)
        d.line((x+38,y+58,x+112,y+58), fill='#4197ca', width=4)


def gerar():
    if pillow_version != '12.3.0':
        raise SystemExit('Use Pillow 12.3.0 para gerar imagens reproduzíveis')
    if hashlib.sha256(FONTE.read_bytes()).hexdigest() != SHA_FONTE:
        raise SystemExit('A fonte Space Mono não confere com o SHA fixado')
    fonte = ImageFont.truetype(str(FONTE), 31)
    rng = random.Random(SEMENTE)
    arquivos = {}
    casos_ocr = []
    casos_objetos = []
    for i, texto in enumerate(TEXTOS, 1):
        imagem = Image.new('RGB', (800, 200), 'white')
        d = ImageDraw.Draw(imagem)
        d.multiline_text((25+rng.randrange(8), 40+rng.randrange(8)), texto, fill='#101010', font=fonte, spacing=8)
        caminho = f'imagens/ocr-{i:02d}.png'
        arquivos[caminho] = png(imagem)
        entrada = 'Leia o texto da imagem.'
        casos_ocr.append({'id': f'ocr-pt-{i:02d}', 'idioma': 'pt', 'entrada': entrada, 'imagem': caminho,
                          'esperado': texto, 'maxTokensSaida': 96, 'tokensEntrada': (len(entrada)+3)//4})
    nomes = list(ROTULOS)
    for i in range(10):
        rotulos = [nomes[i % len(nomes)], nomes[(i+1) % len(nomes)]]
        imagem = Image.new('RGB', (480, 220), '#f8f8f8')
        d = ImageDraw.Draw(imagem)
        objeto(d, rotulos[0], 25+rng.randrange(12), 25)
        objeto(d, rotulos[1], 275+rng.randrange(12), 25)
        caminho = f'imagens/objetos-{i+1:02d}.png'
        arquivos[caminho] = png(imagem)
        entrada = 'Liste os objetos da imagem, um por linha, sem explicação.'
        casos_objetos.append({'id': f'obj-pt-{i+1:02d}', 'idioma': 'pt', 'entrada': entrada, 'imagem': caminho,
                              'esperado': rotulos, 'sinonimos': ROTULOS, 'maxTokensSaida': 96,
                              'tokensEntrada': (len(entrada)+3)//4})
    for nome, descricao, casos in [('ocr', 'Mede a leitura de texto em imagens.', casos_ocr),
                                   ('objetos', 'Mede a identificação de objetos em imagens.', casos_objetos)]:
        dados = {'teste': nome, 'versao': 1, 'capacidade': 'IMAGEM', 'descricao': descricao,
                 'prompt': {'system': None, 'user': '{entrada}'}, 'casos': casos}
        arquivos[f'{nome}.json'] = (json.dumps(dados, ensure_ascii=False, indent=2)+'\n').encode()
    return arquivos


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verificar', action='store_true', help='compara os arquivos sem alterá-los')
    args = parser.parse_args()
    arquivos = gerar()
    if args.verificar:
        diferentes = [nome for nome, conteudo in arquivos.items() if not (DESTINO/nome).is_file() or (DESTINO/nome).read_bytes() != conteudo]
        if diferentes:
            raise SystemExit('Arquivos divergentes: ' + ', '.join(diferentes))
        print(f'{len(arquivos)} arquivos reproduzidos com a semente {SEMENTE}')
        return
    for nome, conteudo in arquivos.items():
        caminho = DESTINO/nome
        caminho.parent.mkdir(parents=True, exist_ok=True)
        caminho.write_bytes(conteudo)
    manifesto = json.loads((DESTINO/'manifesto.json').read_text())
    manifesto['arquivos'] = [item for item in manifesto['arquivos'] if item['caminho'] not in arquivos]
    for nome, conteudo in arquivos.items():
        origem = 'Gerado proceduralmente por scripts/gerar-corpus-ocr.py, semente 20261029'
        if nome.startswith('imagens/ocr-'):
            origem += '; fonte Space Mono SIL OFL 1.1: https://github.com/google/fonts/tree/main/ofl/spacemono; SHA-256 ' + SHA_FONTE
        manifesto['arquivos'].append({'caminho': nome, 'sha256': hashlib.sha256(conteudo).hexdigest(),
                                      'origem': origem, 'autor': 'Equipe KeyFlow', 'licenca': 'Apache-2.0'})
    (DESTINO/'manifesto.json').write_text(json.dumps(manifesto, ensure_ascii=False, indent=2)+'\n')
    pins = RAIZ/'app/src/test/resources/bancada/versoes-fixadas.json'
    fixadas = json.loads(pins.read_text())
    for nome in ('ocr', 'objetos'):
        fixadas['v1'][nome] = {'1': hashlib.sha256(arquivos[nome+'.json']).hexdigest()}
    pins.write_text(json.dumps(fixadas, ensure_ascii=False, indent=2)+'\n')
    print(f'{len(arquivos)} arquivos gerados com a semente {SEMENTE}')


if __name__ == '__main__':
    main()

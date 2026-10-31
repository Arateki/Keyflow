#!/usr/bin/env python3
"""Gera o inventário auditável do grafo real do terminal, com POMs e hashes de artefatos."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

RAIZ = Path(__file__).resolve().parent.parent
PERMITIDAS = {'Apache-2.0', 'MIT', 'BSD-3-Clause', 'BSL-1.0', 'ISC'}


def identificar(nome):
    normal = re.sub(r'[^a-z0-9]', '', nome.lower())
    aliases = {
        'Apache-2.0': {'apache20', 'apachelicense20', 'apachelicenseversion20', 'theapachelicenseversion20', 'theapachesoftwarelicenseversion20'},
        'MIT': {'mit', 'mitlicense', 'themitlicense'},
        'BSL-1.0': {'boostsoftwarelicense10'},
        'ISC': {'isc'},
        'BSD-3-Clause': {'bsd3clause', 'bsd3clauselicense', '3clausebsdlicense', 'thenewbsdlicense'},
    }
    for identificador, nomes in aliases.items():
        if normal in nomes: return identificador
    raise ValueError(f'Licença não auditada/permitida: {nome}')


def executar(grafo, provas, cache):
    provas.mkdir(parents=True, exist_ok=True)
    vistos = {}
    def ler_pom(coordenada):
        if coordenada in vistos: return vistos[coordenada]
        g, a, v = coordenada.split(':')
        repositorio = 'https://dl.google.com/dl/android/maven2/' if g.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
        url = f'{repositorio}{g.replace(".","/")}/{a}/{v}/{a}-{v}.pom'
        existentes = list((cache / g / a / v).glob(f'*/{a}-{v}.pom'))
        dados = existentes[0].read_bytes() if existentes else urllib.request.urlopen(url, timeout=45).read()
        (provas / (coordenada.replace(':', '_') + '.pom')).write_bytes(dados)
        xml = ET.fromstring(dados)
        licencas = [{'id': identificar(n.findtext('{*}name', '')), 'name': n.findtext('{*}name'), 'url': n.findtext('{*}url')} for n in xml.findall('{*}licenses/{*}license')]
        origem = url
        if not licencas:
            pai = xml.find('{*}parent')
            if pai is None: raise ValueError(f'{coordenada}: POM sem licença nem pai')
            partes = [pai.findtext('{*}' + k) for k in ['groupId', 'artifactId', 'version']]
            propriedades = {p.tag.split('}')[-1]: p.text for p in xml.findall('{*}properties/*')}
            partes = [re.sub(r'\$\{(.*?)\}', lambda m: propriedades.get(m[1], m[0]), x) for x in partes]
            if any('${' in x for x in partes): raise ValueError(f'{coordenada}: versão de pai precisa de revisão: {partes}')
            licencas, _, _, origem = ler_pom(':'.join(partes))
        if not licencas or any(n['id'] not in PERMITIDAS for n in licencas): raise ValueError(coordenada)
        resultado = (licencas, hashlib.sha256(dados).hexdigest(), url, origem)
        vistos[coordenada] = resultado
        return resultado

    avisos = RAIZ / 'app/licenses/computador'
    inventario = []
    def preservar(zipado, destino, prefixo=''):
        for nome in zipado.namelist():
            base = Path(nome).name
            if re.fullmatch(r'(LICENSE|LICENCE|NOTICE|COPYING)([._-].*)?', base, re.I):
                destino.mkdir(parents=True, exist_ok=True)
                (destino / (prefixo + nome.replace('/', '_'))).write_bytes(zipado.read(nome))
        if 'classes.jar' in zipado.namelist():
            with zipfile.ZipFile(io.BytesIO(zipado.read('classes.jar'))) as classes: preservar(classes, destino, 'classes_')

    for item in json.loads(grafo.read_text()):
        coord = item['coordinate']
        licencas, sha, url, origem = ler_pom(coord)
        artefatos = []
        for a in item['artifacts']:
            caminho = Path(a['file'])
            if hashlib.sha256(caminho.read_bytes()).hexdigest() != a['sha256']: raise ValueError(f'{coord}: arquivo não bate com o grafo')
            artefatos.append({'name': a['name'], 'sha256': a['sha256']})
            if zipfile.is_zipfile(caminho):
                with zipfile.ZipFile(caminho) as z: preservar(z, avisos / coord.replace(':', '_'), a['name'] + '_')
        inventario.append({'coordinate': coord, 'licenses': licencas, 'pom_sha256': sha, 'pom_url': url, 'license_source': origem, 'artifacts': artefatos})
    alvo = RAIZ / 'app/licenses/computador-dependencias.json'
    embutidos = [
        {'component': 'org.connectbot.sshlib:sshlib:0.6.6', 'id': 'MIT', 'notice': 'MIT_bouncycastle-cbssh.txt', 'description': 'Trechos do Bouncy Castle preservados pelo upstream do cbssh'},
        {'component': 'com.google.crypto.tink:tink-android:1.24.0', 'id': 'BSD-3-Clause', 'notice': 'BSD-3-Clause_protobuf.txt', 'description': 'Runtime Protobuf realocado em com.google.crypto.tink.shaded.protobuf'},
    ]
    alvo.write_text(json.dumps({'schema': 1, 'embedded_notices': embutidos, 'dependencies': inventario}, indent=2, ensure_ascii=False) + '\n')
    linhas = ['# Dependências do terminal — grafo real do release', '', 'Gerado com `gerarGrafoTerminal` e `scripts/auditar-licencas-terminal.py`.', 'As versões e os hashes são conferidos pelo `LicencasTransitivasTest`; mudança exige nova auditoria.', '', 'Avisos embutidos: Bouncy Castle (MIT) no cbssh e Protobuf realocado (BSD-3-Clause) no Tink Android, com os textos completos preservados.', '', '| Componente | Licença | POM que declara a licença |', '| --- | --- | --- |']
    linhas += [f"| `{n['coordinate']}` | {', '.join(x['id'] for x in n['licenses'])} | [fonte]({n['license_source']}) |" for n in inventario]
    (RAIZ / 'app/licenses/Computador-transitivas.md').write_text('\n'.join(linhas) + '\n')
    print(len(inventario), 'componentes; licenças:', sorted({x['id'] for n in inventario for x in n['licenses']}))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--grafo', type=Path, default=RAIZ / 'app/build/reports/computador/dependencias-terminal.json')
    p.add_argument('--provas', type=Path, default=RAIZ / '.equipe/provas/terminal-licencas')
    p.add_argument('--cache', type=Path, default=Path('/srv/dev/keyflow/.gradle/caches/modules-2/files-2.1'))
    args = p.parse_args()
    executar(args.grafo, args.provas, args.cache)

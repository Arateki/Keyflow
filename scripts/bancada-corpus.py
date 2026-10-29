#!/usr/bin/env python3
"""Gera o corpus de texto da bancada de modelos (`app/src/main/assets/bancada/v1/`) e o `manifesto.json`.

Este script é a FONTE do corpus: edite os casos aqui e rode de novo — os JSON dos assets são o resultado. Tudo é conteúdo próprio do
KeyFlow (licença Apache-2.0, a do repositório); nada de terceiros entra (BCP-02).

    python3 scripts/bancada-corpus.py                              # regera os 5 testes e o manifesto
    python3 scripts/bancada-corpus.py --ferramentas FILE.json      # idem, trocando a lista de ferramentas do `acoes`
    python3 scripts/bancada-corpus.py --manifesto                  # só recalcula os SHA-256 do manifesto

FILE.json é o que o `AcoesFerramentasTest` grava em `app/build/bancada/ferramentas-reais.json` (as definições do ToolRegistry real).
Mudou caso, prompt ou ferramenta? Suba a `versao` do teste (BCP-06) e acrescente a linha nova em
`app/src/test/resources/bancada/versoes-fixadas.json`.

BCP-03: o prompt é do teste e faz parte da versão; `{entrada}` é o único campo do caso que o substitui (e `{ferramentas}`, no `acoes`).
`tokensEntrada` é uma estimativa (caracteres ÷ 4, arredondada para cima) do prompt montado, usada só para decidir "não se aplica".
"""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
DESTINO = RAIZ / "app/src/main/assets/bancada/v1"
CORPUS = 1
LICENCA = "Apache-2.0"
AUTOR = "Equipe KeyFlow"
ORIGEM = "Escrito para a bancada de modelos do KeyFlow"


def estimar_tokens(*textos):
    return math.ceil(sum(len(t or "") for t in textos) / 4)


# ── pontuacao ───────────────────────────────────────────────────────────────────────────────────────
PONTUACAO_SYSTEM = (
    "Você é um corretor de ditado. Receba o texto ditado, sem pontuação e sem maiúsculas, e devolva o MESMO texto com a pontuação e as "
    "maiúsculas corretas. Não troque, não acrescente e não remova nenhuma palavra. Não traduza. Responda só com o texto corrigido, "
    "sem explicações."
)
PONTUACAO = [
    ("pt-01", "pt", "oi pessoal tudo bem eu queria confirmar a reunião de amanhã às duas da tarde",
     "Oi, pessoal, tudo bem? Eu queria confirmar a reunião de amanhã às duas da tarde."),
    ("en-01", "en", "hi john thanks for the update i will review the document tonight and send my comments",
     "Hi John, thanks for the update. I will review the document tonight and send my comments."),
    ("es-01", "es", "hola carlos mañana llego tarde a la oficina porque tengo cita con el médico",
     "Hola, Carlos. Mañana llego tarde a la oficina porque tengo cita con el médico."),
    ("pt-02", "pt", "não esqueça de comprar leite pão e ovos no mercado antes de voltar para casa",
     "Não esqueça de comprar leite, pão e ovos no mercado antes de voltar para casa."),
    ("en-02", "en", "can you call me when you get to the airport",
     "Can you call me when you get to the airport?"),
    ("es-02", "es", "qué hora es la reunión con el equipo de ventas",
     "¿Qué hora es la reunión con el equipo de ventas?"),
    ("pt-03", "pt", "maria você já enviou o relatório para o cliente ou ainda está revisando",
     "Maria, você já enviou o relatório para o cliente ou ainda está revisando?"),
    ("en-03", "en", "we need three things a laptop a charger and a notebook",
     "We need three things: a laptop, a charger, and a notebook."),
    ("es-03", "es", "compré manzanas naranjas y plátanos en el mercado",
     "Compré manzanas, naranjas y plátanos en el mercado."),
    ("pt-04", "pt", "o pedido chegou hoje de manhã mas a caixa estava amassada então vou pedir a troca",
     "O pedido chegou hoje de manhã, mas a caixa estava amassada, então vou pedir a troca."),
    ("en-04", "en", "the meeting moved to friday but the deadline is still monday",
     "The meeting moved to Friday, but the deadline is still Monday."),
    ("es-04", "es", "ya terminé el informe pero todavía falta revisar las cifras",
     "Ya terminé el informe, pero todavía falta revisar las cifras."),
]


def teste_pontuacao():
    prompt = {"system": PONTUACAO_SYSTEM, "user": "{entrada}"}
    casos = [
        {"id": i, "idioma": lg, "entrada": e, "esperado": x, "maxTokensSaida": 96, "tokensEntrada": estimar_tokens(PONTUACAO_SYSTEM, e)}
        for i, lg, e, x in PONTUACAO
    ]
    return {
        "teste": "pontuacao", "versao": 1, "capacidade": "TEXTO",
        "descricao": "Mede se o modelo põe pontuação e maiúsculas num ditado sem trocar nenhuma palavra (português, inglês e espanhol).",
        "prompt": prompt, "casos": casos,
    }


# ── reescrita ───────────────────────────────────────────────────────────────────────────────────────
REESCRITA_SYSTEM = (
    "Você reescreve textos seguindo o comando do usuário. Mantenha todos os fatos (nomes, números, datas, horários e lugares) exatamente "
    "como no texto original, não invente nenhuma informação nova e mantenha o idioma do texto original. Responda só com o texto "
    "reescrito, sem explicações."
)
# (id, idioma, comando, texto, esperado de referência, extras)
REESCRITA = [
    ("rw-01", "pt", "Encurte o texto em no máximo 15 palavras.",
     "Olá equipe, estou escrevendo para avisar que a reunião de planejamento que estava marcada para quinta-feira às 14h foi remarcada para sexta-feira às 10h na sala 3 por causa da viagem do diretor.",
     "A reunião de planejamento foi remarcada para sexta-feira às 10h, sala 3.",
     {"fatosObrigatorios": ["sexta-feira", "10h", "sala 3"], "maxPalavras": 15}),
    ("rw-02", "pt", "Corrija os erros de ortografia e de gramática sem mudar o sentido.",
     "Ontem eu fui no mercado e comprei tres kilos de maçã, porém esqueci de pegar o troco de dez reais.",
     "Ontem eu fui ao mercado e comprei três quilos de maçã, porém esqueci de pegar o troco de dez reais.",
     {"fatosObrigatorios": ["três quilos", "maçã", "dez reais"]}),
    ("rw-03", "pt", "Torne o texto mais formal, sem gírias.",
     "E aí, tudo certo? Preciso que vc mande o contrato assinado até quarta, tá? Valeu!",
     "Prezado, tudo bem? Solicito que envie o contrato assinado até quarta-feira. Agradeço.",
     {"fatosObrigatorios": ["contrato", "quarta"], "proibidos": ["vc", "valeu", "tá", "e aí", "blz"]}),
    ("rw-04", "en", "Shorten the text to at most 12 words.",
     "Hello everyone, I am writing to let you know that the quarterly report is now ready and has been uploaded to the shared drive in the Finance folder.",
     "The quarterly report is ready in the shared drive's Finance folder.",
     {"fatosObrigatorios": ["quarterly report", "Finance folder"], "maxPalavras": 12}),
    ("rw-05", "en", "Make the text more formal.",
     "Hey, can u send me the invoice for order 4821 asap? thx",
     "Hello, could you please send me the invoice for order 4821 at your earliest convenience? Thank you.",
     {"fatosObrigatorios": ["invoice", "order 4821"], "proibidos": ["u", "asap", "thx", "hey"]}),
    ("rw-06", "es", "Acorta el texto a un máximo de 14 palabras.",
     "Hola a todos, les escribo para informarles que la entrega del pedido 5530 se retrasó hasta el martes 12 de noviembre debido a un problema con el transportista.",
     "El pedido 5530 se retrasó hasta el martes 12 de noviembre.",
     {"fatosObrigatorios": ["pedido 5530", "martes 12 de noviembre"], "maxPalavras": 14}),
    ("rw-07", "es", "Corrige la ortografía y la gramática sin cambiar el sentido.",
     "Ayer fui a la tienda y compre dos botellas de agua, pero se me olvido pagar la bolsa de papel.",
     "Ayer fui a la tienda y compré dos botellas de agua, pero se me olvidó pagar la bolsa de papel.",
     {"fatosObrigatorios": ["dos botellas de agua", "bolsa de papel"]}),
    ("rw-08", "pt", "Resuma em uma frase de no máximo 20 palavras, mantendo nomes e valores.",
     "A Ana Souza aprovou o orçamento de R$ 12.500 para a reforma da recepção, e o início das obras ficou marcado para 3 de março com prazo de 45 dias.",
     "Ana Souza aprovou R$ 12.500 para a reforma da recepção; obras começam em 3 de março, por 45 dias.",
     {"fatosObrigatorios": ["Ana Souza", "12.500", "3 de março", "45 dias"], "maxPalavras": 20}),
    ("rw-09", "en", "Fix the spelling and grammar without changing the meaning.",
     "Their going to launch the new app on Thursday, and we has already tested it on 3 phones.",
     "They're going to launch the new app on Thursday, and we have already tested it on 3 phones.",
     {"fatosObrigatorios": ["Thursday", "3 phones"]}),
    ("rw-10", "pt", "Reescreva em tom profissional, em até 25 palavras.",
     "Cara, o servidor caiu de novo hoje às 9h e eu não consegui entregar o relatório pro Pedro. Foi mal.",
     "O servidor ficou indisponível hoje às 9h e não consegui entregar o relatório ao Pedro. Peço desculpas.",
     {"fatosObrigatorios": ["servidor", "9h", "Pedro"], "proibidos": ["cara", "foi mal", "pro"], "maxPalavras": 25}),
]


def teste_reescrita():
    prompt = {"system": REESCRITA_SYSTEM, "user": "{entrada}"}
    casos = []
    for i, lg, comando, texto, esperado, extras in REESCRITA:
        entrada = f"Comando: {comando}\n\nTexto: {texto}"
        caso = {"id": i, "idioma": lg, "entrada": entrada, "esperado": esperado, "maxTokensSaida": 128,
                "tokensEntrada": estimar_tokens(REESCRITA_SYSTEM, entrada)}
        caso.update(extras)
        casos.append(caso)
    return {
        "teste": "reescrita", "versao": 1, "capacidade": "TEXTO",
        "descricao": "Mede se o modelo obedece um comando sobre um texto (encurtar, corrigir, formalizar) sem perder fatos nem inventar nada.",
        "prompt": prompt, "casos": casos,
    }


# ── json ────────────────────────────────────────────────────────────────────────────────────────────
JSON_SYSTEM = (
    "Você extrai informações de um texto e responde SOMENTE com um objeto JSON válido, sem explicações e sem cercas de código. Use "
    "exatamente as chaves pedidas. Datas no formato AAAA-MM-DD e horas no formato HH:MM (24 h). Hoje é 2026-10-28, uma quarta-feira. "
    "Se a informação não aparece no texto, use null."
)
# (id, idioma, campos pedidos, texto, esquema, esperado)
JSON_CASOS = [
    ("js-01", "pt", "nome, telefone, cidade (todos texto)",
     "Meu nome é Carla Mendes, moro em Curitiba e meu telefone é (41) 99876-5432.",
     {"nome": "string", "telefone": "string", "cidade": "string"},
     {"nome": "Carla Mendes", "telefone": "(41) 99876-5432", "cidade": "Curitiba"}),
    ("js-02", "en", "nome, email (texto) e quantidade (inteiro)",
     "Please ship 3 units to Mark Taylor, mark.taylor@example.com.",
     {"nome": "string", "email": "string", "quantidade": "integer"},
     {"nome": "Mark Taylor", "email": "mark.taylor@example.com", "quantidade": 3}),
    ("js-03", "es", "nome, idade (inteiro), cidade",
     "Me llamo Lucía Fernández, tengo 34 años y vivo en Sevilla.",
     {"nome": "string", "idade": "integer", "cidade": "string"},
     {"nome": "Lucía Fernández", "idade": 34, "cidade": "Sevilla"}),
    ("js-04", "pt", "data (AAAA-MM-DD) e hora (HH:MM)",
     "A consulta com a dentista ficou marcada para amanhã às 15h30.",
     {"data": "date", "hora": "string"},
     {"data": "2026-10-29", "hora": "15:30"}),
    ("js-05", "pt", "valor (número, sem moeda), vencimento (AAAA-MM-DD), pago (verdadeiro ou falso)",
     "A fatura de R$ 1.250,90 vence em 05/11/2026 e ainda não foi paga.",
     {"valor": "number", "vencimento": "date", "pago": "boolean"},
     {"valor": 1250.9, "vencimento": "2026-11-05", "pago": False}),
    ("js-06", "en", "data (AAAA-MM-DD) e hora (HH:MM, 24 h)",
     "The team dinner is this Friday at 7:30 PM.",
     {"data": "date", "hora": "string"},
     {"data": "2026-10-30", "hora": "19:30"}),
    ("js-07", "pt", "itens (lista de textos) e total_itens (inteiro)",
     "Na lista de compras anotei arroz, feijão, café e sabão em pó.",
     {"itens": "array", "total_itens": "integer"},
     {"itens": ["arroz", "feijão", "café", "sabão em pó"], "total_itens": 4}),
    ("js-08", "pt", "nome e telefone (null se o telefone não aparece)",
     "Aqui é o Rafael, pode me ligar depois do almoço.",
     {"nome": "string", "telefone": "string?"},
     {"nome": "Rafael", "telefone": None}),
    ("js-09", "en", "inicio (AAAA-MM-DDTHH:MM) e duracao_minutos (inteiro)",
     "The call is on November 4th at 14:15 and will last 45 minutes.",
     {"inicio": "datetime", "duracao_minutos": "integer"},
     {"inicio": "2026-11-04T14:15", "duracao_minutos": 45}),
    ("js-10", "pt", "produto (nome do item no singular, em minúsculas), quantidade (inteiro) e preco_unitario (número, sem moeda)",
     "Pedido: 12 canetas azuis a R$ 2,50 cada.",
     {"produto": "string", "quantidade": "integer", "preco_unitario": "number"},
     {"produto": "caneta", "quantidade": 12, "preco_unitario": 2.5}),
]


def teste_json():
    prompt = {"system": JSON_SYSTEM, "user": "{entrada}"}
    casos = []
    for i, lg, campos, texto, esquema, esperado in JSON_CASOS:
        entrada = f"Campos: {campos}\n\nTexto: {texto}"
        casos.append({"id": i, "idioma": lg, "entrada": entrada, "esperado": esperado, "esquema": esquema,
                      "maxTokensSaida": 96, "tokensEntrada": estimar_tokens(JSON_SYSTEM, entrada)})
    return {
        "teste": "json", "versao": 1, "capacidade": "TEXTO",
        "descricao": "Mede se o modelo extrai campos de um texto e responde só com um JSON válido, no esquema e com os valores certos.",
        "prompt": prompt, "casos": casos,
    }


# ── acoes ───────────────────────────────────────────────────────────────────────────────────────────
ACOES_SYSTEM = (
    "Você é o assistente do app KeyFlow. Estas são as ferramentas que você pode chamar (JSON): {ferramentas}\n\n"
    "Responda SOMENTE com um objeto JSON, sem explicações e sem cercas de código. Para chamar UMA ferramenta: "
    '{"nome": "<ferramenta>", "args": {<argumentos>}}. Se nenhuma ferramenta serve para o pedido, responda {"recusa": "<motivo curto>"}. '
    "Os valores de ajustes (settings_set) vão sempre como texto."
)
# (id, idioma, pedido, chamada esperada ou None). A ordem do arquivo importa: o perfil `fumaca` roda os 2 primeiros.
ACOES = [
    ("ac-01", "pt", "Quais skins eu tenho instaladas?", ("skin_list", {})),
    ("ac-17", "pt", "Qual é a previsão do tempo para amanhã em Curitiba?", None),
    ("ac-02", "pt", "Me mostra o estado do app.", ("app_status", {})),
    ("ac-03", "pt", "Abra a aba do teclado.", ("open_tab", {"tab": "teclado"})),
    ("ac-04", "pt", "Qual é o tamanho atual do teclado?", ("settings_get", {"key": "keyboardScalePercent"})),
    ("ac-05", "pt", "Deixe o teclado com 120% do tamanho.", ("settings_set", {"key": "keyboardScalePercent", "value": "120"})),
    ("ac-06", "pt", "Ative o tema escuro.", ("settings_set", {"key": "themeDark", "value": "true"})),
    ("ac-07", "pt", "Troque a skin do teclado para neon-aaaa1111.", ("skin_select", {"keyboard": "neon-aaaa1111"})),
    ("ac-08", "pt", "Remova a skin neon-aaaa1111.", ("skin_remove", {"id": "neon-aaaa1111"})),
    ("ac-09", "pt", "Como é o formato de skin que o app aceita?", ("skin_get_format", {})),
    ("ac-10", "pt", "Volte a orbe para a skin padrão.", ("skin_select", {"orb": "default"})),
    ("ac-18", "pt", "Me mostre a minha chave de API da OpenAI.", None),
    ("ac-11", "en", "Show me all the settings you can read.", ("settings_get", {})),
    ("ac-12", "en", "Turn on the number row on the keyboard.", ("settings_set", {"key": "keyboardNumberRowEnabled", "value": "true"})),
    ("ac-13", "en", "Open the Docs tab.", ("open_tab", {"tab": "docs"})),
    ("ac-14", "en", 'Preview a skin made of a single file, manifest.json, whose content is exactly: {}',
     ("skin_preview", {"files": {"manifest.json": "{}"}})),
    ("ac-15", "en", "Install the skin I just previewed.", ("skin_install", {})),
    ("ac-19", "en", "Send an email to my boss saying I will be late.", None),
    ("ac-16", "es", "Cambia el idioma del teclado a inglés.", ("settings_set", {"key": "keyboardLanguage", "value": "en"})),
    ("ac-20", "es", "Borra todas mis aplicaciones.", None),
]


def teste_acoes(ferramentas_json):
    ferramentas = json.dumps(ferramentas_json, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    sistema = ACOES_SYSTEM.replace("{ferramentas}", ferramentas)
    prompt = {"system": ACOES_SYSTEM, "user": "{entrada}"}
    casos = []
    for i, lg, pedido, chamada in ACOES:
        esperado = None if chamada is None else {"nome": chamada[0], "args": chamada[1]}
        casos.append({"id": i, "idioma": lg, "entrada": pedido, "esperado": esperado, "maxTokensSaida": 128,
                      "tokensEntrada": estimar_tokens(sistema, pedido)})
    return {
        "teste": "acoes", "versao": 1, "capacidade": "TEXTO",
        "descricao": "Mede se o modelo escolhe a ferramenta certa do agente e os argumentos exatos — ou recusa o que nenhuma ferramenta faz.",
        "prompt": prompt, "ferramentas": ferramentas, "casos": casos,
    }


# ── contexto ────────────────────────────────────────────────────────────────────────────────────────
CONTEXTO_SYSTEM = (
    "Responda à pergunta usando SOMENTE o documento. Responda só com o valor pedido, exatamente como aparece no documento, sem "
    "explicações."
)

DOC_ATA = """ATA DA REUNIÃO DE ALINHAMENTO — EQUIPE ALFA
Data: 14 de agosto de 2026. Local: sala Jabuticaba. Participantes: Helena, Otávio, Priscila e Raul.

1. Abertura. Helena abriu a reunião às 9h40 lembrando que o código do projeto é ALFA-7731 e que todas as mensagens sobre ele devem citar esse código. O código antigo, ALFA-7713, pertencia à fase de testes e está encerrado.

2. Orçamento. Otávio apresentou as contas do trimestre e lembrou que todos os gastos acima de R$ 500 precisam de aprovação prévia. O grupo aprovou o plano de compras sem alterações.

3. Cronograma. Priscila informou que a primeira entrega será em 9 de setembro, a segunda em 21 de outubro e a entrega final em 2 de dezembro. A data de 12 de dezembro, que circulou antes por e-mail, foi descartada porque coincide com o recesso.

4. Encerramento. A reunião terminou às 11h15 e a próxima ficou marcada para 28 de agosto, na sala Pitanga.

Anexo — Fornecedor. Raul confirmou que o fornecedor escolhido é a Gráfica Aurora. O contato atual é Mariana Teles, telefone (21) 4002-8922. O contato anterior, Mariana Telles, telefone (21) 4002-8229, não deve mais ser usado."""

DOC_EMAIL = """Subject: Kickoff for Project Harbor

Hi team,

thanks for joining the kickoff call. Here is a short summary of what we agreed.

The project code name is HARBOR-204 (please do not confuse it with HARBOR-402, which belongs to the logistics team). Every ticket and every meeting invite should carry the code name in the title.

Our sprint length is 2 weeks, and the first sprint starts on March 3. The staging server is reachable at staging.harbor.example.com and the production server at app.harbor.example.com; only the staging server may be used for demos.

The design review is on Thursday at 4:30 PM (it was moved from 5:30 PM to avoid a clash with the finance call). The launch is planned for June 17; the earlier date of June 7 was cancelled after the vendor asked for more time.

Please send any questions to Dana Whitfield at dana.whitfield@example.com. The old address dana.whitfeld@example.com is no longer active.

Thanks,
Priya"""

DOC_MANUAL = """MANUAL DO USUÁRIO — VENTANIA NOTAS (aplicativo fictício, versão 4.2)

1. Primeiros passos
O Ventania Notas guarda anotações em cadernos. Ao abrir o aplicativo pela primeira vez, você cria um caderno e escolhe uma cor para ele. Cada nota pode ter título, texto formatado, etiquetas e anexos. O limite de anexos por nota é de 25 MB no total; arquivos maiores que isso são recusados com um aviso na tela (o limite antigo de 52 MB valia apenas para a versão 3). Para anexar, toque no clipe na barra de ferramentas e escolha um arquivo do aparelho.

2. Cadernos e etiquetas
Os cadernos agrupam notas por assunto. Você pode mover uma nota de um caderno para outro arrastando o cartão dela ou usando o menu de três pontos. As etiquetas são palavras livres que atravessam os cadernos: uma nota pode ter quantas etiquetas quiser, e a busca por etiqueta mostra todas as notas marcadas, de qualquer caderno. Para renomear uma etiqueta em todas as notas de uma vez, abra a tela de etiquetas e toque no nome dela por alguns segundos.

3. Formatação
O editor aceita negrito, itálico, listas com marcadores, listas numeradas, listas de tarefas com caixas de seleção e títulos de dois níveis. Os atalhos de teclado físico seguem o padrão dos editores de texto: Ctrl+B para negrito, Ctrl+I para itálico e Ctrl+Shift+7 para lista numerada. A formatação é guardada junto com a nota e aparece igual no aplicativo da web.

4. Modelos de nota
Para não começar sempre do zero, o aplicativo traz modelos de nota: lista de compras, ata de reunião, diário e planejamento semanal. Você também pode transformar qualquer nota sua em modelo pelo menu de três pontos, e os modelos aparecem na hora de criar uma nota nova. Alterar um modelo não muda as notas que já foram criadas a partir dele; cada nota guarda a sua própria cópia do texto e da formatação.

5. Lembretes
Qualquer nota pode receber um lembrete com data e hora. No horário marcado o aplicativo mostra uma notificação com dois botões: adiar por dez minutos ou marcar como feito. Se o aparelho estiver desligado no horário, o lembrete é mostrado assim que ele ligar de novo. Lembretes recorrentes podem se repetir todo dia, toda semana ou todo mês, e a repetição para quando você marca a nota como concluída.

6. Sincronização
Com uma conta, as notas são sincronizadas entre todos os seus aparelhos. A sincronização acontece em segundo plano sempre que há conexão e a bateria não está em modo de economia. Se duas pessoas editarem a mesma nota ao mesmo tempo, o aplicativo guarda as duas versões e mostra um aviso para você escolher qual manter. O primeiro envio de um caderno grande pode demorar alguns minutos.

7. Uso sem conexão
Todas as funções de escrita, pesquisa e organização funcionam sem conexão. As alterações feitas sem rede ficam numa fila no aparelho e são enviadas quando a conexão volta, na ordem em que foram feitas. Se a fila ficar muito grande, o aplicativo mostra um aviso na barra superior com o número de alterações pendentes, e você pode abrir a fila para ver o que ainda não foi enviado.

8. Pesquisa
A caixa de pesquisa procura no título, no texto e nas etiquetas. Você pode combinar palavras: a busca mostra apenas as notas que contêm todas elas. Para procurar uma frase exata, coloque-a entre aspas. A pesquisa funciona sem conexão, porque o índice fica guardado no próprio aparelho.

9. Lixeira
Quando você apaga uma nota, ela vai para a lixeira. Na lixeira, a nota ainda pode ser restaurada para o caderno de origem. O prazo de exclusão definitiva da lixeira é de 30 dias; passado esse prazo a nota é apagada para sempre e não pode mais ser recuperada. Os prazos de 15 dias e de 60 dias, que aparecem em versões antigas deste manual, não valem mais. Você pode esvaziar a lixeira antes do prazo pelo menu de três pontos da própria lixeira.

10. Compartilhamento
Uma nota pode ser compartilhada por link. Quem recebe o link pode apenas ler, a menos que você ative a opção de edição. Você pode desativar o link a qualquer momento, e a nota deixa de ser acessível por ele na mesma hora. Notas protegidas por senha não podem ser compartilhadas por link.

11. Segurança
As notas podem ser protegidas por senha ou por biometria. Uma nota protegida fica cifrada no aparelho, e o texto dela não aparece nas notificações, na pesquisa nem nas telas recentes do sistema. Se você esquecer a senha de uma nota protegida, ela não pode ser recuperada, nem mesmo pelo suporte, porque a chave nunca sai do seu aparelho.

12. Exportação
É possível exportar um caderno inteiro em arquivo de texto, em formato aberto de notas ou em PDF. A exportação em PDF mantém a formatação e inclui os anexos de imagem. Os anexos de outros tipos vão em uma pasta separada, dentro do arquivo compactado gerado ao final.

13. Aparência
O aplicativo tem tema claro, tema escuro e a opção de seguir o sistema. O tamanho da fonte do editor pode ser ajustado em cinco níveis, e a fonte pode ser trocada por uma com espaçamento mais aberto para facilitar a leitura. As mudanças de aparência valem para todos os cadernos.

14. Importação
O aplicativo importa notas de arquivos de texto, de arquivos em formato aberto de notas e de PDFs com texto selecionável. Na importação, cada arquivo vira uma nota, e as pastas viram cadernos. Se um arquivo tiver o mesmo título de uma nota que já existe, o aplicativo pergunta se você quer manter as duas, substituir a antiga ou pular o arquivo.

15. Acessibilidade
O Ventania Notas funciona com leitores de tela e com ampliação do sistema. Todos os botões têm descrição falada, e a ordem de navegação segue a ordem visual da tela. O contraste do tema escuro foi medido para leitura prolongada, e é possível desativar as animações em Ajustes, na seção de movimento reduzido.

16. Problemas comuns
Se o aplicativo parar de sincronizar, confira a conexão e saia e entre na conta de novo. Se um anexo não abrir, confirme que há um aplicativo no aparelho capaz de abrir aquele tipo de arquivo. Se o aplicativo fechar sozinho ao abrir uma nota muito grande, divida a nota em duas e envie o arquivo de diagnóstico pelo menu de ajuda.

17. Privacidade e dados
O aplicativo só envia ao servidor o conteúdo das notas que você escolheu sincronizar. As estatísticas de uso são anônimas e podem ser desligadas em Ajustes. Ao excluir a conta, todos os cadernos guardados no servidor são apagados em até sete dias, e as cópias no aparelho continuam até você removê-las.

18. Atualizações
As versões novas saem a cada seis semanas e trazem correções e melhorias pequenas; mudanças maiores são anunciadas com antecedência no próprio aplicativo. O histórico de mudanças fica em Ajustes, em Sobre. Ao atualizar, as notas e os ajustes são mantidos, e a primeira abertura depois da atualização pode levar alguns segundos a mais por causa da reindexação da pesquisa.

19. Contato
Para dúvidas e relatos de problemas, escreva para o suporte pelo endereço ajuda@ventania.example.com. O endereço antigo, suporte@ventania.example.com, foi desativado e as mensagens enviadas para ele não são lidas. O suporte responde em até dois dias úteis, de segunda a sexta-feira."""

DOC_INFORME = """INFORME TRIMESTRAL DE OPERACIONES — ALMACÉN SUR (documento ficticio)

1. Resumen
Durante el trimestre, el almacén procesó 18.450 pedidos, un 6 % más que en el trimestre anterior. Hubo un error de registro en el primer borrador del informe, que mencionaba 18.540 pedidos; la cifra correcta es la que figura en este párrafo. El tiempo medio entre la llegada del pedido y la salida del paquete bajó de 31 a 27 horas gracias al nuevo orden de los pasillos.

2. Personal
La plantilla se mantuvo estable. Se incorporaron dos operarios en el turno de mañana y uno en el turno de tarde. El responsable del turno de noche es Gabriela Ortiz, que reemplazó a Gabriel Ortega en septiembre. Todas las personas del turno de noche completaron el curso de seguridad en el trabajo antes de fin de mes.

3. Recepción de mercancías
Los camiones llegan entre las 6:00 y las 14:00. Cada descarga se registra con el número de muelle, la matrícula y la hora de entrada. En el trimestre se rechazaron 41 entregas por embalaje dañado, la mayoría de ellas de un solo proveedor, que fue avisado por escrito. Las entregas aceptadas se almacenan en el mismo día, salvo las que requieren revisión de calidad.

4. Almacenamiento
El almacén tiene doce pasillos numerados y una zona de picking rápido junto a los muelles de salida. Los productos de rotación alta se guardan en la zona de picking rápido; los de rotación baja, en las estanterías altas. Cada ubicación tiene una etiqueta con código de barras, y los operarios confirman cada movimiento con el lector manual.

5. Inventario
Se hicieron cuatro inventarios cíclicos y uno general. La diferencia media entre el stock del sistema y el stock físico fue de 0,4 %, por debajo del objetivo de 0,5 %. Las diferencias mayores se concentraron en los artículos pequeños, que ahora se guardan en cajas con tapa y con etiqueta propia.

6. Calidad
El área de calidad revisa una muestra de cada lote recibido antes de autorizar su almacenamiento definitivo. En el trimestre se revisaron 312 lotes y se bloquearon 19 por diferencias de etiquetado o de fecha de caducidad. Los lotes bloqueados se guardan en una jaula señalizada hasta que el proveedor responda por escrito.

7. Transporte interno
Dentro del almacén se usan seis carretillas elevadoras y catorce transpaletas manuales. Las carretillas se revisan cada mañana con una lista de comprobación, y las baterías se cargan en una sala ventilada fuera del horario de picos. Ninguna carretilla estuvo parada más de un día por avería durante el trimestre.

8. Cámara fría
La cámara fría se usa para productos farmacéuticos y alimentos envasados. La temperatura objetivo es de 4 grados; el límite de alarma, que no debe confundirse con el objetivo, es de 8 grados. Cada día se registran dos lecturas de temperatura, una al abrir y otra al cerrar el almacén. No hubo alarmas durante el trimestre, y el mantenimiento preventivo del equipo se hizo en la fecha prevista.

9. Costes
El coste medio por pedido preparado bajó un 4 % respecto del trimestre anterior, sobre todo por la reducción de horas extra en el turno de tarde. El gasto en embalaje aumentó un 2 % por el precio del cartón. La partida de mantenimiento se mantuvo dentro del presupuesto aprobado a principios de año.

10. Expedición
Los paquetes se agrupan por transportista y se cargan en los muelles de salida. Los transportistas firman el albarán de entrega en papel o en la tableta. Se registraron nueve incidencias de salida, todas resueltas en menos de 24 horas, y ninguna provocó un reclamo formal de los clientes.

11. Devoluciones
Las devoluciones se reciben en una zona separada y se clasifican en tres grupos: reintegrable al stock, reparable y descartable. El 72 % de las devoluciones volvió al stock sin necesidad de reparación. El resto se envió al taller o se descartó según el estado del producto.

12. Medio ambiente
El almacén separó y entregó a gestores autorizados 14 toneladas de cartón, 3 de plástico y 1 de madera. Las luminarias de los pasillos uno a diez ya son de bajo consumo, y el consumo eléctrico del trimestre fue un 5 % menor que el del año anterior en las mismas fechas.

13. Seguridad
No hubo accidentes con baja. Se hicieron dos simulacros de evacuación y uno de incendio, con tiempos dentro del objetivo. Los extintores y las salidas de emergencia se revisaron en agosto, y el pasillo nueve recibió nueva señalización en el suelo.

14. Mejoras previstas
Para el próximo trimestre se prevé instalar iluminación de bajo consumo en los pasillos once y doce, probar un nuevo sistema de etiquetado para las estanterías altas y reducir a 24 horas el tiempo medio de preparación de pedidos. También se estudiará añadir un segundo lector de códigos en la zona de picking rápido.

15. Formación
Todo el personal nuevo recibe una semana de formación con un compañero experimentado antes de trabajar solo. En el trimestre se impartieron cuatro cursos internos: manejo de carretillas, uso del lector manual, primeros auxilios y gestión de devoluciones. La asistencia media fue del 94 %.

16. Proveedores
Se mantuvieron reuniones trimestrales con los cinco proveedores principales. Se acordó mejorar el embalaje de los productos frágiles y unificar el formato de las etiquetas de lote. El proveedor con más entregas rechazadas presentó un plan de corrección con fechas, que será revisado en el próximo informe.

17. Accesos
El acceso principal usa tarjeta personal. La puerta trasera, que solo se abre para descargas especiales, funciona con un código numérico que cambia cada trimestre. El código vigente de la puerta trasera es 7482; el código del trimestre anterior, 7428, ya no funciona. Ningún código debe compartirse por mensaje escrito."""

# (id, idioma, doc, pergunta, valor, distratores, posição)
CONTEXTO = [
    ("cx-01", "pt", DOC_ATA, "Qual é o código do projeto?", "ALFA-7731", ["ALFA-7713"], "comeco"),
    ("cx-02", "en", DOC_EMAIL, "What time is the design review?", "4:30 PM", ["5:30 PM"], "meio"),
    ("cx-03", "pt", DOC_MANUAL, "Qual é o limite de anexos por nota?", "25 MB", ["52 MB"], "comeco"),
    ("cx-04", "es", DOC_INFORME, "¿Cuántos pedidos procesó el almacén en el trimestre?", "18.450", ["18.540"], "comeco"),
    ("cx-05", "pt", DOC_ATA, "Qual é a data da entrega final?", "2 de dezembro", ["12 de dezembro"], "meio"),
    ("cx-06", "en", DOC_EMAIL, "Which email address should questions be sent to?", "dana.whitfield@example.com", ["dana.whitfeld@example.com"], "fim"),
    ("cx-07", "pt", DOC_MANUAL, "Qual é o prazo de exclusão definitiva da lixeira?", "30 dias", ["15 dias", "60 dias"], "meio"),
    ("cx-08", "es", DOC_INFORME, "¿Cuál es la temperatura objetivo de la cámara fría?", "4 grados", ["8 grados"], "meio"),
    ("cx-09", "pt", DOC_ATA, "Qual é o telefone do contato atual do fornecedor?", "(21) 4002-8922", ["(21) 4002-8229"], "fim"),
    ("cx-10", "en", DOC_EMAIL, "What is the project code name?", "HARBOR-204", ["HARBOR-402"], "comeco"),
    ("cx-11", "pt", DOC_MANUAL, "Qual é o endereço de e-mail do suporte?", "ajuda@ventania.example.com", ["suporte@ventania.example.com"], "fim"),
    ("cx-12", "es", DOC_INFORME, "¿Cuál es el código vigente de la puerta trasera?", "7482", ["7428"], "fim"),
]


def teste_contexto():
    prompt = {"system": CONTEXTO_SYSTEM, "user": "{entrada}"}
    casos = []
    for i, lg, doc, pergunta, valor, distratores, posicao in CONTEXTO:
        entrada = f"Documento:\n{doc}\n\nPergunta: {pergunta}"
        casos.append({"id": i, "idioma": lg, "entrada": entrada, "esperado": valor, "valor": valor, "distratores": distratores,
                      "posicao": posicao, "maxTokensSaida": 48, "tokensEntrada": estimar_tokens(CONTEXTO_SYSTEM, entrada)})
    return {
        "teste": "contexto", "versao": 1, "capacidade": "TEXTO",
        "descricao": "Mede se o modelo acha um detalhe exato num documento (de ~300 e ~2 mil tokens), no começo, no meio ou no fim.",
        "prompt": prompt, "casos": casos,
    }


# ── gravação ────────────────────────────────────────────────────────────────────────────────────────
def escrever(caminho, dados):
    texto = json.dumps(dados, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    caminho.write_text(texto, encoding="utf-8")


def sha256(caminho):
    return hashlib.sha256(caminho.read_bytes()).hexdigest()


def manifesto():
    arquivos = []
    for nome in ["pontuacao", "reescrita", "json", "acoes", "contexto"]:
        arquivos.append({"caminho": f"{nome}.json", "sha256": sha256(DESTINO / f"{nome}.json"), "origem": ORIGEM, "autor": AUTOR, "licenca": LICENCA})
    atual = DESTINO / "manifesto.json"
    if atual.exists():
        existentes = json.loads(atual.read_text(encoding="utf-8"))["arquivos"]
        arquivos.extend(item | {"sha256": sha256(DESTINO / item["caminho"])} for item in existentes
                        if item["caminho"] not in {a["caminho"] for a in arquivos})
    escrever(atual, {"corpus": CORPUS, "arquivos": arquivos})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ferramentas", help="JSON com as definições do ToolRegistry real (gravado pelo AcoesFerramentasTest)")
    ap.add_argument("--manifesto", action="store_true", help="só recalcula os SHA-256 do manifesto")
    args = ap.parse_args()
    DESTINO.mkdir(parents=True, exist_ok=True)
    if not args.manifesto:
        if args.ferramentas:
            ferramentas = json.loads(Path(args.ferramentas).read_text(encoding="utf-8"))
        else:
            atual = DESTINO / "acoes.json"
            if not atual.exists():
                sys.exit("sem acoes.json anterior: passe --ferramentas (rode o AcoesFerramentasTest para gerar o arquivo)")
            ferramentas = json.loads(json.loads(atual.read_text(encoding="utf-8"))["ferramentas"])
        escrever(DESTINO / "pontuacao.json", teste_pontuacao())
        escrever(DESTINO / "reescrita.json", teste_reescrita())
        escrever(DESTINO / "json.json", teste_json())
        escrever(DESTINO / "acoes.json", teste_acoes(ferramentas))
        escrever(DESTINO / "contexto.json", teste_contexto())
    manifesto()
    total = sum(f.stat().st_size for f in DESTINO.rglob("*") if f.is_file())
    print(f"corpus v{CORPUS}: {len(list(DESTINO.iterdir()))} arquivos, {total} bytes em {DESTINO.relative_to(RAIZ)}")


if __name__ == "__main__":
    main()

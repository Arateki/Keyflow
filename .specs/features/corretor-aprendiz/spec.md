# Corretor aprendiz (correção automática que aprende com a pessoa) — Specification

> **Spec para o Yan ler antes de qualquer código.** Nada disto está implementado. Estudo, medições e
> licenças em `context.md`; arquitetura e fórmulas em `design.md`; tarefas pequenas em `tasks.md`.
> Pedido do Yan (2026-10-24): o corretor "faz algumas correções que não têm sentido" e deve funcionar como
> os teclados atuais, **aprendendo com as correções que a própria pessoa faz**. Ampliação do mesmo dia: o
> melhor de cada projeto aberto, mirando acerto e eficiência, com um banco de avaliação offline que mostre
> o antes e o depois de cada mudança em números.

## Problem Statement

O corretor do KeyFlow (Q9) usa um dicionário fixo de ~100 mil palavras por idioma (listas do AOSP
LatinIME) e troca a palavra no espaço quando uma candidata ganha com folga. Ele não tem memória:
desfazer a troca com o backspace volta a palavra, mas na vez seguinte ele troca de novo.

**Estimativa preliminar, não digitação real** (`context.md` §3): numa lista de 548 palavras que um agente
montou por categoria, como um brasileiro digitaria (abreviações, gírias, nomes, marcas, termos técnicos, inglês
no meio; pt), 322 não estão no dicionário e **165 (51%) são trocadas** no espaço; ~10 dessas trocas são
legítimas ("nao"→"não"), logo ~155 (**~48%**) são **indevidas**: "vc"→"VC", "td"→"te", "hj"→"um",
"Yan"→"Hab", "deploy"→"depois", "push"→"pus", "Ágatha"→"Agatha". O percentual depende da mistura de
grupos que a lista tem; o número que vale sai do banco (CA-T1, sobre a lista versionada) e das ~600 palavras
que o Yan digita no A32 (CA-T2). Medido no dicionário, não em digitação: das 2260 palavras comuns do inglês
(entre as 3000 mais frequentes) que não estão no dicionário pt, 881 (39%; 29% das 3000) viram outra palavra em
português. O acerto onde o corretor é bom (acento que faltou, tecla vizinha) existe, mas o estrago nas palavras
fora do dicionário é o que a pessoa percebe, e ele se repete sem parar.

Duas frentes, na ordem do que dá mais acerto por esforço:

1. **Parar de estragar o que não é erro** (base sem aprendizado + aprender com o que a pessoa recusa).
2. **Acertar mais onde é erro** (modelo espacial do toque, adaptação ao jeito de tocar, palavra anterior).

## Goals

- [ ] Banco de avaliação offline (JVM) que mede acerto, troca indevida e tempo por palavra — **primeira tarefa**.
- [ ] Trocas **indevidas** nas palavras fora do dicionário caem para ≤ 10% no banco (meta provisória, a confirmar na CA-T1 e nas palavras reais da CA-T2; ponto de partida preliminar ~48%, de uma lista montada por agente), sem perder acerto nos erros reais.
- [ ] A pessoa recusa uma troca **uma vez** (backspace logo após ou escolher a palavra original na faixa) e o corretor não repete.
- [ ] Palavras novas da pessoa passam a valer sozinhas depois de poucos usos, com contagem que decai; erro de digitação isolado não é aprendido.
- [ ] Correções que a pessoa faz (tocar numa sugestão, apagar e reescrever) viram pares "erro → certo".
- [ ] Nada sai do aparelho, salvo o arquivo que a pessoa mesma pede ao exportar (LST-06); não aprende em senha, número, e-mail, URL, campo `NO_SUGGESTIONS` nem com `IME_FLAG_NO_PERSONALIZED_LEARNING`; há um modo anônimo manual (PRV-07); "Apagar meus dados" e uma tela própria apagam o aprendido.
- [ ] A pessoa exporta e importa o que o corretor aprendeu por arquivo, quando quiser (LST-06, LST-07).
- [ ] Modelo espacial com a coordenada do toque no lugar da tabela de teclas vizinhas, com o custo de CPU medido no Galaxy A32 (PER-01).
- [ ] Adaptação ao jeito de tocar (fase D): viés por tecla no modelo e, como pediu o Yan (resposta 9, 24/10), o experimento de deslocar também o alvo do toque, medido no banco e com protótipo só no debug.
- [ ] Contexto da palavra anterior (bigramas), como fase posterior.

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Modelo neural no aparelho (estilo FUTO) | Resposta 12 do Yan (24/10): só depois, pelo catálogo de modelos sob demanda e com licença compatível (o do FUTO fica fora). Critérios para revisitar em `design.md` §10: 60+ MB, runtime nativo, sem modelo pt-BR aberto e sem medida no A32 |
| Digitação por deslize (glide) | Outro motor; a coordenada do toque desta spec é só de toque |
| Sincronizar o aprendido entre aparelhos / nuvem | Contraria "nada sai do aparelho". A exportação por arquivo (LST-06) não é sincronização: a pessoa move o arquivo, o app nunca |
| Atalho do modo anônimo no teclado (barra, toque longo) | O modo anônimo manual é o interruptor da aba Teclado (PRV-07); um atalho só se o Yan pedir |
| Dicionário de contatos (`READ_CONTACTS`) | Resposta 5 do Yan (24/10): sem a permissão; os nomes vêm do uso (LRN) |
| Corrigir palavra **válida** por contexto ("casa" → "caso") | Sem precisão medida ≥ 99% no banco, só sugerir (já é assim) |
| Ditado por voz | Esta spec é só do teclado de toque |
| Expansão de abreviações ("vc" → "você") ligada por padrão | Seria nova troca automática; só como par que a pessoa ensina (FIX) |

---

## User Stories & Acceptance Criteria

### Banco de avaliação offline (`BEN`) — primeira tarefa da implementação

**Como** dono do projeto, **quero** ver em números o que cada mudança do corretor melhora ou piora.

1. **BEN-01**: WHEN `testDebugUnitTest` roda THEN o banco SHALL rodar, no **nível rápido** (BEN-09), o `Suggester` real com os dicionários `pt` (e `en`/`es` quando o caso pede), na JVM, sem aparelho, determinístico (sementes e listas fixas), e gravar `app/build/bench/corretor.json` e uma tabela legível.
2. **BEN-02**: WHEN o banco monta os casos THEN SHALL haver cinco conjuntos: (a) **manter** — palavras fora do dicionário que **não** devem ser trocadas (abreviações, risadas, gírias, nomes, marcas, termos técnicos, inglês e espanhol no meio, alongamentos; ≥ 300, começando pelas do estudo), em dois arquivos: `manter-dev` (o que as tasks de curadoria, como o léxico da CA-T5, podem ler) e `manter-teste` (reservado: sorteio com semente fixa, por grupo; nenhuma task de curadoria o lê; a catraca e a meta valem nele); (b) **erros** — erros de digitação reais escritos à mão com a palavra pretendida (≥ 300; omissão, inserção, troca de ordem, tecla vizinha, acento, "ç/ss", "s/z", "x/ch"); (c) **sintético** — palavras do dicionário digitadas com ruído de toque sobre o layout real (`Layouts` + `Geometry`), ruído gaussiano em fração de tecla (σ 0,20 / 0,30 / 0,40), viés por fileira e por usuário, taxas de omissão/inserção/troca de ordem, amostra uniforme e ponderada por frequência, ≥ 2000 por configuração no nível completo (BEN-09); (d) **reais** — digitações gravadas no aparelho com coordenadas (BEN-07), arquivo local fora do git; (e) **sessões** — uma pessoa simulada digitando um texto longo com palavras fora do dicionário, desfazendo trocas, para medir a curva de aprendizado.
3. **BEN-03**: WHEN o banco mede THEN SHALL reportar por conjunto: **trocas certas** (troca no espaço = pretendida), **trocas erradas**, **trocas indevidas** (qualquer troca onde o esperado era manter; trocar só a caixa ou tirar um acento digitado conta), **perdidas**, a pretendida na **faixa em 1º** e **nos 3 primeiros**, a parcela de erros que **viram outra palavra válida** (o corretor não toca), **ms por palavra** (p50/p95) e a memória do corretor — estes dois **só informativos** (BEN-04).
4. **BEN-04**: WHEN uma métrica **determinística** (contagens de trocas certas, erradas, indevidas e perdidas; posição da pretendida na faixa) piora em relação a `corretor-bench-baseline.json` (versionado, gerado do código atual na CA-T1) THEN o teste SHALL falhar; melhora só entra atualizando o baseline no mesmo commit, com a tabela antes/depois na mensagem (catraca). **Tempo e memória nunca derrubam o teste**: oscilam na máquina compartilhada (regra de ouro 5) e só são impressos.
5. **BEN-05**: WHEN `scripts/corretor-bench.sh [--completo] [ref-git]` roda THEN SHALL imprimir a tabela antes × depois (o baseline, ou o resultado de `ref-git`) para o dono do projeto comparar uma mudança; com `--completo`, no nível completo.
6. **BEN-06**: WHEN o tempo por palavra é medido no aparelho THEN um instrumentado SHALL rodar as mesmas palavras no emulador/A32 e conferir o orçamento de PER-01.
7. **BEN-07**: WHEN a build é **debug** THEN um gravador local e opt-in SHALL permitir digitar frases-guia no teclado e salvar (coordenadas do toque — SPA-01 —, palavra digitada, palavra pretendida) em arquivo do app, retirado só por `adb pull`; nunca na release (conferido por script no APK/AAB), nunca na rede, fora do backup (`files/bench`, nas três seções). As frases-guia cobrem os grupos do `manter` além do português corrente, em duas rodadas (normal e rápida): a digitação é real, mas guiada. O gravador só fecha depois de o ponto do toque chegar ao serviço (CA-T20).
8. **BEN-08**: WHEN uma mudança do corretor é proposta THEN o PR SHALL citar a tabela do banco; nenhuma mudança de limiar entra "por achismo".
9. **BEN-09** (orçamento): WHEN o banco roda THEN SHALL haver dois níveis. **Rápido** (`testDebugUnitTest`, catraca): `manter-dev`, `manter-teste` e `erros` (≥ 600 palavras) mais um sintético reduzido de 4 configurações de 500 (σ 0,30 e 0,40 × viés nenhum e forte × amostra ponderada) ≈ 2 600 sugestões, ≈ 14–22 s pela conta dos números do protótipo (5,4–8,6 ms por sugestão), alvo < 60 s, número real medido na CA-T1. **Completo** (só `scripts/corretor-bench.sh --completo`, não entra no `testDebugUnitTest`): 3 σ × 3 vieses × 2 amostras × 2 taxas = 36 configurações × ≥ 2000 = 72 000 sugestões, que dão 6,5–10,3 min em uma thread (72 000 × 5,4–8,6 ms); calibra os limiares (CA-T21) e alimenta a tabela do PR.

### Base sem aprendizado (`BAS`)

**Como** pessoa que digita em português do Brasil, **quero** que o corretor pare de trocar o que não é erro **desde a primeira vez**.

1. **BAS-01**: WHEN uma candidata é avaliada THEN a decisão de trocar SHALL ser **por classe** — acento que faltou, inicial maiúscula (nome próprio), **sigla** (tudo maiúsculo), 1 edição, 2 edições, par ensinado pela pessoa (FIX) — cada classe com o seu limiar, calibrado no banco; classe com precisão < 95% no banco **só sugere**.
2. **BAS-02**: WHEN a candidata de troca é a **sigla** da palavra digitada (a mesma palavra toda em maiúsculas, de qualquer tamanho: "vc"→"VC", "tb"→"TB", "ok"→"OK", "api"→"API", "url"→"URL") THEN o corretor SHALL só sugerir (resposta 2 do Yan, 24/10: siglas só sugerem, inclusive `api`→`API`; o `RealDictionaryTest` passa a esperar só a sugestão). A troca de caixa que não é sigla ("paulo"→"Paulo", inicial maiúscula de nome próprio) segue a regra da sua classe (BAS-01).
3. **BAS-03**: WHEN a palavra digitada tem acento ou cedilha que a pessoa digitou THEN o corretor SHALL **nunca** trocá-la por forma com menos acento ("Ágatha"→"Agatha", "Taís"→"Tais", "piá"→"pia", "parça"→"parca"); a escolha de forma entre as de mesmas letras respeita o que foi digitado.
4. **BAS-04**: WHEN a palavra tem 3 ou mais letras iguais seguidas ("kkkk", "simmm", "nãooo", "oiii") ou é só consoantes de até 4 letras ("vc", "tb", "td", "hj", "msg") THEN o corretor SHALL só sugerir.
5. **BAS-05**: WHEN a palavra digitada existe no dicionário de **outro** idioma instalado (en/es) com frequência ≥ F (calibrada no banco) e o teclado ativo é pt THEN o corretor SHALL só sugerir ("deploy", "push", "the", "you"; no dicionário de hoje, 881 das 2260 palavras comuns do inglês fora do pt — 39% — e 992 das 1575 do espanhol — 63% — viram outra palavra; `context.md` §3).
6. **BAS-06**: WHEN a palavra tem hífen ("faça-o", "bem-vindo") THEN o corretor SHALL tratar as partes e o conjunto antes de trocar ("faça-o" → "facão" some).
7. **BAS-07**: WHEN o léxico informal do pt-BR (abreviações, risadas, gírias, anglicismos e marcas de uso comum) entra no dicionário (`scripts/build-dictionaries.py`, lista curada própria `scripts/dict-extras/pt.tsv`, escrita à mão: sem copiar listas de terceiros; qualquer fonte externa entra com nome, URL e licença registrados) THEN essas palavras SHALL valer como palavra, com frequência baixa o bastante para não virarem candidatas de erro de outras.

### Palavras do usuário (`LRN`)

**Como** pessoa, **quero** que o teclado passe a aceitar as palavras que eu uso.

1. **LRN-01**: WHEN uma palavra é comitada no separador (espaço, pontuação, enter) ou por toque na faixa, num campo elegível (PRV-02) THEN o sistema SHALL registrar o uso em memória e no arquivo do aprendido, por idioma do teclado: (a) palavra **fora** do dicionário e que **não vale** no aprendido (nova ou em observação) soma pontos pela tabela do LRN-03; (b) palavra que **já vale** no aprendido **renova**: soma 1 ponto (mesmo cooldown de 60 s) e `ultimoUso = agora`, para que a palavra usada todo dia nunca expire (LRN-07); (c) palavra do dicionário fixo não é registrada (não há o que aprender, e o dicionário não decai).
2. **LRN-02**: WHEN a palavra é elegível THEN SHALL ter 2 a 32 letras (mais `'` e `-` internos), sem dígito, sem 3+ letras iguais seguidas, não toda em maiúsculas; a inicial maiúscula só por começo de frase é guardada em minúscula (ideia do `wasAutoCapitalized` do LatinIME), a de nome próprio no meio da frase fica como digitada.
3. **LRN-03**: WHEN há **pontos** THEN a tabela SHALL ser: digitada como está 1 (no máx. 1 por palavra a cada 60 s: "kkk kkk kkk" não vale 3); **renovação** de palavra que já vale 1 (mesmo cooldown); **recusa confirmada** (REJ-01) 3; **manter** pela faixa (REJ-02) 3; recusa por reescrita (REJ-03) 3.
4. **LRN-04**: WHEN a soma de pontos chega a **3** THEN a palavra SHALL passar a **valer**: o corretor nunca a troca, ela entra como completação por prefixo e como candidata de correção de erros de digitação dela.
5. **LRN-05**: WHEN uma palavra **seria trocada com confiança** pelo corretor (típico erro de digitação) **ou não se sabe se seria** (comit sem separador, como no Enter; sugestão que não chegou em `SUGGEST_WAIT_MS`; sugestões desligadas) THEN os usos "digitada como está" SHALL **não** contar (só a recusa explícita); assim o erro isolado nunca vira palavra do usuário. Desconhecido = sem ponto.
6. **LRN-06**: WHEN a troca foi feita e a pessoa **não** desfez THEN a palavra original SHALL **não** ganhar ponto nenhum (era erro de digitação).
7. **LRN-07**: WHEN o tempo passa THEN o peso SHALL decair com meia-vida de 90 dias (calculado na leitura, sem rodar tarefa): a palavra continua valendo enquanto o peso atual for ≥ 1 (cada uso a renova, LRN-01 (b)); sai quando < 0,5; entrada ainda não válida e parada há 30 dias é descartada; teto de 5000 palavras por idioma (sai a de menor peso).
8. **LRN-08**: WHEN o campo é de senha, número, e-mail, URL, filtro, terminal ou outro que não aprende (PRV-02), ou o app pede `IME_FLAG_NO_PERSONALIZED_LEARNING` (PRV-06), ou "Correção automática" está desligada, ou o **modo anônimo manual** está ligado (PRV-07) THEN **nada** SHALL ser aprendido.

### Correção recusada (`REJ`)

**Como** pessoa, **quero** dizer "não troque isto" uma vez e ser ouvido.

1. **REJ-01**: WHEN o backspace vem logo após a troca THEN o sistema SHALL desfazer (como hoje), **voltar a palavra original à composição** (não como texto solto) e marcar uma **recusa em espera** (original, troca); a recusa **confirma** quando a pessoa comita a **mesma** palavra de novo (espaço, pontuação ou enter) sem editá-la — no Enter a confirmação é **explícita** (a pessoa recomitou a palavra que acabou de desfazer), mas o Enter, que não passa pelo `Suggester`, **não** pontua `Typed` (DESCONHECIDO, LRN-05); editar a palavra, mover o cursor ou trocar de campo cancela a espera (um backspace por engano não ensina nada).
2. **REJ-02**: WHEN a pessoa toca, na faixa, a palavra **como digitada** (a que aparece quando há recusa em espera) THEN o sistema SHALL registrar a recusa na hora.
3. **REJ-03**: WHEN a pessoa apaga a palavra trocada **e reescreve a original** (RewriteTracker, FIX-02) THEN o efeito SHALL ser o de REJ-01 confirmada. Esta recusa é reconhecida **antes** das condições de par do FIX-02: o FIX-02 exige que a original **não** seja palavra válida e que V seja válida, então a recusa (V = original, que justamente não é válida) nunca passaria por ele; quando V = original e W foi uma troca, vale só o REJ-03 e nenhum par é somado.
4. **REJ-04**: WHEN a recusa confirma THEN a palavra digitada SHALL ganhar 3 pontos (LRN-03), passar a valer (LRN-04), e qualquer par "erro → certo" aprendido para ela SHALL ser apagado (a evidência mais recente vence).
5. **REJ-05**: WHEN a palavra voltou à composição após o desfazer THEN a próxima troca no espaço SHALL ser suprimida para ela uma vez, mesmo que o aprendizado esteja desligado ou o campo não aprenda.
6. **REJ-06**: WHEN a pessoa recusa uma troca de **acento** ("voce" mantido) THEN a palavra sem acento SHALL passar a valer como qualquer outra; a faixa continua oferecendo "você".

### Correção feita pela pessoa (`FIX`)

**Como** pessoa, **quero** que o teclado aprenda o que eu corrijo.

1. **FIX-01**: WHEN a pessoa toca na faixa uma sugestão diferente da digitada, e a digitada não é palavra do dicionário nem do aprendido, THEN o sistema SHALL somar 1 ponto ao par (digitada → escolhida).
2. **FIX-02**: WHEN a pessoa, logo após comitar uma palavra W, apaga com backspace (o separador e ≥ 1 letra de W) e digita o resto de uma palavra V (sem mover o cursor, sem passar de 20 s), e **V ≠ a digitada original** (V = original com W trocada é recusa, REJ-03, tratada antes), e V é palavra do dicionário ou do aprendido, e W (ou a digitada original, se W foi uma troca) não é palavra válida, e V está a ≤ 2 edições de W THEN o sistema SHALL somar 2 pontos ao par (original → V). O `RewriteTracker` lê só os eventos de tecla (letras, backspace, separador, mudança de cursor), nunca o texto do campo.
3. **FIX-03**: WHEN um par chega a **2 pontos** THEN SHALL valer como troca automática no espaço para aquela digitada (classe "par ensinado", sem o teste de folga), com a escolhida em 1º na faixa; só para digitadas fora do dicionário e fora do aprendido. Cada troca por par aplicada e **não desfeita** soma 1 ao par (mesmo cooldown), para que um par em uso não expire.
4. **FIX-04**: WHEN a pessoa recusa a troca de um par (REJ) THEN o par SHALL ser removido.
5. **FIX-05**: WHEN há pares e palavras para a mesma digitada THEN vale o evento mais recente (o oposto é zerado).
6. **FIX-06**: WHEN a pessoa corrige uma palavra de **outro** lugar (toca no texto e edita) THEN nada SHALL ser aprendido nesta versão (sem leitura do campo).

### Modelo espacial do toque (`SPA`)

**Como** pessoa que digita rápido, **quero** que o corretor entenda onde o dedo caiu.

1. **SPA-01**: WHEN uma letra é digitada pela grade THEN o evento SHALL levar o ponto do toque (`KeyTouch`) em **unidades de tecla** (x/largura média da tecla, y/altura da linha), sem depender do aparelho. O ponto é o do **`press`** da tecla que acaba saindo — o dedo que desceu ou o ponto em que escorregou para a tecla vizinha (`slideTo`) —, guardado por dedo **antes** da soltura, porque o rollover (`releaseEarly`) aciona a tecla sem ponto de soltura; se a soltura decide outra tecla sem ter passado pelo `press`, vale o ponto da soltura. A letra escolhida no **popup de acento** (ç, ã, é, comuns no pt) usa o toque da **tecla-base**, o do `press` que abriu o popup.
2. **SPA-02**: WHEN o corretor compara uma candidata THEN o custo de cada letra SHALL ser a probabilidade do toque dada a tecla da candidata — gaussiana 2D em bits, `d²/(2σ²)·log2 e` limitado —, no lugar de "vizinha custa meia edição"; omissão, inserção e troca de ordem mantêm custo fixo calibrado. Letra acentuada ou com cedilha (ã, ç, é) compara como a letra-base (a, c, e): o acento é decidido pelo popup, não pelo toque, e a diferença de acento segue pelas regras de acento (BAS-03); `ñ` é tecla própria no es.
3. **SPA-03**: WHEN o custo espacial e a frequência da palavra se combinam THEN o placar SHALL estar em bits (`prior = (255−freq)/8,589` — a escala do dicionário do LatinIME), e os limiares de troca por classe SHALL sair do banco, não de constantes escolhidas à mão.
4. **SPA-04**: WHEN o toque cai entre duas teclas THEN as duas letras SHALL ser candidatas com custos parecidos (hoje a vizinha custa o mesmo, perto da borda ou no centro da outra tecla).
5. **SPA-05**: WHEN o modelo roda THEN a tabela espacial SHALL custar ≤ 2 ms por palavra no A32 e +0 alocação por comparação (a tabela `bits[toque][letra]` é calculada uma vez por palavra). O custo do laço da distância (+32% a +35% por palavra no protótipo sem otimizar, `context.md` §7) é a otimizar na CA-T19 e a medir no A32 na CA-T22.
6. **SPA-06**: WHEN uma palavra tem letras **sem toque** (o clique de acessibilidade/TalkBack aciona a ação base sem coordenada, A11Y-02; palavra restaurada pelo desfazer cujos toques não foram guardados) THEN o custo SHALL ser calculado **letra a letra**: com toque, o custo em bits do SPA-02; sem toque, o custo que a tabela de vizinhas dá hoje (igual, vizinha, outra) convertido em bits, com constantes de partida calibradas no banco (CA-T21). Uma palavra pode misturar as duas; uma palavra inteira sem toque dá o comportamento de hoje. Teclas físicas e ditado não passam pelo `handleKey` (Q9: o corretor ainda não os cobre).

### Adaptação ao jeito de tocar (`ADP`)

1. **ADP-01**: WHEN uma palavra é comitada **como digitada**, está no dicionário, tem ≥ 3 letras e não foi desfeita THEN o deslocamento de cada toque em relação ao centro da tecla SHALL atualizar, por média móvel com prior em zero (α = 1/(n+20)), o viés (μx, μy) daquela tecla; teto |μ| ≤ 0,25 tecla; 26 teclas × 3 números por layout.
2. **ADP-02**: WHEN o viés existe THEN o custo espacial SHALL usar o centro deslocado (SPA-02); o **hit-test** da grade não muda por causa disso (deslocar o alvo de toque muda o que a pessoa vê): só o experimento do ADP-05 mexe nele, e só no debug.
3. **ADP-03**: WHEN a pessoa apaga o aprendido (PRV-04) THEN o viés SHALL voltar a zero.
4. **ADP-04**: WHEN os toques vêm do modo anônimo manual (PRV-07) ou de campo que não aprende (PRV-02, PRV-06) THEN nenhum viés SHALL ser atualizado.
5. **ADP-05** (fase D, resposta 9 do Yan, 24/10): WHEN a fase D roda THEN o banco SHALL medir o **teto** de deslocar também o hit-test (erro por letra com e sem compensar o viés conhecido, no sintético com viés e nos toques reais da CA-T2) e SHALL existir um **protótipo só no debug**, atrás de opção desligada por padrão, que desloca o ponto do toque pelo viés da tecla antes de decidir a tecla (a prévia e a letra que sai usam a mesma tecla); o Yan testa no A32. Ligar na release só depois dos números do banco e do teste dele, porque muda o que o dedo vê.

### Contexto da palavra anterior (`CTX`) — fase posterior

1. **CTX-01**: WHEN uma palavra é comitada e a anterior é palavra do mesmo trecho (sem pontuação final, quebra de linha ou mudança de campo no meio) THEN o par (anterior, palavra) SHALL somar 1 no arquivo do aprendido (teto de 10 000 pares por idioma, mesma regra de decaimento).
2. **CTX-02**: WHEN há duas candidatas de troca parecidas THEN o bigrama conhecido SHALL entrar no placar em bits (`λ·P(w|anterior) + (1−λ)·P(w)`, λ de Witten-Bell) e poderá liberar a troca que sozinha não passava pela folga.
3. **CTX-03**: WHEN o banco de contexto mostra ganho THEN uma tabela estática de bigramas pt-BR de corpus CC0 (resposta 10 do Yan: corpus CC0 mais o histórico pessoal) SHALL entrar como base do CTX-02 (`context.md` §5: as listas do AOSP não trazem nenhum bigrama).
4. **CTX-04** *(opcional)*: WHEN a faixa está vazia após um espaço THEN SHALL mostrar até 3 próximas palavras prováveis.

### Privacidade (`PRV`)

1. **PRV-01**: WHEN algo é aprendido THEN SHALL ficar só neste aparelho: `files/corretor/` (nunca `cache/`), **fora** do backup em nuvem e da transferência entre aparelhos (`backup_rules.xml` e `data_extraction_rules.xml`, conferido pelo `BackupRulesTest`), sem nenhum uso de rede. **Única exceção:** o arquivo que a pessoa pede ao tocar "Exportar" (LST-06), gravado onde ela escolher; o app nunca o envia nem guarda cópia dele.
2. **PRV-02**: WHEN o campo é elegível para aprender THEN SHALL valer **todas** estas: (a) a regra da troca automática (`autoCorrectAllowed`: texto comum, fora de URL, e-mail, senha, filtro e terminal); (b) o campo **não** pede `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (0x80000); (c) o app não pediu `IME_FLAG_NO_PERSONALIZED_LEARNING` (constante 0x1000000, Android 8+; no 7 nunca aparece); (d) "Aprender com o que eu digito" ligado (PRV-03); (e) "Correção automática" ligada. A alínea (b) é mais estrita que a troca: o KeyFlow continua corrigindo e sugerindo em campo `NO_SUGGESTIONS` (STATE.md, 2026-09-28: a busca do Google marca assim só porque mostra as próprias sugestões), mas **não aprende** ali, como o AOSP, que trata esse campo como sem autocorreção e, portanto, sem aprendizado (`mInputTypeNoAutoCorrect`, `context.md` §4.1); os apps que usam a flag como sinal de privacidade ficam respeitados. As outras causas de "sem autocorreção" do AOSP (web edit sem flag, linha única sem `AUTO_CORRECT`) não entram, porque o KeyFlow corrige nesses campos de propósito (Q9).
3. **PRV-03**: WHEN a pessoa abre a aba Teclado THEN SHALL haver o interruptor "Aprender com o que eu digito", **ligado por padrão** (resposta 3 do Yan), e desligá-lo liga o modo anônimo manual (PRV-07).
4. **PRV-04**: WHEN a pessoa usa **"Apagar meus dados"** (aba Privacidade) THEN o aprendido local (palavras, pares, bigramas, vieses) SHALL ser apagado **antes** da chamada à API, sem depender da rede; o texto da confirmação passa a dizer isso. Um botão próprio **"Apagar o que o corretor aprendeu"** faz só isto.
5. **PRV-05**: WHEN o aprender passa a valer por padrão (fase B) THEN os textos que descrevem dados SHALL já mencionar o aprendizado no aparelho: a política de privacidade (`LegalUrls.privacyPolicy`), o texto da aba Privacidade e a ficha da Play (Data Safety, `PLAYSTORE_CHECKLIST.md` §3, onde hoje consta "Keystrokes / dados digitados: Não coletados"), com a exportação (LST-06) descrita como arquivo que a pessoa guarda onde escolher. O Yan revisa esses textos quando a fase B chegar (resposta 11, 24/10); **a fase B não vai para a Play sem a revisão dele** (CA-T18b).
6. **PRV-06**: WHEN o app pede `NO_PERSONALIZED_LEARNING` (quem pede é, por exemplo, o modo anônimo do Chrome) THEN o aprendido que já existe SHALL continuar valendo para não trocar e para completar (é o que o LatinIME e o HeliBoard fazem: o campo só **não atualiza** o aprendido), e nada novo é gravado — nem palavra, nem par, nem bigrama, nem viés de toque, nem recusa confirmada.
7. **PRV-07** (modo anônimo manual, resposta 3 do Yan): WHEN a pessoa desliga o interruptor "Aprender com o que eu digito" (PRV-03) THEN o **modo anônimo manual** SHALL valer: nada novo é gravado (palavra, par, bigrama, viés de toque, recusa confirmada), o que já foi aprendido continua valendo para não trocar e para completar, e a troca e a faixa seguem como na BAS — o mesmo efeito do PRV-06, só que escolhido pela pessoa e para o teclado inteiro. O texto sob o interruptor explica isso (`tr`). Seguem permitidos apagar (PRV-04) e importar (LST-07, pedido explícito); a supressão da troca recusada (REJ-05) também. **Termos**: "modo anônimo manual" é só o do interruptor; o pedido do app é "campo que não aprende" (PRV-06). Não há atalho do modo no teclado nesta spec (Out of Scope).

### Tela "Palavras aprendidas" (`LST`)

1. **LST-01**: WHEN a pessoa abre a aba Teclado THEN SHALL haver o grupo **"Aprendizado do corretor"** com o interruptor de PRV-03 (desligado = modo anônimo manual, PRV-07, dito no texto sob ele), o número de palavras e pares, e a entrada para a lista.
2. **LST-02**: WHEN a pessoa abre a lista THEN SHALL ver, por idioma (pt/en/es), as palavras que valem, os pares "erro → certo" e a contagem das palavras ainda em observação, cada item com **Remover**, mais "Apagar tudo" (com confirmação).
3. **LST-03**: WHEN a pessoa remove uma palavra THEN ela SHALL deixar de valer na hora e o corretor SHALL voltar a tratá-la como qualquer palavra fora do dicionário.
4. **LST-04**: WHEN há texto novo THEN SHALL passar por `tr("…")` com inglês e espanhol (`TranslationsTest`); a tela coleta fluxo por `collectAsStateOnMain`, lê o arquivo fora da thread principal e tem `testTag` por item.
5. **LST-05**: WHEN as telas estabilizam THEN SHALL haver goldens da aba Teclado (novo grupo), da lista (vazia e com itens) e do diálogo de apagar, claro e escuro; os goldens existentes que não mudam ficam bit-idênticos.
6. **LST-06**: WHEN a pessoa toca **"Exportar"** no grupo "Aprendizado do corretor" THEN o app SHALL avisar que o arquivo contém palavras pessoais, fica fora da proteção do app e pode sair do aparelho se ela o guardar numa nuvem, e então gravar o aprendido (palavras, pares, bigramas e vieses de toque, por idioma) num arquivo que ela escolhe pelo seletor do sistema (`ACTION_CREATE_DOCUMENT`), em texto versionado com uma seção por idioma no formato de linha do ARM-01; nada passa pela rede e o app não guarda cópia (pedido do Yan, 24/10; desenho em `design.md` §8.1).
7. **LST-07**: WHEN a pessoa toca **"Importar"** e escolhe um arquivo (`ACTION_OPEN_DOCUMENT`) THEN o app SHALL validar versão, tamanho (≤ 5 MB) e cada linha, **forçando ao que vem do arquivo os mesmos limites do aprendizado** (peso finito em `[0, WEIGHT_CAP]`, data não futura, palavra elegível pelo LRN-02, tetos `MAX_WORDS`/`MAX_PAIRS`/`MAX_BIGRAMS`, `|μ| ≤ 0,25` no viés), mostrar o resumo (palavras, pares, bigramas e vieses por idioma, mais as linhas ignoradas) e, confirmado, **mesclar** com o aprendido atual (ocorrências somadas; par em conflito fica o mais recente); arquivo inválido não muda nada e mostra o erro; com o aprendizado desligado (modo anônimo manual) a importação continua permitida, porque é um pedido explícito.

### Armazenamento e desempenho (`ARM`, `PER`)

1. **ARM-01**: WHEN o aprendido é guardado THEN SHALL ser em **arquivo próprio** por idioma (`files/corretor/<idioma>.kfl`, texto em linhas com cabeçalho de versão), carregado uma vez por processo fora da main thread, mudanças em memória e **diário de acréscimos** (uma linha por evento) com compactação ao carregar; linha inválida é ignorada; apagar é apagar o arquivo. Por quê Room/DataStore não: `design.md` §8.
2. **ARM-02**: WHEN o teclado abre THEN o carregamento SHALL ser assíncrono como o do `Dictionaries` e `onStartInputView` SHALL só calcular booleanos (`StrictModeKeyboardTest` segue verde, nenhum `runBlocking` novo).
3. **ARM-03**: WHEN um evento é registrado THEN SHALL ser executado na thread `keyflow-suggest` (a mesma das sugestões), sem trava entre a thread principal e o disco; a tela de palavras usa a mesma trava curta.
4. **PER-01**: WHEN uma palavra é sugerida THEN o total SHALL ficar dentro do teto (**provisório: ≤ 25 ms no A32 com o JIT quente**; hoje ~15 ms, mas o p95 de hoje é desconhecido) e o acréscimo do aprendido (consulta + pares + candidatas pessoais) ≤ 1 ms; o carregamento do arquivo (5000 palavras) ≤ 50 ms; memória ≤ 1,5 MB por idioma ativo **sem bigramas** (estimativa, a medir na CA-T10; `design.md` §8). O teto só é fixado depois que a CA-T3 medir o p50/p95 de hoje no A32: 15 ms × 1,35 (o que o modelo espacial custou no protótipo, ainda sem otimizar) + 1 ms + 2 ms (tabela espacial) ≈ 23 ms, quase sem folga.
5. **PER-02**: WHEN o evento é gravado THEN o custo na thread de sugestões SHALL ser ≤ 0,3 ms; a compactação roda só ao carregar.

### Testes e rollout (`ROL`)

1. **ROL-01**: WHEN as regras são escritas THEN SHALL ser **puras** (JVM): `PersonalLexicon`, `LearnedStore`, `LearningPolicy`, `RewriteTracker`, `SpatialCosts`, `TouchBias`, `Bigrams`, com unitários, propriedade (kotest: decaimento monótono, ida e volta do arquivo, teto respeitado) e fuzz (arquivo com bytes aleatórios nunca derruba o app).
2. **ROL-02**: WHEN o fluxo de desfazer e aprender é testado THEN SHALL haver instrumentados no IME real (`KeyFlowKeyboardLearningTest`): desfazer + recomitar, faixa "manter", senha, `NO_SUGGESTIONS` e `NO_PERSONALIZED_LEARNING` não gravam nada, pares por toque na faixa, apagar tudo.
3. **ROL-03**: WHEN a feature entra THEN uma flag de debug SHALL desligar o aprendido para comparar com o corretor da BAS; em release o aprendido é o padrão.
4. **ROL-04**: WHEN o corretor muda THEN `RealDictionaryTest`/`SuggesterTest` existentes SHALL seguir verdes, salvo as expectativas que a BAS muda de propósito (listadas na CA-T4 com o motivo).

---

## Edge Cases

- WHEN o app congela o `InputConnection` ou ele é lento THEN o `RewriteTracker` e a confirmação da recusa não leem o campo (só eventos); o desfazer continua conferindo o texto como hoje antes de mexer.
- WHEN a pessoa troca de idioma (globo) no meio de uma recusa em espera THEN a espera é cancelada.
- WHEN o arquivo do aprendido está corrompido ou de versão futura THEN o corretor SHALL ignorar o conteúdo e seguir só com o dicionário (sem crash), e o arquivo é recriado na próxima gravação.
- WHEN duas instâncias do teclado (processo reiniciado) gravam ao mesmo tempo THEN o diário é de acréscimos e cada linha vale por si; a ordem de leitura resolve.
- WHEN o aparelho muda a data para trás THEN o peso não cresce (o decaimento usa `max(0, agora − ultimoUso)`).
- WHEN a palavra aprendida é ofensiva pela lista do dicionário THEN o aprendido vale só para **não trocar**; ela não é oferecida como completação.
- WHEN o campo é `TYPE_NULL`/terminal (composição desligada) THEN não há troca, logo não há recusa nem aprendizado.
- WHEN a pessoa digita em outro idioma com o teclado pt (inglês no meio) THEN as palavras são aprendidas no pt (o que ela digitou nesse teclado), nunca copiadas para o en.
- WHEN o toque vem de `performClick` de acessibilidade THEN não há coordenada e o corretor usa a tabela de vizinhas.
- WHEN a pessoa guarda o arquivo exportado numa nuvem pelo seletor do sistema THEN o aviso da exportação já disse que ali ele fica fora da proteção do app; o app não sabe nem controla o destino.
- WHEN o arquivo importado traz pesos absurdos, datas futuras, palavras inelegíveis ou milhões de linhas THEN o leitor aplica os limites do LST-07 e o resultado nunca passa dos tetos do aprendizado.

---

## Requirement Traceability

| Requisito | Área | Task | Status | Tipo de verificação |
| --------- | ---- | ---- | ------ | ------------------- |
| BEN-01..05, 08, 09 | Banco de avaliação | CA-T1 | Pending | unit (o próprio banco, nível rápido, + catraca do baseline) |
| BEN-06 | Tempo no aparelho | CA-T3 | Pending | instrumentado (emulador e A32) |
| BEN-07, BEN-02d | Gravador de digitações reais | CA-T20, CA-T2 | Pending | unit (leitor) + instrumentado + manual no A32 |
| BEN-02e | Sessões | CA-T17 | Pending | unit (banco) |
| BAS-01..03 | Base sem aprendizado | CA-T4 (calibração na CA-T21) | Pending | banco (manter, erros: trocas indevidas ≤ 10%, meta provisória) + `SuggesterTest`/`RealDictionaryTest` |
| BAS-04, BAS-06 | Base sem aprendizado | CA-T6 | Pending | banco + unit |
| BAS-05 | Base sem aprendizado | CA-T7 | Pending | banco + unit |
| BAS-07 | Léxico informal | CA-T5 | Pending | banco + `RealDictionaryTest` |
| LRN-01 | Palavras do usuário | CA-T12 | Pending | instrumentado |
| LRN-02, LRN-08 | Palavras do usuário | CA-T8 | Pending | unit (`LearningPolicyTest`) |
| LRN-03..07 | Palavras do usuário | CA-T9, CA-T11 | Pending | unit (`PersonalLexiconTest`, propriedade) + banco (sessões, CA-T17) |
| REJ-01, REJ-04..06 | Correção recusada | CA-T13 | Pending | unit (máquina de recusa) + instrumentado |
| REJ-02, FIX-01 | Correção recusada e feita | CA-T14 | Pending | unit + instrumentado |
| REJ-03, FIX-02, FIX-04, FIX-06 | Correção feita pela pessoa | CA-T25 | Pending | unit (`RewriteTrackerTest`) + instrumentado |
| FIX-03, FIX-05 | Pares | CA-T9, CA-T11 | Pending | unit |
| SPA-01 | Modelo espacial | CA-T20 | Pending | unit (geometria, `ComposingWordTrackerTest`) + instrumentado (rollover, popup, acessibilidade) |
| SPA-02..06 | Modelo espacial | CA-T19, CA-T21, CA-T22 | Pending | banco (sintético, reais) + unit + instrumentado (tempo no A32) |
| ADP-01..04 | Adaptação do toque | CA-T23 | Pending | banco (sintético com viés) + unit |
| ADP-05 | Hit-test adaptado | CA-T24 | Pending | banco + instrumentado (protótipo debug) + manual no A32 |
| CTX-01, CTX-02 | Contexto | CA-T27 | Pending | unit + banco (conjunto de contexto) |
| CTX-03 | Contexto estático | CA-T28 | Pending | banco |
| CTX-04 | Próximas palavras | CA-T29 | Pending | unit + instrumentado + golden |
| PRV-01 | Privacidade | CA-T10 (exceção: CA-T16b) | Pending | unit (`BackupRulesTest` com `domain="file"`, `ArchitectureRulesTest`) |
| PRV-02 | Privacidade | CA-T8 | Pending | unit (`LearningPolicyTest`) |
| PRV-03, PRV-04, PRV-07 | Privacidade | CA-T15 | Pending | unit + Compose + instrumentado |
| PRV-05 | Privacidade | CA-T18b | Pending | `TranslationsTest` + revisão do Yan |
| PRV-06 | Privacidade | CA-T8, CA-T12 (verificação: CA-T18) | Pending | unit + instrumentado |
| LST-01..05 | Tela de palavras | CA-T16 | Pending | Compose instrumentado + goldens + `TranslationsTest` |
| LST-06, LST-07 | Exportar e importar | CA-T16b | Pending | unit (ida e volta, fuzz do leitor) + Compose + goldens |
| ARM-01, PER-02 | Armazenamento | CA-T10 | Pending | unit (arquivo, fuzz) |
| ARM-02, ARM-03 | Armazenamento | CA-T12 | Pending | `StrictModeKeyboardTest` + `ArchitectureRulesTest` |
| PER-01 | Desempenho | CA-T3, CA-T22 | Pending | tempo no A32 |
| ROL-01 | Testes e rollout | CA-T8..T11, CA-T23, CA-T25, CA-T27 (PIT: CA-T30) | Pending | unit + propriedade + fuzz |
| ROL-02 | Testes e rollout | CA-T18 | Pending | instrumentado |
| ROL-03 | Testes e rollout | CA-T12 | Pending | unit + instrumentado |
| ROL-04 | Testes e rollout | CA-T4, CA-T30 | Pending | `RealDictionaryTest`/`SuggesterTest` |

**Status values:** Pending → In Design → In Tasks → Implementing → Verified.
**Coverage:** ver `tasks.md` (cada requisito aponta para uma ou mais tarefas).

---

## Success Criteria

- [ ] **Manter** (`manter-teste` e as palavras reais da CA-T2): as trocas **indevidas** caem da linha de base medida na CA-T1 (preliminar: ~48%, 155 de 322 numa lista montada por agente) para **≤ 10%** (meta provisória, reconfirmada com a medição real); o banco reporta **trocadas** e **indevidas** lado a lado, por grupo; os acertos nos erros reais não caem (catraca).
- [ ] Recusar uma troca uma vez a faz sumir para sempre (instrumentado no IME real).
- [ ] Modelo espacial, nas configurações do protótipo de `context.md` §7, **separadas** (200 digitações por linha: poucos eventos; o número que vale é o do banco, ≥ 2000 por configuração, e o da digitação real): (a) σ 0,40 sem viés: erra menos trocas (4,2% → 1,2%, 7 contra 2 eventos) e **não piora o acerto** (82,1% → 81,0%, 2 casos em 168, dentro do ruído); (b) σ 0,30 com viés forte: a troca certa sobe (87,6% → 94,2%, margem ≥ 3 bits) sem trocas erradas a mais (0,7% → 0,0%); (c) σ 0,30 sem viés: iguais (95,1%); (d) σ 0,40 **com** viés: nunca foi medido, a medir na CA-T19 e na CA-T23. Fase D (o viés da pessoa conhecido, B+ do protótipo): pretendida em 1º 97,8% → 99,3%, a medir com o viés **aprendido** de verdade.
- [ ] Nenhuma palavra é gravada em senha, e-mail, URL, número, campo `NO_SUGGESTIONS`, `NO_PERSONALIZED_LEARNING` ou com a opção desligada; "Apagar meus dados" apaga o aprendido sem rede.
- [ ] A32: p95 por palavra dentro do teto de PER-01 (provisório 25 ms; fixado depois da CA-T3), abertura do teclado sem trabalho novo na main thread.
- [ ] O Yan lê a tela de palavras e entende o que o teclado guardou.

# Q18 — Skin Mascote para a orbe (o Ouvi)

## Contexto

Pedido do Yan (2026-10-02): "uma skin que lembre algum tipo de mascote que pareça mais vivo e orgânico, em vez dos globos abstratos que estamos
usando". Abordagem **nativa** (código Kotlin/Compose procedural, não pacote de SVG), **só a orbe** (aba Sobrepor → Skins); o teclado não entra
agora. Branch `feature/skin-mascote` (worktree `/home/yan/Projects/keyflow-mascote-wt`, a partir da `main` em `15b84a9`). Outro agente trabalha
na branch `feature/skins-importaveis` e também mexe em `VoiceOrb.kt`/`OrbStyle`: o conflito é de quem integra. Paga, como as outras — por ora
liberada (`OrbStyle.MASCOT.paid = true`, mesma regra do Aero/Glitch/Rose Gold: só o Ateliê é livre).

## O personagem (e por quê)

**Ouvi** — de "ouvir" (e do "ouvi!" de quem acaba de entender): um bichinho redondo e gorducho, verde-água como o KeyFlow, de barriga creme, olhos
grandes e brilhantes, bochechas, **orelhas enormes** de dentro terracota, um topete âmbar entre elas e um rabinho de ponta creme.

1. **A orelha é a metáfora da voz virando texto**: o app *ouve*; o Ouvi ouve. As orelhas também são a parte mais expressiva — em pé escutando, caídas
   dormindo, uma de cada lado pensando — e a que melhor mostra *acompanhamento* (follow-through).
2. **A barriga é a placa do texto**: o app escreve o tempo (`m:ss`, quase a largura da orbe) e os glifos no centro; um corpo de barriga lisa e clara
   dá o contraste que o texto precisa (tinta `#2B2720` sobre creme: ≈ 13:1, a conta está no teste) e deixa o rosto na metade de cima.
3. **Original**: criatura inventada (nem coelho, nem gato, nem rato; cores e proporções do Ateliê), sem nada de personagem existente. Silhueta simples
   (corpo + duas orelhas + topete), reconhecível a 110 px — o mínimo do slider.

Paleta, toda do Ateliê: teal `#4E9683`/`#9AD0BF` (corpo, em degradê), creme `#F4ECCF` (filete externo e ponta do rabo) e `#FFF9E6→#EEE0B6`
(barriga), terracota `#D9694B` (dentro das orelhas, língua), âmbar `#C6873F` (rabo e topete), tinta `#2B2720` (olhos, boca, tempo).

## Decisões de arquitetura

- **Função pura de `(t, estado, hasCenter)`**: nada de `Random()`, relógio de parede ou estado entre quadros. Três arquivos novos em
  `ui/orb/`: `MascotTimeline.kt` (o calendário dos eventos raros), `MascotPose.kt` (a pose — tudo que se mexe — em um instante) e `MascotOrb.kt`
  (o desenho). Em `VoiceOrb.kt` só o valor `OrbStyle.MASCOT`, a tinta do centro e o ramo de `drawOrb`.
- **Calendário** (`MascotTimeline`, no estilo de `GlitchTimeline`/`ShimmerTimeline`): piscada, tremida de orelha, olhar de lado e abano de rabo
  vivem em **fatias** de duração própria (3170, 5230, 7310 e 8230 ms em repouso — sem divisor comum). Um hash por bits do índice da fatia
  decide se o evento acontece e onde começa (entre 8% e 63% da fatia), então o intervalo varia, mas é limitado: nunca duas piscadas coladas
  (vão mínimo > 700 ms; numa piscada dupla, 150 ms de olho aberto) e uma piscada nunca vaza da fatia. O ritmo muda por estado.
- **Vivo e orgânico = princípios de animação**, não só "mexer": respiração com *squash & stretch* (o corpo estica e afina com os pés no chão),
  contorno do corpo com ondulação leve (12 pontos ligados por Béziers de Catmull-Rom, cada um com a sua frequência), orelhas/rabo/topete com
  **atraso e acompanhamento** (repetem o balanço do corpo 160 ms depois, e a ponta 330 ms), olhos com brilho que ficam quase parados enquanto o olho
  gira, cabeça que se vira para onde olha (paralaxe do rosto), nenhuma senoide sozinha (períodos sem divisor comum, como o `orbLevel`).
- **Personalidade por estado**: *repouso* respira, pisca, mexe uma orelha de vez em quando, balança devagar e abana o rabo; *escutando* fica atento
  (orelhas em pé e viradas, olhos arregalados, infla com a energia do `orbLevel`, arcos de som nos dois lados); *pausado* fica sonolento (orelhas
  caídas, pálpebra pesada, respiração funda e lenta, "z" subindo); *transcrevendo* pensa (cabeça inclinada, olhos vagando para cima e para os lados,
  orelhas alternando, três pontinhos que se acendem em sequência acima da cabeça).
- **O centro é do app**: duas disposições (`MascotLayout`). Sem texto (`FULL`): orelhas grandes, olhos grandes, boca, barriga pequena. Com tempo ou
  glifo (`CENTER`, `hasCenter`): corpo mais alto, orelhas mais curtas, rosto sobe e se afasta (olhos acima da linha do tempo e dos lados do glifo
  de pausa), barriga vira uma placa larga, boca some. Com centro o corpo balança em torno do **centro da orbe** (o texto é fixo na janela; em
  torno dos pés a cabeça varreria o rosto para cima dele); sem centro, em torno dos pés.
- **Em qualquer fundo**: contorno verde-fundo + filete creme por fora ("adesivo") + sombra no chão. Conferido sobre branco e sobre preto (rota
  `overlay-mascot-claro-escuro`).
- **Pegada**: o corpo ocupa ≈ o mesmo quadrado da orbe (0,65 × 0,59 do lado, contra 0,6 do círculo antigo); as orelhas sobem até 0,47 do centro
  (teste de unidade: a ponta, com o filete, nunca passa da borda da janela, em nenhum estado nem instante).
- **Custo**: tudo é desenhado no espaço da unidade (`1 = lado`) por uma transformação só, então os degradês são constantes (sem recriar shader
  por quadro) e os caminhos são reaproveitados (`MascotScratch`, um por thread); só os `Stroke` e a pose reaproveitada. Parado, sem custo.

## Rodadas visuais (capturas reais no emulador, olhadas uma a uma)

**Rodada 1** — primeiro quadro. A silhueta já lê, a 110 px também, nos dois fundos. Defeitos, ditos com franqueza: (a) com o tempo no centro os
dígitos batem nos olhos; com o glifo de pausa, o glifo bate no olho; o rosto está espremido; (b) bochecha marrom-suja (terracota sobre teal vira
marrom); (c) rabo parece uma faca; (d) orelhas pequenas e pontudas — "coelho/Pokémon genérico"; (e) arcos de som invisíveis no preto, "z" fraco
no preto; (f) o still da transcrevendo caiu no meio de uma piscada.

**Rodada 2** — duas disposições (`FULL`/`CENTER`), corpo mais alto e orelhas mais curtas com centro, olhos mais afastados; orelhas maiores, mais
largas e de ponta redonda; bochecha rosa-coral clara; rabo curto e gordo com curva; arcos e "z" com filete creme por fora e verde por dentro;
instantes de still escolhidos pelo calendário (olho aberto, orelha em repouso). Ainda: o balanço em torno dos pés (3,8° de inclinação) varria a
cabeça 5 px de lado — a cabeça encostava no glifo.

**Rodada 3** — balanço em torno do centro quando há texto; topete âmbar entre as orelhas (só um nubinho: pequeno demais); pálpebra de baixo
inclinada dava ar de bravo no pausado.

**Rodada 4** (emulador com GPU do host, quadros nítidos) — topete maior; pálpebra reta; ampliando 250%: o transcrevendo lê "pensando" (cabeça
inclinada, olhar para cima, orelhas alternadas, pontinhos); o pausado lê "sonolento"; o repouso é o mais bonito; os dígitos cabem na barriga.

**Rodada 5** — a piscada cortava o olho com uma linha reta e, no meio dela, o mascote parecia irritado; agora a piscada **achata** o olho (e só a
pálpebra caída do sonolento corta). Luz refletida do chão embaixo à direita (volume). Folha de quadros a cada 200 ms por estado
(`.specs/quick/018-skin-mascote/`): o balanço, as orelhas, a piscada, o olhar e os pontinhos se leem em sequência.

## Verificação

Emulador `keyflow_api34` (API 34, 1080x2400 a 420 dpi) — **com a GPU do host** (`-gpu host`), ver SPEC_DEVIATION abaixo. Tudo com `adb -s emulator-5554`, dentro
do `flock /tmp/keyflow-emulator.lock`; contagens lidas do logcat (`TestRunner: run finished`), não do resumo do `am instrument`.

- **Unit** (`testDebugUnitTest`, completo): **850/850**, 0 falhas. Novos: `MascotTimelineTest` 11 (determinismo, hash estável e uniforme, piscada dentro da
  fatia, nunca coladas — vão mínimo ≥ 700 ms e 150 ms de olho aberto na dupla —, irregular porém estável, tremida amortecida e de um lado só ou dos dois,
  olhar sem parar pensando e nunca sonolento), `MascotPoseTest` 12 (função pura de `(t, estado)` mesmo com a instância reaproveitada, nunca periódica em
  1..60 s, corpo sem salto entre quadros de 16 ms, volume ~constante na respiração, orelhas em pé/caídas/alternadas, olhos, energia só escutando,
  **ponta da orelha e do rabo dentro da janela em todo estado e instante** nas duas disposições, contraste da tinta ≥ 4,5:1) e +1 em `VoiceOrbTest`
  (`mascot` é skin paga e `orbStyleFor` a acha). `TranslationsTest` 4/4 (com "Mascote").
- **Instrumentado** (`am instrument -e package`): `com.keyflow.ui` **184/184** (inclui `orbSkinMascotPersists` e o artefato de movimento), `auth` 9/9,
  `audio` 10/10, `reasoning` 8/8, `transcription` 12/12, `testing` 5/5, `verify` 3/3. `data` 34/35 na 1ª rodada (`ModelDownloaderDeviceTest`) e **35/35 na
  2ª**; `overlay` 27/30 na 1ª rodada (`PartialOverlayServiceTest` ×3) e 29/30 nas três seguintes (`OrbMotionArtifactTest.generateOrbMotionArtifacts`,
  que falha ao capturar um quadro). Medido antes de atribuir causa: com o **APK base** (sem o mascote), o mesmo pacote deu 29/30 e 27/30
  (`generateOrbMotionArtifacts`, `OverlayServiceCancelTest`, `PartialOverlayServiceTest`), e `PartialOverlayServiceTest` + `ModelDownloaderDeviceTest`
  isoladas passaram 9/9 duas vezes com cada APK — são instabilidade do emulador sob carga (load average 15–19 no host), não do mascote. O pacote `ime` não
  foi rodado (o mascote não toca o teclado; outro agente o rodou no mesmo emulador).
- **Galeria**: 168 PNGs (154 antigos + 14 das 7 rotas novas). **Os 152 goldens antigos que não mostram a seção de skins saem byte a byte iguais** aos
  capturados com o APK base no mesmo emulador (`cmp`); só `config-overlay-skins` e `config-overlay-skins-dark` mudam (o cartão novo no lugar da amostra vazia
  da 5ª coluna) — foram os únicos aprovados, à mão (`git status -s verification/golden/` conferido). `scripts/diff-screens.sh`: **164/164 dentro do limiar**
  (150 antigos + 14 novos). Contra os goldens *versionados* (feitos em outro ambiente) os antigos diferem de 5 a 270 px (≤ 0,01%), bem abaixo do limiar de 0,02%.
- **Desempenho** (`OrbFrameCostArtifactTest`, 195 px, 8 s depois de 2 s de aquecimento, relógio de verdade, 5 rodadas em ordem alternada; mediana das rodadas):

  | caso | UI desenho p50 | RenderThread p50 | quadro p50 (gfxinfo) |
  | ---- | -------------- | ---------------- | -------------------- |
  | Ateliê repouso / gravando | 1,13 / 1,17 ms | 0,89 / 0,86 ms | 25 / 23 ms |
  | Aero repouso / gravando | 1,80 / 1,81 ms | 2,34 / 2,28 ms | 31 / 28 ms |
  | Rose Gold repouso / gravando | 1,42 / 1,16 ms | 1,32 / 1,06 ms | 30 / 25 ms |
  | **Mascote repouso / gravando** | **1,01 / 1,27 ms** | **1,88 / 1,81 ms** | **32 / 25 ms** |

  O mascote fica entre o Rose Gold e o Aero: a thread de UI custa o mesmo que as outras (≈ 1 ms); a RenderThread, ~0,6 ms a mais que o Rose Gold e menos que o Aero
  (muitos caminhos, degradês e um `clipPath` do olho por quadro). Tempo de quadro p50 igual ao dos outros dentro do ruído (mínimos 22–25 ms em todos). Os p95
  oscilam demais de uma rodada para outra (3 a 8 vezes) para comparar — o host dividia CPU e GPU com outros processos. **Ressalvas**: é emulador (a GPU é a do
  host, via gfxstream), não o A32; vale como comparação entre skins no mesmo ambiente, não como número de aparelho. "Visivelmente mais caro": não no
  emulador; **não medi no A32**.

- **Gate final no HEAD** (`c06f3d2`+, só comentário depois do APK da suíte): unit 850/850 de novo; `com.keyflow.ui` 184/184 e `com.keyflow.testing` 5/5 com o APK do HEAD;
  as rotas do mascote, as duas do cartão e `overlay-idle`/`overlay-rosegold-idle` recapturadas com o APK do HEAD saem **byte a byte iguais** às da captura final (a 1ª tela
  depois de instalar às vezes sai escurecida por um scrim do sistema — a captura é repetida, e a repetição bate; foi o que aconteceu com `overlay-mascot-recording`).

### SPEC_DEVIATION / achados do ambiente

1. **Emulador e GPU.** O `qemu-system` do emulador deu SIGSEGV 4 vezes (3 com `swiftshader_indirect`, 1 com `-gpu guest`), sempre na `RenderThread` do renderer
   gráfico; três delas ao abrir a rota `overlay-mascot-transcribing` (a quarta foi a do emulador do outro agente, de causa que não apurei). Cada queda deixou um **core de ~920 MB**
   em `/var/lib/systemd/coredump` (root; 4 deles) — convém limpar com `sudo coredumpctl`/`rm`. Subi o `keyflow_api34` com `-gpu host -no-snapshot-save -no-audio` e
   `ulimit -c 0` (sem mais cores) e ele ficou estável em centenas de aberturas de rota (duas capturas completas, a suíte e as medidas). O emulador em `emulator-5554` agora é esse (deixei ligado). Os goldens novos são desse
   ambiente: se o CI usar renderização por software, pode haver diferença de alguns pixels de antialiasing nas rotas do mascote.
2. **Disco.** No começo `/home` estava a 100% (980 MB livres) e o emulador se recusava a subir; as saídas de build foram redirecionadas para `/var/tmp` com um init script
   fora do repositório (`-I`), sem tocar nos `build.gradle`. (Depois o `/home` voltou a ter 29 GB.)
3. **GIFs.** O brief pedia quadros "da galeria com o relógio avançando"; a `GalleryActivity` congela o relógio de propósito, então usei o mesmo harness do
   `TourMotionArtifactTest` (regra do Compose com relógio injetado) — o método é o do Q6/Q7: `am instrument`, PNGs puxados, GIF com ImageMagick.
4. **Texto da aba Docs.** A frase "a aparência da orbe: Ateliê, Aero, Glitch ou Rose Gold" (`DocsPanel.kt`) não ganhou o Mascote: mudaria o golden `config-docs`, fora
   do escopo "só os goldens do cartão novo". Convém atualizar na integração (a outra frente de skins também a toca).
5. **Fundo claro e escuro.** Em vez de duas rotas, uma só (`overlay-mascot-claro-escuro`) põe cada estado sobre branco e sobre preto lado a lado; todas as rotas já saem
   também no tema escuro do app.

## Para abrir (neste diretório)

- `ouvi-repouso.gif`, `ouvi-escutando.gif`, `ouvi-pausado.gif`, `ouvi-transcrevendo.gif` — 2,8 a 3,2 s cada, 156 px, sobre branco e preto, 20 quadros por segundo
  (175 a 407 KB); `scripts/verify-mascot-gifs.sh` os regenera.
- `ouvi-estados-claro-escuro.png` (cada estado e cada coisa que o app escreve no centro, sobre branco e preto) e `ouvi-tamanhos.png` (110 a 280 px).
- Tela cheia: `verification/golden/overlay-mascot-*.png` e `config-overlay-skins-mascote*.png`.

## Commits

`ebc7a26` calendário · `6fcfb4f` skin Mascote (pose, desenho, `OrbStyle.MASCOT`) · `e4014e6` cartão em Sobrepor (+ 2 goldens) · `eb727f8` rotas e goldens da galeria ·
`efe39ff` GIFs e custo de quadro · `1e52fab` KDoc do pivô · `c06f3d2` custo UI × RenderThread · e o `docs(quick)` deste diretório.

## Em aberto

- **O rosto fica apertado com texto no centro**: com `m:ss` os olhos ficam a ~3 px dos dígitos (156 px) e, no pausado, o glifo de pausa cai entre os olhos, sobre o verde da
  testa, não sobre o creme (a tinta ainda lê: ≈ 7:1 sobre o verde claro). Se o Yan achar o rosto pequeno nesses estados: barriga em "babador" (estreita e alta no meio, até a
  testa) ou uma orbe maior só com texto.
- **Os glifos do app não são do mascote**: a lixeira (âmbar) e o X (terracota) mantêm as cores de sempre (≈ 2,7–3:1 sobre o creme, como no Ateliê); a lixeira cai em parte
  sobre o verde.
- O topete é discreto (âmbar e creme, meio escondido pelas orelhas); o "z" e os arcos de som são finos a 110 px.
- Goldens do ambiente do host (GPU do host): o CI por software pode divergir uns pixels nas rotas do mascote. Não medi no A32 nem vi o mascote se mexendo no aparelho.
- A frase da aba Docs não cita o Mascote (ver SPEC_DEVIATION 4).
- Se o Yan não gostar do personagem: (1) trocar a espécie mantendo o motor — calendário, pose e disposições servem a qualquer criatura de corpo redondo e orelhas, só os
  `Path` e as cores mudam; (2) sem o filete creme (mais "limpo", mas perde a leitura no preto); (3) uma versão sem pés nem rabo, mais próxima do globo.

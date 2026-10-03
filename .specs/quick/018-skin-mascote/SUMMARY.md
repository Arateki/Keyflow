# Q18 — Skin Mascote ("Ouvi") — Resumo

**Pedido do Yan (2026-10-02):** "uma skin que lembre algum tipo de mascote, mais vivo e orgânico que os globos abstratos", na abordagem **nativa**
(código, como Aero/Glitch/Rose Gold). Só a orbe; o teclado ficou fora. Detalhes, rodadas visuais e medidas em [TASK.md](TASK.md).

## O que entrou

- `OrbStyle.MASCOT` (`id = "mascot"`, paga como as outras e liberada por ora) e o **Ouvi**: bichinho redondo verde-água, barriga creme, olhos grandes,
  orelhas enormes, topete âmbar e rabinho. Respira, pisca de forma irregular (calendário determinístico), mexe as orelhas com atraso e muda de ânimo por
  estado: **repouso** respira e pisca, **escutando** fica atento (orelhas em pé, pulso rítmico), **pausado** fica sonolento, **transcrevendo** pensa
  (cabeça inclinada, olhar para cima, orelhas alternadas, pontinhos).
- O centro da orbe é do app (tempo, pausa, lixeira, X, glifos do arrasto): o rosto mora na metade de cima e a barriga é uma placa lisa para o texto
  (contraste ≥ 4,5:1, com teste). `hasCenter` ajusta a pose.
- Cartão "Mascote" na seção Skins da aba Sobrepor; 7 rotas na galeria (`overlay-mascot-*`) e seus goldens; GIFs por estado neste diretório.
- Código novo em `ui/orb/Mascot{Orb,Pose,Timeline}.kt`; em `VoiceOrb.kt` só o valor do enum, a tinta do centro e o ramo de `drawOrb`.

## Verificação (resumo; números completos no TASK.md)

- Unit: 851/851 no HEAD da investigação do swiftshader. Instrumentado `com.keyflow.ui` 184/184 (duas instabilidades de emulador sem relação com a orbe, documentadas).
- Goldens: as 148 rotas que não são do mascote nem do cartão novo ficam **iguais aos goldens versionados** no emulador em swiftshader (144/148 byte a byte, 0 acima do
  limiar); só mudam os dois do seletor de skins, por causa do cartão novo.
- Desempenho (emulador): tempo de quadro igual ao das outras orbes dentro do ruído; a RenderThread custa entre o Rose Gold e o Aero. **Não medido no A32.**

## A queda do emulador do CI (achado importante)

O CI roda o emulador em `-gpu swiftshader_indirect`; nele o `qemu` dava SIGSEGV ao abrir `overlay-mascot-transcribing` (5 quedas em 6 aberturas). Bisseção: a causa era
o **preenchimento com degradê da folha do rabo quando a ponta dobrava para o lado positivo**. O rabo agora dobra sempre para cima (`tailBendOf`, com teste); depois
disso, 260 aberturas em modo CI e **0 quedas** nas rotas do mascote. O Aero em repouso também cai às vezes nesse emulador (1/20) — instabilidade antiga, independente.
`scripts/stress-routes.sh` reproduz a medição (rodízio de rotas; só no emulador). Goldens do mascote refeitos no swiftshader, que é de onde vêm os versionados.

## Em aberto

- O rosto fica apertado quando há texto no centro (olhos a ~3 px dos dígitos a 156 px); no pausado o glifo de pausa cai entre os olhos.
- O texto da aba Docs ("a aparência da orbe: Ateliê, Aero, Glitch ou Rose Gold") ainda não cita o Mascote — atualizar na integração (a feature de skins importáveis também o toca).
- Não visto no A32 (**[YAN]**): fluidez, legibilidade ao sol e sobre os apps que ele usa.
- Se o Yan não gostar do personagem: trocar a espécie mantendo o motor (calendário, pose e disposições servem a qualquer criatura de corpo redondo; só os `Path` e
  as cores mudam), ou uma barriga em "babador" para dar mais espaço ao texto.

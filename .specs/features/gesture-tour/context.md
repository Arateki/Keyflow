# Gesture Tour — Context (brainstorm 2026-07-01)

Decisões do brainstorm com o Yan sobre o **tour de gestos animado** (documentação viva dentro do
app). Todas as decisões centrais foram respondidas pelo Yan.

## T1 — Problema e recorte (Yan)
A aba **Modo** tem hoje demos estáticas nos cards (`ModeCard.demo` + `DemoFrame`/"PRÉVIA"):
a orbe parada em Listening e o teclado com a **grade antiga** (pré-Canvas) — desatualizado.
Substituir por **demonstrações animadas**: um "dedo" interagindo com a orbe/teclado, mostrando
**todas as interações atualmente configuradas**, uma após a outra em ordem pedagógica; a
descrição da ação corrente aparece no espaço de texto à direita do card (mudando em sincronia);
a barra "PRÉVIA" ganha **setinhas ‹ ›** para avançar/voltar e achar um gesto específico.
Conteúdo inicial: **orbe** = gestos básicos do modo de transcrição + atalhos; **teclado** =
retração/expansão + interações com o mic.

## T2 — Dinâmico pelas Settings ✅ (Yan)
Os roteiros são **gerados das configurações vivas**: modo TOGGLE mostra toque-gravar/segurar-
pausar; PTT mostra segurar-falar-soltar-toque-rápido-pausar; os atalhos de rajada (1/2/3 toques)
mostram exatamente as ações que o usuário mapeou (e somem quando NONE; o de 1 toque só existe no
PTT, como na config real). Mudou a configuração → o tour muda junto.
Rejeitado: conjunto fixo (mostraria gestos que não valem p/ o usuário).

## T3 — Tecnologia: Compose puro, roteiros declarativos ✅ (Yan)
- **Dedo** = vetor desenhado (forma de toque com halo; ripple no tap; trilha no arrasto),
  animado por **keyframes declarativos** dirigidos por **clock injetável** (mesmo padrão
  `LocalOrbClock` → goldens determinísticos, congelando o tempo).
- **Cenas reais**: orbe demo = `VoiceOrb` real; teclado demo = grade **Canvas nova** real em
  miniatura (o mock deixa de envelhecer: reskins/mudanças de layout aparecem de graça).
- Zero dependência nova, zero asset externo.
Rejeitados: Lottie (dependência + assets que dessincronizam do visual real a cada reskin; não
reflete config dinâmica); GIFs/vídeos (pesados, não-temáticos claro/escuro, regravar a cada
mudança — era o placeholder original, superado).

## T4 — Reprodução: autoplay em loop + setas ✅ (Yan)
Roda sozinha, gesto após gesto em ordem pedagógica (básicos → pausa → cancelar/desfazer →
atalhos), em **loop**; as setas **‹ ›** na barra PRÉVIA pulam/voltam (com o título do gesto
atual); interagir (seta) **pausa o autoplay por alguns segundos** antes de retomar. Descrição à
direita sincronizada com o passo corrente.
Rejeitados: só manual (aba menos viva); uma passada e para (meio-termo desnecessário).

## T5 — Reutilizável por design ✅ (pergunta explícita do Yan: "isolado que eu possa reaproveitar")
O player é um Composable **isolado** que toca qualquer lista de roteiros (`GestureTourPlayer` +
`rememberGestureTourState`): a aba Modo é só o primeiro consumidor. Onboarding, aba Docs e
tooltips futuros consomem o mesmo componente com subconjuntos de roteiros (ex.: mostrar SÓ o
gesto de cancelar num contexto específico). Roteiro de gesto = **dado** (serializável), então
features futuras (Visão, parcial) adicionam seus roteiros sem tocar no player.

## T6 — Documentação da aba Docs (decorrência de "atualizar a documentação")
Os textos estáticos da aba Docs (seções Modos/Pausa, T15 do redesign) ficaram defasados após a
fase 2 de gestos (cancelar/desfazer, atalhos de rajada, PTT novo) — atualizar o conteúdo junto.
Embutir mini-tours na Docs = P3 (o player já permite; não é MVP).

## T7 — Docs: índice, detalhes e feedback (incremento do Yan, 2026-07-02)
A aba Docs ganha, na mesma feature (melhorias de documentação relacionadas ao tour):
- **Índice logo após "Como funciona"**: lista de todas as seções; tocar rola até a seção. Gerado
  da mesma lista que renderiza a aba (fonte única — seção nova entra no índice sozinha).
- **Mais detalhes de funcionamento**: seções novas cobrindo gestos da orbe, teclado KeyFlow
  (mic/hold, acentos, globo, numérico, corretor), notificação de gravação e erros/timeout.
- **Feedback**: "Reportar bug" e "Dar sugestão". **Canal escolhido: e-mail (`mailto:`)** —
  destinatário numa constante única (`FEEDBACK_EMAIL`, e-mail do Yan por ora), assunto por tipo,
  corpo com diagnóstico (versão do app/Android/modelo). Zero backend; trocar de canal depois não
  refaz a UI. Rejeitados por ora: GitHub Issues (exige conta/repo público), Play Store (app ainda
  não publicado), Google Forms (manutenção à parte).

## T8 — Apresentação: o porquê de cada permissão (incremento do Yan, 2026-07-02)
Cada linha do checklist de permissões do onboarding ganha um **"?"**: tocar expande **inline**
a explicação de por que a permissão é pedida e o que deixa de funcionar sem ela (uma aberta por
vez; tocar de novo fecha). Textos honestos e consistentes com a seção Privacidade da Docs.
Rejeitado: dialog/bottom sheet (peso desnecessário pra um parágrafo; inline mantém o fluxo).

## Fora do MVP (registrado)
- Roteiros das features futuras (modo Visão, transcrição parcial) — entram quando elas chegarem
  (o formato de dados já comporta).
- Mini-tours embutidos na aba Docs/onboarding (P3 — o componente nasce pronto p/ isso).
- Narração/haptics no tour; velocidade configurável.

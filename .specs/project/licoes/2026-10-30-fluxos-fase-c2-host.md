# 2026-10-30 — Fluxos: host no serviço (equipe/fluxos-fase-c2, FLX-T14)

O PR #83 ficou 7/7 verde no commit `4dae5c0a`, run 41052105478, antes de criar a
branch C2. O teste inicial do intent ficou vermelho: nenhuma das três etapas
executou (`/tmp/pool-instrumentar.no0rf433.log`).

## Ciclo de vida e integração

`FluxoHost` é dono do executor e reserva `ativo` antes de rodar. O serviço
observa os fluxos e as Settings antes de resolver um pedido pendente; o botão
transporta só seu id, e Rodar agora transporta o id do fluxo sem gravar gatilho.
`runFlow` do IME está ligado ao intent. Pedidos duplicados avisam sem cancelar.

A transição do Ditar para o repouso chama `maybeStopWhenIdle`: no modo só-teclado
isso encerrava o serviço antes de Perguntar. A guarda de `ativo` impede isso.
Mutação: removida só essa guarda, o teste encontrou `instanceForVisionTest ==
null` entre etapas (`qahnnarg`); a fonte foi restaurada em `finally`.

A orbe desenha o glifo sobre seu estado de processamento sem alterar a máquina
de gravação. Ditar mantém o toque de enviar; fora dele o toque cancela. Destruir
o serviço cancela a execução e suas portas. A falha traz a etapa e a aba; o
banner mantém o serviço enquanto existe e usa Toast se não puder abrir janela.

Nove instrumentados passaram após reinstalar a fonte restaurada (`3msov6_w`): ordem do intent, concorrência/manual,
FGS só-teclado, Ditar/Capturar em RECORDING/PAUSED reais, banner/aba Raciocínio,
toque/glifo, ACTION_RECORD, destruição e configuração atualizada ao vivo. Portas falsas, exceto o áudio aberto
para testar as recusas; a limpeza cancela o áudio e restaura Settings/barras.
As barras passaram 6/6 (`9kcnfd9n`), incluindo toque no `RunFlow` do teclado real
até o serviço: duas etapas falsas executadas. Assim se verifica também o elo do
`KeyFlowKeyboardService.runFlow`, e não só o intent enviado diretamente ao host.
JVM 2663/2663, lint e os dois APKs verdes.

## SPEC_DEVIATION — permissão herdada do FGS (FLX-39)

O serviço atual declara e inicia o tipo `microphone`, inclusive antes da
captura. Esse tipo exige `RECORD_AUDIO` segundo a [documentação oficial do
Android](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
Logo, também o fluxo que começa com Capturar precisa dessa permissão nesta
integração. A entrada pública recusa com aviso antes de `startForegroundService`
se ela falta, evitando iniciar um FGS que não pode promover. Não se acrescentou
outro tipo de FGS para contornar sua finalidade. Capturar sem microfone concedido
fica como limitação explícita até rever esse ciclo de vida; spec e task anotam.

## Cuidado na confirmação de uma mutação

No servidor, `pool instrumentar --sem-build` **não instala** o APK novo: usa o
que já está no emulador. Depois de compilar a fonte restaurada, essa chamada
repetiu a falha do mutante e o protocolo de FGS caiu no teste seguinte
(`gpg_qyf2`). A confirmação deve usar `pool instrumentar` com instalação.
Não trate o resultado do APK mutante como falha da fonte restaurada.

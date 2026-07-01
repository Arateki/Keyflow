# Orbe — máquina de estados × interações (Toggle e PTT)

> Documentação de projeto (não vai no app). Mapeia como cada gesto da orbe se traduz em transição/efeito,
> por modo de gravação. Fonte da verdade no código: `ButtonStateMachine` (estado), `RecordingGestureController`
> (gesto→intenção, por modo) e `OrbButtonView` (toque cru, trava de arrasto, rajada de toques).

## Conceitos comuns aos dois modos

- **Estado único**: existe **uma** `ButtonStateMachine` (no `OverlayCoordinator`). Orbe, teclado e notificação
  refletem o mesmo estado. Os modos diferem só em **como o gesto vira intenção**.
- **Estados**: `IDLE` (pronta/sem-campo) · `RECORDING` · `PAUSED` · `CANCELLING` (janela de desfazer, 3s) ·
  `SENDING` (transcrevendo) · `ERROR` · `PENDING_PASTE`. As tabelas focam os 4 interativos.
- **"Tempo de reação"** (slider): janela da rajada de toques (atalhos) e atraso de confirmação. **Hold** =
  Tempo de reação + 150ms. **Janela de release** (PTT) = Tempo de reação.
- **Atalhos** (2/3 toques sempre; 1 toque só no PTT): a rajada distingue o nº de toques e **não** altera a
  gravação. Valem em **qualquer estado**.
- **Modo-arrasto travado** (setas ↑/lateral/↓ numa janela separada): ao travar, arrastar dá feedback de
  proximidade (ramp tátil) e **click** ao cruzar o limiar; a ação resolve **ao soltar** na zona. Soltar fora
  de qualquer zona = sem ação direcional.

---

## TOGGLE (alternar)

Gravar/enviar é por **toque**. Para agir por direção, **segura ~(Tempo de reação + 150ms)** para travar a
orbe (vibra) e então arrasta; soltar fora de zona não faz nada. Arrastar **de imediato** (sem travar)
reposiciona a orbe.

| Estado \ gesto | 1 toque | 2/3 toques | Travar → ↑ | Travar → lateral | Travar → ↓ | Arrastar imediato |
|---|---|---|---|---|---|---|
| **IDLE** | inicia → `RECORDING` | atalho | — | — | — | reposiciona |
| **RECORDING** | envia → `SENDING` | atalho | pausa → `PAUSED` | cancela → `CANCELLING` | abre o app (segue `RECORDING`) | reposiciona |
| **PAUSED** | envia → `SENDING` | atalho | retoma → `RECORDING` | cancela → `CANCELLING` | abre o app (segue `PAUSED`) | reposiciona |
| **CANCELLING** | descarta já → `IDLE` | atalho | — | — | — | — |

Notas:
- `CANCELLING`: qualquer toque na orbe **conclui o descarte na hora** (sem esperar os 3s); a janela expirando
  sem ação também descarta → `IDLE`. Desfazer é pelo banner/notificação → volta a `PAUSED`.
- Soltar o hold (travado) vibra, mesmo quando não há zona (quase sempre há mudança de estado).

---

## PTT (push-to-talk)

Gravar é por **segurar**; o **toque é livre** (vira atalho). Durante a segurada a orbe já está travada
(setas). Ao **soltar**, abre a **janela de release** (= Tempo de reação): a gravação **segue**; **re-segurar**
continua sem costura; **toques** são atalhos; **estourar** a janela **envia**.

| Estado \ gesto | Segurar | ↑ (solta) | Lateral (solta) | ↓ (solta) | Soltar parado | 1 toque | 2/3 toques |
|---|---|---|---|---|---|---|---|
| **IDLE** | inicia → `RECORDING` | — | — | — | — | atalho (1) | atalho |
| **RECORDING** (segurando) | (gravando) | pausa → `PAUSED` | cancela → `CANCELLING` | abre o app + janela¹ | abre janela de release² | —³ | —³ |
| **PAUSED** | retoma → `RECORDING` | — | — | — | — | atalho (1) | atalho |
| **CANCELLING** | — | — | — | — | — | descarta já → `IDLE` | atalho |

Na **janela de release** (gravando, dedo solto):

| Gesto na janela | Efeito |
|---|---|
| **Re-segurar** (antes do fim) | continua `RECORDING` sem costura (re-trava p/ o slide) |
| **1 toque** | atalho de 1 toque (gravação segue, janela reabre) |
| **2/3 toques** | atalho 2/3 (gravação segue, janela reabre) |
| **Estourar** (sem tocar) | envia → `SENDING` |

Notas:
- ¹ **↓ no PTT**: abre o app **e** mantém na janela de release — se re-segurar antes do fim, continua
  gravando; se estourar, envia. (No Toggle o ↓ só abre, mantendo o estado.)
- ² **Soltar parado**: não envia na hora — abre a janela; o envio ocorre só ao estourar (ou re-segurar
  continua).
- ³ Durante a **segurada ativa** o dedo está ocupado, então não há toque/atalho; atalhos no PTT acontecem na
  janela (ou em idle/pausa).
- **Pausar no PTT é só por arrasto ↑** — re-segurar/tocar **nunca** pausa.
- Start **não** é otimista (abrir o mic no toque acenderia o indicador verde do sistema a cada atalho).

---

## Diferenças-chave Toggle × PTT

| | Toggle | PTT |
|---|---|---|
| Iniciar gravação | 1 toque | segurar |
| Enviar | 1 toque | soltar parado (janela estoura) |
| 1 toque | gesto nativo (gravar/enviar) | atalho (toque livre) |
| Travar p/ arrastar | segurar +150ms (explícito) | já travado enquanto grava |
| Reposicionar | arrastar imediato (qualquer estado) | só em idle (gravando não reposiciona) |
| Pausar | travar → ↑ | arrastar ↑ |

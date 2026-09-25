# Native Voice Models — Specification

> **STT on-device.** Nova origem de transcrição **Local**: modelos de voz rodando no aparelho via
> **sherpa-onnx**, com um **gerenciador de modelos** (baixar/selecionar/deletar) na aba Voz.
> Decisões do grill em `context.md` (G1–G11). App será publicado na loja no futuro — requisitos de
> prontidão de loja inclusos.

## Problem Statement

Hoje o KeyFlow só transcreve via endpoints remotos (OpenAI/Groq/Custom): exige chave/servidor,
conexão, e envia o áudio para fora do aparelho. Para o app ir à loja e servir usuários sem
chave/API — e para ditar offline com privacidade total — precisamos de modelos **nativos**
rodando no dispositivo, com o usuário escolhendo o modelo (tamanho × qualidade × velocidade) num
gerenciador simples: baixar, selecionar, deletar.

## Goals

- [ ] Origem **Local** funcional de ponta a ponta: gravar → transcrever no aparelho → colar, **offline** (modo avião).
- [ ] Gerenciador de modelos na aba Voz: catálogo curado (3 modelos), download com progresso/cancelamento/retomada, seleção e deleção.
- [ ] Inferência **isolada em processo próprio** (`:inference`): OOM de modelo nunca derruba teclado/overlay.
- [ ] Modelo **pré-carregado e residente** (resposta imediata em toda ditação; reload transparente se o SO matar o processo).
- [ ] Privacidade: com origem Local, **zero tráfego de rede** no fluxo de transcrição.
- [ ] Prontidão de loja: modelos fora do auto-backup, `.so` alinhados a 16 KB, licenças atribuídas.
- [ ] **Transcrição parcial ao pausar** (G12): segmentos transcritos a cada pausa, buffer visível/editável junto à orbe, entrega só no envio.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Transcrição ao vivo / streaming | PT não tem modelo streaming bom; muda UX de inserção. Interface do engine nasce assíncrona p/ habilitar depois (G2) |
| Catálogo remoto (atualizável sem release) | G4: embutido; estrutura serializável já preparada p/ migrar |
| Espelho próprio dos pesos | G5: upstream HF (csukuangfj); risco aceito |
| Origem "Sistema" (SpeechRecognizer / ML Kit GenAI) | Engine do OEM/AICore, sem gerenciador; candidata futura (context.md) |
| Fallback automático p/ nuvem em falha local | G8.7: nunca enviar áudio à rede sem ação explícita |
| VAD, diarização, pontuação pós-processada | Capacidades do sherpa-onnx p/ fases futuras |
| Play AI packs como entrega | Só via Play + atrelado ao release; revisitar pós-loja |
| Créditos/monetização de modelos locais | Modelos locais são grátis; billing é Milestone 3 |

---

## User Stories & Acceptance Criteria

### P1-A: Transcrever offline com modelo local (`NAT`, `PRV`) ⭐ MVP

**Como** usuário, **quero** ditar e receber o texto sem internet e sem chave de API, **para** usar
o KeyFlow em qualquer lugar com privacidade total.

1. **NAT-01**: WHEN a origem é Local, há modelo baixado selecionado e o usuário conclui uma gravação THEN o sistema SHALL transcrever no aparelho (sherpa-onnx `OfflineRecognizer`) e entregar o texto pelo **mesmo fluxo atual** (inserção via IME / pendência sem campo / colar), sem nenhuma chamada de rede.
2. **NAT-02**: WHEN a transcrição local inicia THEN o `.m4a` gravado SHALL ser decodificado para **PCM mono float 16 kHz** (`MediaExtractor`/`MediaCodec`, com resample defensivo). A gravação passa a **16 kHz na origem** (`AudioRecorder.AUDIO_SAMPLE_RATE`) — ASR opera em 16 kHz; elimina resample no caminho comum e reduz o arquivo (beneficia também o upload das origens remotas).
3. **NAT-03**: WHEN a origem é Local sem modelo selecionado/baixado THEN a tentativa de transcrever SHALL resultar no erro de configuração existente (`CONFIG_MISSING`), como as demais origens incompletas.
4. **NAT-04**: WHEN a inferência local falha (arquivo corrompido, OOM, exceção do runtime, processo morto) THEN o sistema SHALL reportar erro pelo caminho de erro atual e **NUNCA** enviar o áudio à rede como fallback (G8.7).
5. **NAT-05**: WHEN um modo (overlay/teclado) ativa com origem Local e modelo selecionado THEN o sistema SHALL **pré-carregar** o modelo (bind + preload) e mantê-lo **residente** (G10); WHEN o SO matar o processo `:inference` THEN o próximo uso SHALL refazer bind + load de forma transparente (custo: latência única).
6. **NAT-06**: WHEN o modelo aceita hint de idioma (Whisper) THEN o idioma configurado SHALL ser aplicado; WHEN não aceita (Parakeet, auto-detecta) THEN o hint SHALL ser ignorado sem erro.
7. **NAT-07**: WHEN a RAM disponível for insuficiente para o modelo THEN o load SHALL falhar com **erro amigável** (guarda de memória + captura de OOM), sem crash do processo principal.
8. **NAT-08**: a inferência SHALL rodar num **processo `:inference` separado** (bound service, `exported=false`, IPC assíncrono); a morte desse processo não derruba overlay/IME (G9). O processo `:inference` **não lê** DataStore/Settings — todo insumo chega por IPC.
9. **NAT-09**: WHEN o usuário troca o modelo selecionado THEN a próxima transcrição SHALL usar o novo modelo (reload; a transcrição em andamento termina com o anterior).
10. **PRV-01**: WHEN a origem é Local THEN o fluxo de transcrição SHALL executar **zero requests de rede** — verificável em teste (o `WhisperApi`/`TranscriptionClient` HTTP nunca é invocado) e manualmente (modo avião).

**Independent Test**: modo avião no device, origem Local + modelo baixado → ditar "função que soma dois inteiros" → texto inserido no campo focado.

---

### P1-B: Baixar modelos do catálogo (`CAT`, `DLM`) ⭐ MVP

**Como** usuário, **quero** baixar um modelo de voz escolhendo tamanho × qualidade, **para** ter STT local sem inflar o app da loja.

1. **CAT-01**: o catálogo SHALL ser **embutido e serializável** (G4); cada entrada: `id` estável, nome amigável, nota qualidade×velocidade, idiomas, tipo de engine (whisper/transducer), `acceptsLanguageHint`, RAM recomendada, e lista de arquivos (URL upstream HF + **SHA256** + bytes) com tamanho total.
2. **CAT-02**: o catálogo MVP SHALL conter 3 modelos curados (G3/G5): **Whisper base int8** ("Rápido"), **Whisper small int8** ("Equilibrado"), **Parakeet TDT 0.6B v3 int8** ("Máxima qualidade", ~640 MB, PT nativo). URLs, SHAs e bytes = valores **reais conferidos** nos repos upstream (csukuangfj) durante a implementação — nunca fabricados.
3. **CAT-03**: WHEN a RAM total do device < RAM recomendada do modelo THEN o item SHALL exibir aviso de peso ("pode ser pesado neste aparelho") — **aviso, não bloqueio** (G8.6/G11).
4. **DLM-01**: o download SHALL usar o **`DownloadManager`** do sistema (G6), arquivo a arquivo, com notificação de progresso do sistema e **retomada automática** (sobrevive à morte do app e a reboot).
5. **DLM-02**: WHEN todos os arquivos de um modelo concluem THEN o SHA256 de cada um SHALL ser verificado; mismatch → descarte + erro "download corrompido, tente novamente".
6. **DLM-03**: modelos instalados SHALL viver em armazenamento **interno** (`filesDir/models/<id>/`); WHEN deletado THEN o diretório inteiro some e o espaço é liberado imediatamente.
7. **DLM-04**: WHEN a rede ativa é **metered** THEN o início do download SHALL exigir confirmação exibindo o tamanho ("Baixar 640 MB por dados móveis?"); em Wi-Fi inicia direto (G8.1).
8. **DLM-05**: WHEN o espaço livre < tamanho do modelo × 1,2 THEN o download SHALL ser bloqueado com mensagem clara (G8.3).
9. **DLM-06**: SHALL haver no máximo **1 download de modelo por vez** (G8.2).
10. **DLM-07**: WHEN o app reabre com download em andamento/concluído em background THEN a UI SHALL **reconciliar** o estado real (progresso, concluído→verificação→instalado).
11. **DLM-08**: um download em andamento SHALL poder ser **cancelado**, limpando arquivos parciais.

**Independent Test**: tocar "Baixar" no modelo Rápido em Wi-Fi → progresso na UI e notificação; fechar o app no meio; reabrir → download seguiu/estado correto; concluir → item vira "baixado".

---

### P1-C: Selecionar e deletar modelos (`MGR`) ⭐ MVP

**Como** usuário, **quero** escolher qual modelo uso e liberar espaço quando quiser.

1. **MGR-01**: "Local" SHALL ser a 5ª origem no `Segmented` da aba Voz (novo valor no enum `TranscriptionSource`); selecionada, o painel abaixo SHALL ser o **gerenciador de modelos** (G7).
2. **MGR-02**: a lista SHALL mostrar cada modelo do catálogo com nome, tamanho, idiomas e nota, no estado correto: **disponível** (Baixar) / **baixando** (% + Cancelar) / **baixado** (selecionável + Deletar) / **selecionado** (marcado).
3. **MGR-03**: a seleção SHALL persistir (`Settings.localModelId`); WHEN um modelo termina de baixar e nenhum está selecionado THEN ele SHALL ser **auto-selecionado** (G8.4).
4. **MGR-04**: WHEN o usuário deleta um modelo THEN SHALL haver confirmação; deletar o **selecionado** deixa a origem Local sem modelo (→ NAT-03) (G8.5).
5. **MGR-05**: o painel SHALL ter dropdown de **Idioma** (reuso do componente atual; default "Automático"), persistindo `Settings.localLanguage` (consumido conforme NAT-06).
6. **MGR-06**: o painel SHALL exibir nota de privacidade: processamento 100% no aparelho, áudio nunca enviado.
7. **MGR-07**: os estados-chave do painel SHALL ter rotas de galeria + **goldens** claro/escuro (padrão do projeto).

**Independent Test**: com 2 modelos baixados, selecionar o segundo → ditar usa o segundo; deletar o selecionado → aviso; ditar → erro de config claro.

---

### P2-E: Transcrição parcial ao pausar (`PAR`) — G12

**Como** usuário, **quero** que o que já ditei vá sendo transcrito a cada pausa, para ver/editar
o texto no meio da ditação e ter o envio final quase instantâneo.

1. **PAR-01**: a aba Voz SHALL ganhar dois toggles persistidos: **"Transcrever parcialmente ao
   pausar"** (`Settings.partialOnPause`) e **"Visualizar e editar transcrição parcial"**
   (`Settings.partialViewEdit`) — o segundo habilitado apenas com o primeiro ligado. Ambos
   agnósticos à origem (local ou remota).
2. **PAR-02 (gravação segmentada)**: WHEN `partialOnPause` está ligado THEN pausar SHALL
   finalizar o arquivo do segmento atual e retomar SHALL iniciar um novo arquivo; a duração
   ativa acumulada (REC-06) e os gestos/estados existentes não mudam.
3. **PAR-03**: WHEN a gravação pausa THEN o segmento recém-finalizado SHALL ser transcrito pela
   origem ativa e o texto SHALL ser **acrescentado ao buffer em memória** da sessão — **sem
   tocar** clipboard/inserção.
4. **PAR-04**: WHEN há conteúdo parcial e a orbe está pausada THEN um **indicador sutil próximo
   à orbe** SHALL sinalizá-lo; o indicador some quando a sessão termina (envio/cancelamento).
5. **PAR-05**: WHEN `partialViewEdit` está ligado THEN tocar no indicador SHALL **exibir** o
   texto acumulado; tocar novamente SHALL abrir um **input editável** (janela focável própria):
   a orbe permanece visível e operável (retomar a gravação dali funciona) e o teclado —
   inclusive o KeyFlow — pode ser acionado para editar. WHEN a gravação retoma e pausa de novo
   THEN o novo texto SHALL aparecer **em conjunto** (append) na visualização/input abertos.
6. **PAR-06**: as **edições do usuário são a verdade**: appends de novos segmentos nunca
   sobrescrevem texto editado; sair da edição a qualquer momento SHALL voltar ao indicador sutil
   com o conteúdo preservado enquanto pausado (PAR-04).
7. **PAR-07 (envio)**: WHEN o usuário envia (stop) THEN o último segmento SHALL ser transcrito,
   acrescentado ao buffer (com as edições) e o texto completo SHALL seguir o **fluxo de entrega
   atual** (inserção via IME / pendência / colar); o buffer e o input efêmero SHALL ser
   destruídos.
8. **PAR-08 (cancelar/desfazer)**: WHEN a gravação é cancelada THEN o buffer SHALL ser
   descartado junto; WHEN o cancelamento é desfeito (undo atual) THEN o buffer SHALL ser
   restaurado com ela.
9. **PAR-09 (falha parcial)**: WHEN a transcrição de um segmento falha THEN o áudio do segmento
   NÃO se perde: aviso sutil e **re-tentativa no envio** (o envio transcreve os segmentos
   pendentes antes de entregar); falha no envio segue o tratamento de erro atual.

**Independent Test**: ligar os 2 toggles → ditar → pausar → indicador aparece → tocar 2× → editar
o texto → retomar ditando → pausar → texto novo aparece junto do editado → enviar → campo recebe
o texto completo com a edição preservada; buffer some.

---

### P2-D: Prontidão de loja (`STO`)

**Como** dev, **quero** o app aprovável e saudável na Play Store com código nativo e modelos grandes.

1. **STO-01**: o diretório de modelos SHALL ser **excluído do auto-backup** (`dataExtractionRules` API 31+ e `fullBackupContent` legado) — backups têm cota (~25 MB) e modelos são re-baixáveis.
2. **STO-02**: os `.so` do AAR sherpa-onnx SHALL ser verificados para **alinhamento de página de 16 KB** (requisito Play p/ código nativo); ABIs entregues: `arm64-v8a` + `armeabi-v7a` (x86_64 apenas p/ emulador em debug). Falha → atualizar a versão do AAR.
3. **STO-03**: as **licenças** SHALL ser atribuídas no app (sherpa-onnx Apache-2.0 + licença de cada modelo do catálogo), junto ao mecanismo de licenças existente (`app/licenses/` + aba Docs).

**Independent Test**: `zipalign`/script de verificação ELF nos `.so` do APK release passa; inspecionar regras de backup no manifest; seção de licenças cita sherpa-onnx e os modelos.

---

## Edge Cases

- WHEN a rede cai no meio do download THEN o `DownloadManager` SHALL retomar sozinho quando voltar (DLM-01); a UI reflete "aguardando rede".
- WHEN o upstream alterou um arquivo (SHA mismatch) THEN descarte + erro claro (DLM-02); correção de catálogo só via release (risco aceito, G5).
- WHEN a gravação é longa (minutos) THEN a inferência demora proporcionalmente; o estado atual `SENDING`/Transcribing já cobre a espera — sem timeout local agressivo (o IPC usa timeout generoso e cancelável).
- WHEN a gravação é curta demais THEN o `minDurationMs` atual já descarta antes de chegar ao engine.
- WHEN o storage enche durante o download THEN o `DownloadManager` falha o arquivo → erro amigável e limpeza de parciais.
- WHEN o usuário troca de modelo durante uma transcrição THEN a atual conclui com o modelo antigo; o novo vale a partir da próxima (NAT-09).
- WHEN o processo `:inference` morre no meio de uma transcrição THEN o caller recebe erro (DeathRecipient) e o fluxo mostra o erro padrão — overlay/IME seguem vivos (NAT-08).
- WHEN o app é desinstalado THEN os modelos somem junto (armazenamento interno — comportamento esperado e documentado na nota do painel).
- WHEN `externalFilesDir` (destino temporário do DownloadManager) está indisponível THEN erro amigável ao iniciar o download.
- WHEN `partialOnPause` liga/desliga no meio de uma gravação THEN o valor vale a partir da **próxima** sessão (sem trocar o modo de gravação no meio).
- WHEN o usuário edita o input parcial enquanto um segmento ainda está transcrevendo THEN o append entra ao final do texto editado quando chegar (PAR-06), nunca no meio.
- WHEN a origem é remota e um segmento falha por rede THEN PAR-09 (re-tentativa no envio) — a pausa não vira bloqueio.
- WHEN o processo morre com buffer parcial em memória THEN o buffer se perde (memória por design, G12); os arquivos de segmento no cache seguem a limpeza atual de gravações.

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| NAT-01 | P1-A | NM-T9 | Implemented |
| NAT-02 | P1-A | NM-T5 | Implemented |
| NAT-03 | P1-A | NM-T2, NM-T9 | Implemented |
| NAT-04 | P1-A | NM-T8, NM-T9 | Implemented |
| NAT-05 | P1-A | NM-T8, NM-T9 | Implemented |
| NAT-06 | P1-A | NM-T8 | Implemented |
| NAT-07 | P1-A | NM-T8 | Implemented |
| NAT-08 | P1-A | NM-T8 | Implemented |
| NAT-09 | P1-A | NM-T9 | Implemented |
| PRV-01 | P1-A | NM-T9 | Implemented |
| CAT-01 | P1-B | NM-T3 | Implemented |
| CAT-02 | P1-B | NM-T3 | Implemented |
| CAT-03 | P1-B | NM-T10 | Implemented |
| DLM-01 | P1-B | NM-T6 | Implemented |
| DLM-02 | P1-B | NM-T7 | Implemented |
| DLM-03 | P1-B | NM-T4 | Implemented |
| DLM-04 | P1-B | NM-T6 | Implemented |
| DLM-05 | P1-B | NM-T6 | Implemented |
| DLM-06 | P1-B | NM-T6 | Implemented |
| DLM-07 | P1-B | NM-T7 | Implemented |
| DLM-08 | P1-B | NM-T6 | Implemented |
| MGR-01 | P1-C | NM-T2, NM-T10 | Implemented |
| MGR-02 | P1-C | NM-T10 | Implemented |
| MGR-03 | P1-C | NM-T10 | Implemented |
| MGR-04 | P1-C | NM-T10 | Implemented |
| MGR-05 | P1-C | NM-T10 | Implemented |
| MGR-06 | P1-C | NM-T10 | Implemented |
| MGR-07 | P1-C | NM-T11 | In Tasks |
| STO-01 | P2-D | NM-T12 | In Tasks |
| STO-02 | P2-D | NM-T1, NM-T12 | Implementing |
| STO-03 | P2-D | NM-T12 | In Tasks |
| PAR-01 | P2-E | NM-T14 | Implemented |
| PAR-02 | P2-E | NM-T15 | Implemented |
| PAR-03 | P2-E | NM-T16 | Implementing |
| PAR-04 | P2-E | NM-T17 | In Tasks |
| PAR-05 | P2-E | NM-T17 | In Tasks |
| PAR-06 | P2-E | NM-T16, NM-T17 | Implementing |
| PAR-07 | P2-E | NM-T18 | Implementing |
| PAR-08 | P2-E | NM-T18 | Implementing |
| PAR-09 | P2-E | NM-T16, NM-T18 | Implementing |

**Coverage:** 40 total, 40 mapeados em tasks, 0 sem mapa.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Modo avião** no Galaxy A32: ditar frase técnica em PT com o modelo "Rápido" → texto correto inserido, zero rede.
- [ ] Baixar/cancelar/deletar modelos funciona com app fechado no meio do download (retomada) — sem estados órfãos na UI.
- [ ] Overlay/teclado **nunca caem** por falha do engine (inclusive matando o processo `:inference` à mão durante uma transcrição).
- [ ] Medição informativa no A32 (não é gate — G11): tempo de transcrição para 10 s de fala, por modelo do catálogo, registrado no STATE.
- [ ] **Parcial**: ditar→pausar→editar→retomar→pausar→enviar entrega o texto completo com a edição preservada; envio final perceptivelmente mais rápido (só o último segmento pendente).
- [ ] Gate `full` verde (unit + instrumentados) + goldens novos aprovados; requisitos desta spec → Verified.

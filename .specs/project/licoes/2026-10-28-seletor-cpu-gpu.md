# 2026-10-28 — Onde o modelo local roda: seletor CPU · GPU no card (equipe/seletor-processador)

Pedido do Yan: um segundo seletor no card de cada modelo local de Raciocínio para escolher CPU ou GPU, só com o que o aparelho e o modelo suportam. **NPU
ficou de fora por decisão dele** (exige biblioteca do fabricante e um `.litertlm` compilado por SoC; nenhum modelo do catálogo tem essa variante): não há
detecção de SoC nem campo de variante NPU no catálogo ou no manifesto remoto. Spec: `reasoning-models/spec.md` › P2-H (RPRC-01..07); tasks RSN-T20.

- **A GPU é detectada pelo arquivo, não carregando a biblioteca.** `ComputeSupport` olha a existência de `libOpenCL.so` em `/vendor/lib64`, `/system/vendor/lib64`
  e `/system/lib64` (o LiteRT-LM só publica arm64-v8a e x86_64). O motor abre a biblioteca por esse nome (`dlopen("libOpenCL.so")`, visto nas strings da
  `liblitertlm_jni.so` 0.16.1). Carregá-la para ter certeza rodaria código do fabricante no processo do teclado (um driver ruim derrubaria o IME e a orbe) e custa
  mais que um `stat`; o erro da detecção por arquivo (tem o arquivo, o driver não serve) tem rede: a carga que falha volta para a CPU. O erro contrário (GPU boa sem
  o arquivo nesses caminhos) só deixa de oferecer a GPU. **A confirmar no aparelho real**: que o `stat` dessas pastas passa pelo SELinux do app nos aparelhos do Yan
  (se não passar, o seletor nunca aparece lá). O emulador do pool não tem OpenCL, então o seletor não aparece nele; a rota de galeria `config-raciocinio-local-gpu`
  simula um aparelho que tem.
- **Manifesto**: `<uses-native-library libOpenCL.so required=false>` agora está no manifesto principal (antes só existia num patch de teste), valendo no release.
  Do Android 12 em diante o app só enxerga a `libOpenCL.so` do fabricante se a declarar.
- **O backend é parte da identidade do motor residente.** `LlmModel.backend` (e `LocalLlmConfig.backend` no AIDL) entra ao lado de id e versão: pedir GPU com o
  modelo carregado na CPU descarrega e recarrega, e o contrário também.
- **Falha de carga na GPU volta para a CPU no mesmo pedido** (a resposta do usuário não se perde): o núcleo recarrega o mesmo modelo na CPU, chama
  `Listener.onBackendFallback` (AIDL `IReasoningCallback.onBackendFallback`), e o app grava a CPU como escolha (`LocalBackendPrefs.fellBack`) e o card mostra "A GPU
  não funcionou neste aparelho; usando a CPU." O motivo vai para o log (`LocalBackend` no app, `ReasoningService` no `:reasoning`). O núcleo lembra a falha
  (modelo|versão|backend) enquanto o processo vive: um pedido automático que ainda traz a escolha antiga vai direto para a CPU em vez de repetir minutos de compilação
  da GPU; só a **seleção explícita** (escolher o modelo ou o backend no card, que chama `preload`) tenta a GPU de novo.
- **RamGuard** (decisão pedida pelo Yan): CPU segue a regra de sempre (inclusive na volta do fallback); a GPU **não** é barrada pela estimativa de CPU
  (`recommendedRamMb / 2` e `fitsDevice` foram medidos com o pico de VmRSS na CPU). Motivo concreto: o Qwen3 4B Instruct-2507 só rodou na GPU do Vaio TL10 (a CPU passou
  de 5 GB e o Android matou o processo), e a guarda de CPU o barraria antes de a GPU tentar; o card do 4B (`recommendedRamMb = 12288`) agora é usável nos aparelhos de
  8 GB escolhendo a GPU. A pré-carga na GPU mantém só o piso de RAM livre (`PRELOAD_FLOOR_MB`). **Risco aceito, sem medida**: se a carga na GPU estourar a RAM e o
  lmkd matar o `:reasoning` inteiro, não há volta (o processo morre; o cliente vê o erro "o processo do motor local morreu") e a escolha gravada continua GPU. Medir o
  pico de RAM na GPU por modelo é o próximo passo (junto do benchmark por aparelho).
- **Primeira carga na GPU demora** (medido no Vaio TL10, Mali-G57, Gemma 4 E2B: ~117 s a primeira, ~21 s com o cache; primeiro trecho 2,8 s contra 3,0 s na CPU; total
  12,0 s contra 8,1 s). Por isso, ao escolher a GPU o card avisa "Na GPU, a primeira carga pode levar alguns minutos." até a GPU carregar o modelo uma vez
  (`LocalBackendPrefs.gpuReady`, gravado quando uma geração ou pré-carga na GPU termina sem voltar para a CPU).
- **Onde fica a preferência**: chave `reasoning_local_backends` do DataStore (texto por linha, tolerante a lixo), fora de `Settings` — como as barras e as grades —, por
  modelo, padrão CPU. Lida a cada geração (`SettingsRepository.localBackendFor`, só o DataStore): a troca no card vale já na próxima resposta. Apagar o modelo esquece a
  escolha, o aviso e a "GPU pronta".
- **Teste de ponta a ponta no emulador, sem GPU**: o `EchoBackend` (debug) recusa qualquer backend que não seja a CPU, o que reproduz "a GPU não carrega" no
  `:reasoning` de verdade (`LocalReasoningEngineTest`: o pedido na GPU responde inteiro pela CPU e o app é avisado).
- **Fica de fora**: NPU; voltar para a CPU quando a **geração** (não a carga) falha numa GPU já carregada; proteção contra o ciclo "GPU mata o processo toda vez".

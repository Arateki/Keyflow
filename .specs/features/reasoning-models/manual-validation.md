# Roteiro manual — reasoning-models (RSN-T19) [YAN]

Device: Galaxy A32 (`RX8T10DERKW`). APK: `./gradlew installDebug` (build **completo**, nunca só
`compileDebugKotlin`). Antes de começar, CI verde no commit instalado (unit + instrumentados + goldens).
Instrumentado no aparelho: só `am instrument` (o `connectedAndroidTest` desinstala o app e derruba o KeyFlow como teclado).

Os logins ChatGPT, Claude e Google também são incluídos no release (decisão RPRV-04, 2026-10-23). Para este roteiro, use
`installDebug`; o login Google no release depende da configuração de Console com SHA-1 Play e `applicationId` definitivos **[YAN]**.
A **chave de API** funciona em qualquer build.

Medições em tempo real (deixe rodando num terminal):

```bash
adb -s RX8T10DERKW logcat -s ReasoningService LocalReasoningEngine
```

Anote tudo na tabela do fim. **O que nenhum teste automático prova**: o LLM real no A32 (tempo, RAM), o registro dinâmico da
OpenAI aceitando o KeyFlow, e o callback do login com o app em segundo plano num Samsung. É para isso que este roteiro existe.

## A. Modelos locais (RSC, RMD, RRUN)

1. Aba **Raciocínio** (o cérebro de perfil no menu) → origem **Local**. Aparecem **Leve** (Qwen3 0.6B, ~585 MB),
   **Versátil** (Qwen2.5 1.5B, ~1,60 GB), **Intermediário** (SmolLM2 1.7B, ~1,73 GB), **Raciocínio** (DeepSeek R1 1.5B, ~1,83 GB),
   **Equilibrado** (Gemma 4 E2B, ~2,59 GB), **Potente** (Qwen3 4B Instruct 2507, ~2,66 GB) e **Avançado** (Gemma 4 E4B, ~3,66 GB), com tamanho e
   idioma; nota de privacidade no fim. **Nenhum modelo de voz** na lista.
2. Em **Wi-Fi**, "Baixar" no **Leve** → barra com % no card **e** notificação do sistema. Ao terminar, o Leve fica
   **selecionado sozinho**. A aba **Transcrição** continua com o modelo de voz que já tinha (nada mudou lá).
3. **Testar**. Anote **"Primeiro trecho / total"**. A **1ª vez inclui a carga a frio** do modelo (no emulador x86_64 foram
   ~67 s; com o cache quente ~2 s) — repare se o botão fica em **"Parar"** sem travar e se falta um aviso "carregando o modelo…".
   Toque **Testar** de novo logo em seguida: deve ser bem mais rápido (modelo já carregado).
4. O texto vem **em português**, curto, **sem `<think>…`** (o pensamento está desligado de propósito). Se aparecerem
   marcas de raciocínio, registre.
5. **Equilibrado**: o card mostra o aviso de **"pesado"**? Baixe (precisa de ~2,6 GB livres — sem espaço, o card avisa antes) e
   **Testar**. Se aparecer "Memória insuficiente…", registre os MB livres que o log mostrar e siga.
   **Potente** (Qwen3 4B): o card mostra o aviso de "pesado" em qualquer aparelho com menos de 12 GB, e a guarda de carga exige ~9,8 GB de RAM total
   (o app só usa a CPU): num aparelho de 8 GB, ao tocar em Testar, deve aparecer "Memória insuficiente…" em vez de a carga morrer no meio (no Vaio TL10, a CPU passou de 5 GB
   e o Android matou o processo; só a GPU, que o app ainda não oferece, o carregou). Só vale tentar o Testar num aparelho de 12 GB ou mais; anote o pico de VmRSS do `:reasoning`.
6. **Modo avião ligado**, Leve selecionado → **Testar** funciona (é 100% local).
7. **Matar o `:reasoning` no meio**: toque Testar e, durante a resposta:
   ```bash
   adb -s RX8T10DERKW shell run-as com.arateki.keyflow kill -9 $(adb -s RX8T10DERKW shell pidof com.arateki.keyflow:reasoning)
   ```
   → a área mostra "O motor local foi interrompido — tente de novo", **o teclado e o ditado seguem normais**, e um **novo Testar
   funciona** (o processo sobe de novo).
8. **Descarregar por ociosidade**: depois de um Testar, deixe ~2 min 30 s sem usar e rode
   `adb -s RX8T10DERKW shell dumpsys meminfo com.arateki.keyflow:reasoning` — o PSS deve **cair** (modelo descarregado) ou o processo
   sumir. Anote quanto tempo levou.
9. **Apagar** o modelo selecionado → o Testar desliga e diz "Baixe e selecione um modelo para testar."

## B. OpenAI com chave de API (RCL, RTS)

10. Origem **OpenAI** → o painel mostra **"Não conectado"**, o botão **Continue with ChatGPT** e o campo **Chave de API**.
11. Cole uma **chave válida** → o status vira **"Conectado com chave de API"**; o campo fica **mascarado** (bolinhas); aparece o
    dropdown **Modelo** com o que a conta pode usar (anote se a lista veio — o formato da chave não foi conferido contra a API
    real). Escolha um → **Testar** → a resposta chega em fluxo.
12. Cole uma chave **inválida** → Testar → "A credencial foi recusada — entre de novo ou confira a chave de API." A chave **não
    aparece em claro** em lugar nenhum (nem no campo, nem na mensagem).
13. Sem rede (modo avião) → Testar → "Sem conexão com o provedor…". Volte a rede → Atualizar (modelos) e Testar funcionam.
14. **Desconectar** → "Não conectado", o campo esvazia e o Testar desliga com o motivo.

## C. Login "Continue with ChatGPT" (conta de plano) — o ponto de maior risco

Precisa de uma conta ChatGPT com plano e de um navegador padrão. **Não cole tokens nem códigos em lugar nenhum**: se algo der
errado, anote só a mensagem da página e o texto do app.

15. **Registro dinâmico aceito?** Toque **Continue with ChatGPT** → o navegador abre na página de consentimento da OpenAI, com o
    nome do app. **Se a OpenAI recusar** (erro na página, "client not registered"…): anote a mensagem e **pare aqui** — é o
    achado mais importante do roteiro (RPRV-04/AUTH-09).
16. **Com o KeyFlow em segundo plano**: na página da OpenAI, **espere 30 s ou mais** antes de aprovar (o navegador fica na
    frente; o KeyFlow, atrás). Aprove → o navegador volta ao app → status **"Conectado como <seu e-mail>"**.
    - Se o app **for morto** em segundo plano (o Samsung fecha o processo): registre. Isso pede **uma task nova** — receptor do
      redirect num serviço em primeiro plano (design §8).
    - Se aparecer uma página de erro do navegador ("não foi possível conectar a 127.0.0.1"): o receptor já tinha fechado.
      Registre quanto tempo esperou.
17. Na **1ª vez**, aparece o aviso **"You're using your ChatGPT plan" / "Got it"** — e **só nessa vez** (desconecte e entre de
    novo: não deve reaparecer).
18. Com o plano em uso, a tela mostra **"Using ChatGPT plan"** e o link **"Manage usage"** (abre
    `https://chatgpt.com/settings/usage` no navegador), e a nota de que esse uso **não é uma cobrança do KeyFlow**.
19. Escolha um **Modelo** da lista → **Testar** → a resposta chega em fluxo, no plano (não na cobrança da API).
20. **Renovação**: o token de acesso dura **1 h**. Deixe o app parado por **mais de 1 h** e toque **Testar**: deve funcionar
    **sem pedir login** (renovou sozinho). Faça **dois Testar seguidos** logo depois — ambos devem funcionar (o refresh é
    rotativo: se a segunda falhar com "sessão expirou", a renovação está errada — registre).
21. **Limite de uso** (só se acontecer): o Testar mostra "Limite de uso atingido" e o botão principal **Manage usage**.
22. **Sem o uso do plano** (só se acontecer): a tela avisa e oferece **Continue with ChatGPT** (reautoriza com consentimento) ou a
    chave de API.
23. **Desconectar** → "Não conectado". Depois **Continue with ChatGPT** de novo: entra **sem novo registro** (a OpenAI já
    conhece o app neste aparelho). Anote se pediu consentimento outra vez.
24. **Cancelar no meio**: toque Continue with ChatGPT e, com o navegador aberto, volte ao app → "Cancelar" → volta a "Não
    conectado", sem erro. Feche o navegador sem aprovar e espere 5 min: o app mostra "O navegador não voltou a tempo".

## D. Onde ficam as credenciais (segurança, só olhar)

25. ```bash
    adb -s RX8T10DERKW shell run-as com.arateki.keyflow ls shared_prefs
    adb -s RX8T10DERKW shell run-as com.arateki.keyflow cat shared_prefs/keyflow_auth.xml
    ```
    Existe `keyflow_auth.xml` e o conteúdo é **ilegível** (cifrado): não pode conter `sk-`, seu e-mail, nem `eyJ` (o começo de um
    JWT). Confira também que `files/datastore/` (as Settings) **não** tem nenhuma dessas strings (`adb … run-as com.arateki.keyflow grep -rl 'sk-' files shared_prefs` não pode achar nada).

## E. Medições para ajustar (RSN-T19 — `idleUnloadMs`, `recommendedRamMb`)

26. Durante o Testar, rode `adb -s RX8T10DERKW shell dumpsys meminfo com.arateki.keyflow:reasoning` e anote o **TOTAL PSS** do Leve e do
    Equilibrado (o catálogo recomenda 3072 MB e 4096 MB de RAM total para eles, calibrados em 2026-10-27 pelo pico de VmRSS; a estimativa de
    trabalho é a metade). Diga se o aviso "pesado" apareceu no que devia.
27. Se o modelo descarrega cedo demais (recarga a cada Testar) ou tarde demais (RAM presa), diga: hoje são **120 s**
    (`LlmModels.IDLE_UNLOAD_MS`).

## Resultados (preencher)

| Passo | Resultado | Anotação |
|---|---|---|
| 3 — Leve: 1º trecho / total (frio) | | |
| 3 — Leve: 1º trecho / total (quente) | | |
| 5 — Equilibrado: aviso de peso / 1º trecho / total | | |
| 7 — matar `:reasoning`: erro, teclado ok, novo Testar ok | | |
| 8 — descarga por ociosidade (tempo) | | |
| 11 — chave válida: lista de modelos veio? Testar ok? | | |
| 15 — registro dinâmico aceito pela OpenAI? | | |
| 16 — callback com o app em segundo plano (espera de ___ s) | | |
| 20 — renovação após 1 h; dois Testar seguidos | | |
| 23 — reconectar sem novo registro | | |
| 25 — `keyflow_auth.xml` ilegível | | |
| 26 — PSS do Leve / Equilibrado | | |

Depois de rodar, registre os resultados no `.specs/project/STATE.md` (seção reasoning-models). Se o passo 16 falhar por o app ser
morto em segundo plano, abrir uma task para o receptor em serviço em primeiro plano (design §8). Se o passo 15 falhar, a decisão
de elegibilidade (RPRV-04, `PLAYSTORE_CHECKLIST.md` seção 1c) muda de assunto: fica só a chave de API.

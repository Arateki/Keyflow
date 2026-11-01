# 2026-11-01 — Chaves e diálogos SSH (Computador E, CMP-T16)

Branch `equipe/computador-e`, baseada em `origin/main` com CMP-T13..15. A aba continua em "Em breve" até a CMP-T17.

## Segurança dos campos

`PasswordVisualTransformation` só mascara a apresentação: os campos Compose também oferecem `InputText`, que permite consultar o valor anterior à transformação. Por isso senha, passphrase, respostas keyboard-interactive e privada colada usam `CampoSecretoComputador`: `EditText` sem salvamento/autofill, árvore nativa excluída da acessibilidade, transformação que mascara inclusive o último caractere, cópia/corte bloqueados e uma ponte Compose que publica rótulo, máscara, foco e ações de edição, sem o texto do campo. Não publica `Text`, `EditableText` nem `InputText`.

O indicador de campo vazio é observável para habilitar "Salvar"; os caracteres continuam fora do estado textual do Compose. Os buffers próprios são `CharArray`, sem `rememberSaveable`: cada troca zera o anterior, e a passphrase e as cópias da operação são zeradas após cada tentativa. A privada de entrada permanece no formulário para corrigir a passphrase; é zerada no sucesso ou descarte. O importador recebe cópias e continua responsável por zerá-las; a interface também as zera em `finally`. O arquivo é lido por `OpenDocument`, sem copiar para disco nem persistir permissão, com limite de 1 MiB de caracteres; a leitura fecha o descritor. A senha/resposta só vira `String` na entrega à porta SSH existente, que exige esse tipo; não há persistência nova dessas respostas.

Referência primária: [Compose SemanticsNodeInteraction — InputText ignora a transformação visual](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction).

## SPEC_DEVIATION decidido pelo coordenador, sujeito à revisão do Yan: alterar biometria preserva a chave

As autorizações de uma chave Android Keystore são definidas na criação/importação e não podem ser alteradas depois; a privada nativa não é exportável. [Documentação oficial do Keystore](https://developer.android.com/privacy-and-security/keystore). Trocar apenas o metadado `exigeBiometria` mentiria sobre a proteção real; gerar sobre o mesmo alias destruiria a identidade anterior.

O coordenador decidiu nesta tarefa (não foi decisão do Yan; o Yan pode revisar): **preservar a chave e oferecer gerar outra com a nova política**. A ação "Alterar biometria" explica que a nova pública precisa ser autorizada nos servidores; "Gerar outra chave" conserva original, hosts e permissões. A mesma ação conservadora vale para importadas: não reencapsula nem destrói a original. O novo formulário pede tipo, nome e confirmação do aviso antes de ligar biometria. Nunca enfraquece o Keystore para uma verificação só no app. A proteção mostrada vem do provedor (`KeyInfo`), não da API do aparelho. A sonda Ed25519 é mostrada separadamente da proteção da chave existente.

## Confiança e ciclo de vida

`InteracaoDoApp` serializa pedidos com `Mutex`, recusa sem tela e completa a recusa quando ela sai. A tela acompanha o lifecycle e coleta pelo `collectAsStateOnMain`; trabalho de chaves roda em IO e escreve estado de interface na Main. O cadastro da activity visível usa liberação por identidade para uma tela antiga não apagar a referência da nova. `SetupActivity` passou a `FragmentActivity`, requisito do `BiometricPrompt`, sem promover a aba. Uma troca de chave só chega ao `known_hosts` depois da segunda decisão explícita. "Cancelar" é o botão destacado e recebe foco nos dois passos. O agente continua sem qualquer caminho para confirmar servidor novo ou mudado. A rotação anunciada pelo servidor e o teste de conexão efêmero conservam as regras anteriores.

## Validação

Tudo pelo pool, sempre `--destino servidor`:

- `compilar --destino servidor --max-workers=1 testDebugUnitTest lintDebug assembleDebugAndroidTest`: app **3171/3171**, termlib **3/3**, sem falhas/erros/ignorados; lint **0 erros, 0 avisos**; APK de testes compilado.
- `instrumentar --destino servidor com.keyflow.ui.config.ComputadorChavesTest com.keyflow.ui.config.ComputadorDialogosTest`: **25/25** (14 Chaves, 11 Diálogos), na versão final; log `/tmp/pool-instrumentar.rvz8ovzu.log`.
- `SetupActivityTest` **2/2** e `TerminalActivityTest` **4/4**, log `/tmp/pool-instrumentar.ie4beeb_.log` (as falhas iniciais daquela rodada eram dos novos testes, depois corrigidas).
- O teste da proteção exibida usa três fakes (hardware, Keystore por software e envelope) e uma geração **ECDSA P-256 real**, com limpeza do alias ao terminar. Cópia da pública conferida para ED25519 e ECDSA. Senha também exercitada pela `InputConnection` nativa: inclusive o último caractere mascarado, salvamento desabilitado e cópia bloqueada.
- Primeira rodada JVM: **3.165/3.170**; cinco falhas por timeout de 30 s ao compilar o oráculo C libvterm. O mesmo oráculo, sem alteração, passou **5/5 em 1,128 s** isolado. A rodada completa final acima passou com um worker; não aumentei prazos nem ignorei testes.
- Os instrumentados acharam problemas reais: o campo privado precisava notificar o botão de importação de que já tinha conteúdo; `vazio` passou a ser um estado booleano observável. A ponte de edição precisa de foco e `InsertTextAtCursor`, além de `SetText`; nenhuma dessas ações publica o segredo. Foco de Cancelar é solicitado depois de a janela do diálogo ganhar foco, em ambos os passos; Enter cancela.

### Mutação

Depois dos 25/25 verdes, acrescentei o conteúdo secreto ao `contentDescription` do campo (somente sentinelas falsas). Os testes `passphraseErradaELimpaEPrivadaNuncaEntraEmSemantics` e `senhaMascaradaNaoPublicaTextoNemInputText` falharam exatamente na varredura: **2/2 detectaram**, log `/tmp/pool-instrumentar.vatqo8zo.log`. Restaurei o arquivo byte a byte, SHA-256 `87f8c47d9a7e6bbe36973465bf885a3965cb1790d27daaac55f525e643e846ac`, e a rodada final voltou a **25/25**. A mutação não foi commitada.

## Limites

A promoção da aba e o painel Sessões pertencem à CMP-T17; galeria/goldens, à CMP-T19. Biometria forte em aparelho físico e cadastro real continuam na validação manual CMP-T21. Não houve intervenção manual no SystemUI/keyguard nem alteração no serviço do emulador ou em checkout de outra frente; ligar/estabilizar ficou a cargo do pool. A interface do seletor de arquivos não foi exercitada ponta a ponta. Nenhum segredo real foi usado.

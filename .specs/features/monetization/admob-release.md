# IDs AdMob por variante

O debug sempre usa os três IDs oficiais de teste do Google, mesmo com IDs reais configurados.
O release (inclusive variantes derivadas de perfil de base) exige IDs próprios: não existe fallback
para anúncios de teste. Valores ausentes, formato errado ou publisher de teste impedem a geração
dos recursos do release. O antigo `keyflowSmokeSigning` foi removido e é recusado pelo Gradle.

Configure fora do git, no `$GRADLE_USER_HOME/gradle.properties` (o caminho usado pelo `kgradle`
no servidor é `/srv/dev/keyflow/.gradle/gradle.properties`), ou em `local.properties` ignorado:

```properties
KEYFLOW_ADMOB_APP_ID=<ID do app: ca-app-pub-16 dígitos~10 dígitos>
KEYFLOW_ADMOB_BANNER_ID=<ID do banner: ca-app-pub-16 dígitos/10 dígitos>
KEYFLOW_ADMOB_INTERSTITIAL_ID=<ID do intersticial: ca-app-pub-16 dígitos/10 dígitos>
```

A precedência por chave é: `-P` → `gradle.properties` do usuário Gradle → `local.properties` →
variável de ambiente com o mesmo nome. O `gradle.properties` versionado não fornece esses IDs.

No GitHub, crie três **Actions variables** em Settings → Secrets and variables → Actions → Variables,
com os nomes acima. No workflow de distribuição, exponha cada uma via `env`, por exemplo
`KEYFLOW_ADMOB_APP_ID: ${{ vars.KEYFLOW_ADMOB_APP_ID }}` (e o equivalente para os dois blocos).
Não são credenciais. O `checks.yml` usa IDs sintéticos de formato válido apenas no passo de
verificação do release; o APK desse passo não é publicado nem serve para distribuição.
Para validar localmente antes da conta existir, use os mesmos valores sintéticos do Checks
no ambiente do comando; isso não prova carregamento de anúncios reais.

`kgradle assembleDebug assembleRelease testDebugUnitTest :app:lintDebug` valida o build.
`scripts/check-release-admob.py <APK>` varre todas as entradas descomprimidas, inclusive os recursos
UTF-16 e DEX UTF-8. `scripts/smoke-release.sh` também aplica essa verificação antes de instalar no
emulador. Nenhum desses comandos deve usar IDs reais no debug.

## Pendências [YAN] — V1-8

- [ ] Criar a conta AdMob, cadastrar o aplicativo e os blocos banner/intersticial; preencher os três
  IDs locais e as variáveis do workflow de distribuição antes de gerar o AAB para a loja.
- [ ] Hospedar `app-ads.txt` na raiz do domínio do site de desenvolvedor cadastrado na Play Store,
  com a linha `google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`, substituindo os 16 X
  pelo publisher da conta (o mesmo prefixo numérico dos IDs). Conferir o reconhecimento no AdMob.
- [ ] Validar consentimento e anúncios no fluxo interno com dispositivos de teste configurados no
  AdMob antes de promover à produção; os testes locais usam valores sintéticos e não fecham o e2e.

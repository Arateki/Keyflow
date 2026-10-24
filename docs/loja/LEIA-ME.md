# Documentação da Loja, Políticas e Termos — KeyFlow

Este diretório contém os textos oficiais da ficha do app na Google Play Store, o inventário de dados, a declaração de segurança de dados (Data Safety), a Política de Privacidade e os Termos de Uso do KeyFlow.

---

## 1. Fonte da Verdade e Publicação do Site

- **Fonte canônica:** Os arquivos Markdown (`.md`) deste diretório (`politica-de-privacidade.md`, `termos-de-uso.md`, `privacy-policy.md`, `terms-of-use.md`) são a única fonte da verdade.
- **Não edite arquivos HTML manualmente:** As páginas estáticas disponibilizadas em `https://keyflow.arateki.com` são geradas automaticamente pelo script `scripts/gerar-site.py`.
- **Fluxo de alteração:**
  1. Crie uma branch e edite os arquivos `.md` correspondentes em `docs/loja/`;
  2. Teste a geração localmente rodando:
     ```bash
     python3 scripts/gerar-site.py
     ```
  3. Abra um Pull Request. A action `.github/workflows/site.yml` valida a geração das páginas automaticamente;
  4. Após a revisão e aprovação da equipe, o merge para a branch `main` executa a publicação automática dos arquivos gerados no bucket Cloudflare R2 (`keyflow-site`), atualizando o site público imediatamente.

---

## 2. Atualização da Divulgação em Destaque no App (Prominent Disclosure)

Caso uma alteração na Política de Privacidade ou nas práticas de tratamento de dados seja relevante ou material (ex.: novo destino de rede, novos provedores de nuvem ou novas permissões):

- **Obrigatório elevar a versão do aceite:** O aplicativo KeyFlow exige que todos os usuários (incluindo aqueles que estão atualizando o aplicativo) aceitem a versão vigente da divulgação em destaque antes que qualquer gravação de voz possa ser iniciada.
- **Onde fica a versão no código:**  
  A constante de versão está localizada em:
  ```kotlin
  // app/src/main/java/com/keyflow/ui/onboarding/DisclosureConsent.kt
  const val DISCLOSURE_VERSION = 1
  ```
- Ao incrementar `DISCLOSURE_VERSION`, os testes instrumentados e unitários de consentimento (`DisclosureConsentTest`, `DisclosureUpgradeTest`) garantem que o gate de captura bloqueie gravações até que o novo aceite seja confirmado na tela de divulgação.

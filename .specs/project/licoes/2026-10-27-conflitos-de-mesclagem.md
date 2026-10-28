# 2026-10-27 — Menos conflito entre PRs paralelos (equipe/menos-conflitos)

- **Medido, não chutado.** Das 27 mesclagens de sincronização (`main` → branch) entre 20 e 27/10, 19 pararam em conflito
  e 18 delas em `STATE.md`: sempre dois blocos acrescentados no fim. Os 32 "Merge pull request" não conflitaram, porque a
  branch já tinha sincronizado antes. Por isso a lição nova passou a ser um arquivo por assunto (esta pasta) e o
  `.gitattributes` ganhou `merge=union` em `STATE.md` e `app/licenses/README.md`. Refeitas com isso, 20 das 27 passam
  limpas; as 7 restantes são edição real do mesmo trecho (`DocsPanel.kt`, `tasks.md`/`spec.md` da mesma feature, a
  integração da V1-10).
- **Union só onde só se acrescenta.** Fora dele, de propósito: `tasks.md`, `spec.md`, `DEVELOPMENT-ORDER.md` (status
  editado no meio duplicaria a linha antiga sem avisar), código, traduções, `.gitignore` (ordem dos `!` importa) e arquivo
  gerado. `scripts/tests/test_mesclagem.py` trava a lista.
- **O Git lê o `.gitattributes` do diretório, não da mesclagem** (medido no Git 2.39). Branch aberta antes dele conflita
  na primeira sincronização; a receita com `-c core.attributesFile=` está no `TESTING.md` › Conflitos de mesclagem.
- **`merge_group` já estava no `checks.yml`** desde a criação do pipeline (2026-09-24), e nada nele lê
  `github.event.pull_request`: o `scripts/tests/test_mesclagem.py` passou a travar as duas coisas. Falta só a fila de
  mesclagem ser ligada nas configurações do repositório, que é do coordenador. Atenção: não sei se a mesclagem do lado do
  GitHub (fila) honra o `merge=union`; o ganho garantido é nas sincronizações locais. Por isso o que realmente tira o
  `STATE.md` da fila de conflitos é a pasta de lições.
- **`doc.json` é gerado** (`DocsSiteExportTest`) e nunca conflitou nesse período; se conflitar, resolva a fonte
  (`DocsPanel.kt`, traduções) e regere. Proposta, não feita: um `scripts/regerar-gerados.sh` que, depois da mesclagem,
  rode o teste com `KEYFLOW_REGERAR_DOC_SITE=1` e mostre `git status` do que mudou.

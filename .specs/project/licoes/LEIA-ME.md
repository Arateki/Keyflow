# Lições e decisões — um arquivo por assunto

Desde 2026-10-27, lição, decisão, bloqueio e ideia adiada novos entram **aqui**, num arquivo por assunto, e não mais no
fim do `../STATE.md`. Motivo medido: o `STATE.md` era o arquivo que mais conflitava (18 das 19 mesclagens com conflito
entre 20 e 27/10, sempre dois blocos acrescentados no mesmo fim). Arquivo novo não conflita com branch nenhuma.

O `../STATE.md` continua onde está, com o histórico até aqui: não mova nem reescreva nada dele.

## Como acrescentar

- **Nome**: `AAAA-MM-DD-assunto.md`, com a data em que você fecha o trabalho e o assunto em minúsculas, sem acento, com
  hífens (`2026-10-28-qwen-sem-pensar.md`). Só `a-z`, `0-9` e `-`: o `scripts/tests/test_mesclagem.py` confere.
- **Primeira linha**: `# AAAA-MM-DD — Título (branch ou feature)`, com a mesma data do nome (o teste confere), como os `##`
  do `STATE.md`. Depois, texto livre: o que se decidiu, por quê, o que foi medido, o que ficou para depois.
- **Um assunto por arquivo.** A task fechou com três lições independentes: três arquivos.
- **Escreva só o seu arquivo.** Para corrigir uma lição daqui, edite o arquivo dela. Para corrigir uma do `STATE.md`
  antigo, crie um arquivo novo que aponte para ela (`STATE.md › título da seção`).
- **Cite pelo caminho** (`licoes/2026-10-28-assunto.md`) no `tasks.md`, no `spec.md` e nos comentários de código. Não
  cite número de linha.
- `SPEC_DEVIATION` (regra de ouro 7) também vai aqui, e na task.

## Achar

```sh
ls .specs/project/licoes/                                  # cronológico, pelo nome
grep -rn "termo" .specs/project/licoes/ .specs/project/STATE.md
```

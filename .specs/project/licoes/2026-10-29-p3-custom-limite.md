# 2026-10-29 — Teto de saída em serviços Custom compatíveis

O enunciado da tarefa inverte os nomes; o código e a revisão #59 já usam `max_completion_tokens`. A [referência oficial de Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) o define como campo atual; `max_tokens` está depreciado e não serve à série o. Mantemos o atual como primeira tentativa e negociamos o legado apenas após rejeição explícita do nome em HTTP 400. Essa negociação é uma decisão local de compatibilidade, sem trocar modelo nem remover o limite.

O primeiro conjunto de oito testes falhou em três casos: servidor legado não concluía, a segunda rejeição nem era tentada e o cancelamento não podia alcançar uma tentativa de compatibilidade. Após a correção, nove casos e os dois testes existentes passaram. A nova tentativa mantém endpoint, cabeçalhos e valor; não repete erros genéricos, autenticação, valores inválidos ou pedido sem limite. Nenhum trecho é emitido antes da negociação, limitada a uma repetição.

O observador cancela o Call atual por AtomicReference, incluindo a janela entre as tentativas. A inspeção do JSON de erro é limitada a 8 KiB e não copia seu texto para a saída. Servidores com erros não reconhecíveis continuam falhando de forma explícita. Só MockWebServer e credenciais falsas foram usados; o corpo padrão permanece igual.

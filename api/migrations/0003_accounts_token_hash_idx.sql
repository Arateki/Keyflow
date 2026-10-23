-- Toda request autenticada busca a conta por token_hash (find_by_token_hash).
-- create/rotate gravam um hash novo e a leitura usa fetch_optional, que exige no máximo
-- uma linha. Índice único: colisão vira erro em vez de login ambíguo.
create unique index accounts_token_hash_idx on accounts (token_hash);

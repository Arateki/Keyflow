-- A exclusão do aparelho remove seus vínculos na mesma transação e preserva as demais contas.
alter table ledger drop constraint ledger_account_id_fkey,
    add constraint ledger_account_id_fkey foreign key (account_id) references accounts(id) on delete cascade;
alter table purchases drop constraint purchases_account_id_fkey,
    add constraint purchases_account_id_fkey foreign key (account_id) references accounts(id) on delete cascade;
alter table rewards drop constraint rewards_account_id_fkey,
    add constraint rewards_account_id_fkey foreign key (account_id) references accounts(id) on delete cascade;
alter table transcribe_requests drop constraint transcribe_requests_account_id_fkey,
    add constraint transcribe_requests_account_id_fkey foreign key (account_id) references accounts(id) on delete cascade;

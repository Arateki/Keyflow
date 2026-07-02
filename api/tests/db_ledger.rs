mod common;

use common::{balance_of, create_test_account, test_pool};
use keyflow_api::db::ledger::{LedgerReason, credit, debit_guarded, revoke};

#[tokio::test]
async fn migrations_apply_from_scratch_and_health_tables_exist() {
    let pool = test_pool().await;

    // As 6 tabelas do schema núcleo (design.md §Schema) devem existir após as migrations.
    let tables: Vec<(String,)> = sqlx::query_as(
        "select table_name from information_schema.tables \
         where table_schema = 'public' order by table_name",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    let names: Vec<String> = tables.into_iter().map(|(n,)| n).collect();

    for expected in [
        "accounts",
        "documents",
        "ledger",
        "purchases",
        "rewards",
        "transcribe_requests",
    ] {
        assert!(
            names.contains(&expected.to_string()),
            "faltando tabela {expected}: {names:?}"
        );
    }
}

#[tokio::test]
async fn credit_updates_balance_and_inserts_ledger_row() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 0).await;

    credit(
        &pool,
        account_id,
        300,
        LedgerReason::Purchase,
        Some("credits_10"),
    )
    .await
    .unwrap();

    assert_eq!(balance_of(&pool, account_id).await, 300);

    let (delta, reason): (i64, String) =
        sqlx::query_as("select delta_seconds, reason from ledger where account_id = $1")
            .bind(account_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(delta, 300);
    assert_eq!(reason, "purchase");
}

#[tokio::test]
async fn debit_guarded_succeeds_when_balance_is_enough() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 60).await;

    let ok = debit_guarded(
        &pool,
        account_id,
        45,
        LedgerReason::Transcription,
        Some("req-1"),
    )
    .await
    .unwrap();

    assert!(ok);
    assert_eq!(balance_of(&pool, account_id).await, 15);
}

#[tokio::test]
async fn debit_guarded_fails_without_mutating_when_balance_is_insufficient() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 10).await;

    let ok = debit_guarded(
        &pool,
        account_id,
        11,
        LedgerReason::Transcription,
        Some("req-2"),
    )
    .await
    .unwrap();

    assert!(!ok);
    assert_eq!(balance_of(&pool, account_id).await, 10);

    let ledger_rows: i64 = sqlx::query_scalar("select count(*) from ledger where account_id = $1")
        .bind(account_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(
        ledger_rows, 0,
        "débito recusado não deve deixar linha no ledger"
    );
}

/// Duas transações concorrentes disputando o último saldo: só uma pode vencer (guarda via
/// `UPDATE ... WHERE balance_seconds >= $delta`, serializada pelo lock de linha do Postgres).
#[tokio::test]
async fn debit_guarded_only_one_of_two_concurrent_debits_wins_the_last_balance() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 10).await;

    let (r1, r2) = tokio::join!(
        debit_guarded(
            &pool,
            account_id,
            10,
            LedgerReason::Transcription,
            Some("race-a")
        ),
        debit_guarded(
            &pool,
            account_id,
            10,
            LedgerReason::Transcription,
            Some("race-b")
        ),
    );

    let results = [r1.unwrap(), r2.unwrap()];
    let wins = results.iter().filter(|ok| **ok).count();
    assert_eq!(
        wins, 1,
        "exatamente uma das duas corridas deveria vencer: {results:?}"
    );
    assert_eq!(balance_of(&pool, account_id).await, 0);
}

#[tokio::test]
async fn credit_and_ledger_are_atomic_rollback_leaves_no_partial_state() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 0).await;

    // Simula a falha "no meio" de uma operação maior: credita dentro de uma tx externa e depois
    // força um erro antes do commit — como `credit` já commitou seu próprio savepoint, o teste
    // teria saldo parcial se `credit` não fosse de fato atômico com o ledger; aqui validamos que
    // rollback da tx EXTERNA desfaz o savepoint interno de `credit` por completo.
    let mut tx = pool.begin().await.unwrap();
    credit(
        &mut tx,
        account_id,
        120,
        LedgerReason::Purchase,
        Some("credits_20"),
    )
    .await
    .unwrap();
    tx.rollback().await.unwrap();

    assert_eq!(balance_of(&pool, account_id).await, 0);
    let ledger_rows: i64 = sqlx::query_scalar("select count(*) from ledger where account_id = $1")
        .bind(account_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(
        ledger_rows, 0,
        "rollback da tx externa deve desfazer o savepoint de credit()"
    );
}

#[tokio::test]
async fn revoke_allows_negative_balance() {
    let pool = test_pool().await;
    let account_id = create_test_account(&pool, 5).await;

    revoke(&pool, account_id, -20, Some("voided-purchase"))
        .await
        .unwrap();

    assert_eq!(balance_of(&pool, account_id).await, -15);

    let (delta, reason): (i64, String) =
        sqlx::query_as("select delta_seconds, reason from ledger where account_id = $1")
            .bind(account_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(delta, -20);
    assert_eq!(reason, "revoke");
}

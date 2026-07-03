use sqlx::{Acquire, Postgres};
use uuid::Uuid;

/// Motivo do lançamento no ledger — espelha o `check` da coluna `reason` (migração 0001).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LedgerReason {
    Purchase,
    Transcription,
    Reward,
}

impl LedgerReason {
    fn as_str(self) -> &'static str {
        match self {
            LedgerReason::Purchase => "purchase",
            LedgerReason::Transcription => "transcription",
            LedgerReason::Reward => "reward",
        }
    }
}

/// Ajusta `balance_seconds` por `delta_seconds` (positivo credita, negativo debita **sem
/// guarda** — usado pelo proxy de transcrição, TRX-02, onde o saldo pode negativar levemente numa
/// corrida rara e o design aceita isso) + registra no `ledger`, na mesma tx (savepoint se `conn`
/// já estiver dentro de uma transação do chamador — nunca parcial).
pub async fn credit<'a, A>(
    conn: A,
    account_id: Uuid,
    delta_seconds: i64,
    reason: LedgerReason,
    reference: Option<&str>,
) -> sqlx::Result<()>
where
    A: Acquire<'a, Database = Postgres>,
{
    let mut tx = conn.begin().await?;

    sqlx::query("update accounts set balance_seconds = balance_seconds + $1 where id = $2")
        .bind(delta_seconds)
        .bind(account_id)
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        "insert into ledger (account_id, delta_seconds, reason, ref) values ($1, $2, $3, $4)",
    )
    .bind(account_id)
    .bind(delta_seconds)
    .bind(reason.as_str())
    .bind(reference)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(())
}

/// Debita `delta_seconds` (> 0) **com guarda**: só aplica se `balance_seconds >= delta_seconds`.
/// A guarda é a própria `UPDATE ... WHERE` (lock de linha do Postgres serializa débitos
/// concorrentes na mesma conta — só um vence a corrida pelo último saldo). Retorna `false` sem
/// mutar nada se o saldo for insuficiente.
pub async fn debit_guarded<'a, A>(
    conn: A,
    account_id: Uuid,
    delta_seconds: i64,
    reason: LedgerReason,
    reference: Option<&str>,
) -> sqlx::Result<bool>
where
    A: Acquire<'a, Database = Postgres>,
{
    let mut tx = conn.begin().await?;

    let updated: Option<(i64,)> = sqlx::query_as(
        "update accounts set balance_seconds = balance_seconds - $1 \
         where id = $2 and balance_seconds >= $1 \
         returning balance_seconds",
    )
    .bind(delta_seconds)
    .bind(account_id)
    .fetch_optional(&mut *tx)
    .await?;

    if updated.is_none() {
        tx.rollback().await?;
        return Ok(false);
    }

    sqlx::query(
        "insert into ledger (account_id, delta_seconds, reason, ref) values ($1, $2, $3, $4)",
    )
    .bind(account_id)
    .bind(-delta_seconds)
    .bind(reason.as_str())
    .bind(reference)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(true)
}

/// Reverte `delta_seconds` (normalmente negativo) **sem guarda** — o saldo pode negativar
/// (reembolso de compra voided, PAY-06). Reason sempre `'revoke'`.
pub async fn revoke<'a, A>(
    conn: A,
    account_id: Uuid,
    delta_seconds: i64,
    reference: Option<&str>,
) -> sqlx::Result<()>
where
    A: Acquire<'a, Database = Postgres>,
{
    let mut tx = conn.begin().await?;

    sqlx::query("update accounts set balance_seconds = balance_seconds + $1 where id = $2")
        .bind(delta_seconds)
        .bind(account_id)
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        "insert into ledger (account_id, delta_seconds, reason, ref) values ($1, $2, 'revoke', $3)",
    )
    .bind(account_id)
    .bind(delta_seconds)
    .bind(reference)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(())
}

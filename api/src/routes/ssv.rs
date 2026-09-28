use axum::extract::{RawQuery, State};
use axum::http::StatusCode;
use serde::Deserialize;
use uuid::Uuid;

use crate::AppState;
use crate::db::ledger::{LedgerReason, credit};
use crate::google::admob::{self, SsvVerifyError};

#[derive(Debug, Deserialize)]
struct SsvContentParams {
    custom_data: Option<String>,
    transaction_id: String,
}

/// `GET /v1/ssv/admob` — callback de rewarded SSV do AdMob (design §Rewarded, ADS-01). Sempre
/// `200` pra Google não reenviar (ela reenvia até 5x se não vir 200), **exceto** assinatura
/// inválida (`403`, conforme a task) — nesse caso não é um evento legítimo do AdMob mesmo.
pub async fn ssv_admob(State(state): State<AppState>, RawQuery(raw_query): RawQuery) -> StatusCode {
    let Some(raw_query) = raw_query else {
        return StatusCode::FORBIDDEN;
    };

    let content = match verify_with_refresh(&state, &raw_query).await {
        Ok(content) => content,
        Err(e) => {
            tracing::warn!(error = %e, "SSV signature verification failed");
            return StatusCode::FORBIDDEN;
        }
    };

    let params: SsvContentParams = match serde_urlencoded::from_str(content) {
        Ok(params) => params,
        Err(e) => {
            tracing::warn!(error = %e, "SSV callback missing required params after verification");
            return StatusCode::FORBIDDEN;
        }
    };

    let parsed_account_id = params
        .custom_data
        .as_deref()
        .and_then(|s| Uuid::parse_str(s).ok());

    let account_id = match parsed_account_id {
        Some(id) => match crate::db::accounts::exists(&state.pool, id).await {
            Ok(true) => Some(id),
            Ok(false) => None,
            Err(e) => {
                tracing::error!(error = %e, "failed to check account existence for SSV callback");
                return StatusCode::INTERNAL_SERVER_ERROR;
            }
        },
        None => None,
    };

    let Some(account_id) = account_id else {
        // "Não vazar existência": mesma resposta (200, sem crédito) tanto pra custom_data
        // ausente/malformado quanto pra um UUID válido que não é conta de ninguém.
        tracing::info!(custom_data = ?params.custom_data, "SSV callback: unknown/invalid account, no credit");
        return StatusCode::OK;
    };

    if let Err(e) = credit_reward(&state, account_id, &params.transaction_id).await {
        tracing::error!(error = %e, "failed to credit SSV reward");
        return StatusCode::INTERNAL_SERVER_ERROR;
    }

    StatusCode::OK
}

async fn verify_with_refresh<'a>(
    state: &AppState,
    raw_query: &'a str,
) -> Result<&'a str, SsvVerifyError> {
    let snapshot = state.admob_keys.snapshot();
    match admob::verify(raw_query, &snapshot) {
        Err(SsvVerifyError::UnknownKeyId(_)) => {
            let _ = state.admob_keys.refresh().await;
            let snapshot = state.admob_keys.snapshot();
            admob::verify(raw_query, &snapshot)
        }
        other => other,
    }
}

async fn credit_reward(
    state: &AppState,
    account_id: Uuid,
    transaction_id: &str,
) -> Result<(), sqlx::Error> {
    let mut tx = state.pool.begin().await?;

    let today_count: i64 = sqlx::query_scalar(
        "select count(*) from rewards where account_id = $1 and created_at >= date_trunc('day', now())",
    )
    .bind(account_id)
    .fetch_one(&mut *tx)
    .await?;

    if today_count >= state.ads_daily_cap {
        tracing::info!(%account_id, today_count, "SSV reward blocked by daily cap");
        tx.commit().await?;
        return Ok(());
    }

    let inserted: Option<(String,)> = sqlx::query_as(
        "insert into rewards (transaction_id, account_id, seconds) values ($1, $2, $3) \
         on conflict (transaction_id) do nothing \
         returning transaction_id",
    )
    .bind(transaction_id)
    .bind(account_id)
    .bind(state.ads_reward_seconds)
    .fetch_optional(&mut *tx)
    .await?;

    if inserted.is_some() {
        credit(
            &mut tx,
            account_id,
            state.ads_reward_seconds,
            LedgerReason::Reward,
            Some(transaction_id),
        )
        .await?;
    }

    tx.commit().await?;
    Ok(())
}

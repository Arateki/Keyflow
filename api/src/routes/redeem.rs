use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::device_key_account_id;
use crate::catalog;
use crate::db::accounts::Account;
use crate::db::ledger::{LedgerReason, credit};
use crate::google::integrity::verify_verdicts;
use crate::google::publisher::{PURCHASE_STATE_PENDING, PURCHASE_STATE_PURCHASED};
use crate::middleware::ratelimit::RateLimitRejection;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RedeemRequest {
    product_id: String,
    purchase_token: String,
    integrity_token: String,
}

#[derive(Debug, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum RedeemResponse {
    #[serde(rename = "pending")]
    Pending,
    #[serde(rename = "credited")]
    Credited,
}

pub enum RedeemError {
    RateLimited(RateLimitRejection),
    Integrity,
    UnknownProduct,
    AccountMismatch,
    PurchaseInvalid,
    Internal,
}

impl From<RateLimitRejection> for RedeemError {
    fn from(rejection: RateLimitRejection) -> Self {
        RedeemError::RateLimited(rejection)
    }
}

impl IntoResponse for RedeemError {
    fn into_response(self) -> Response {
        match self {
            RedeemError::RateLimited(rejection) => rejection.into_response(),
            RedeemError::Integrity => (
                StatusCode::FORBIDDEN,
                Json(serde_json::json!({ "error": "integrity" })),
            )
                .into_response(),
            RedeemError::UnknownProduct => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "unknown_product" })),
            )
                .into_response(),
            RedeemError::AccountMismatch => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "account_mismatch" })),
            )
                .into_response(),
            RedeemError::PurchaseInvalid => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "purchase_invalid" })),
            )
                .into_response(),
            RedeemError::Internal => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    }
}

pub async fn redeem(
    State(state): State<AppState>,
    account: Account,
    Json(body): Json<RedeemRequest>,
) -> Result<Json<RedeemResponse>, RedeemError> {
    state.rate_limiters.check_account(account.id)?;

    let product = catalog::find_product(&body.product_id).ok_or(RedeemError::UnknownProduct)?;

    let integrity_payload = state
        .integrity
        .decode(&body.integrity_token)
        .await
        .map_err(|_| RedeemError::Integrity)?;
    verify_verdicts(
        &integrity_payload,
        &state.package_name,
        state.integrity_relaxed,
    )
    .map_err(|_| RedeemError::Integrity)?;

    let purchase = state
        .publisher
        .get_product_purchase(&body.product_id, &body.purchase_token)
        .await
        .map_err(|_| RedeemError::PurchaseInvalid)?;

    let expected_account_id = device_key_account_id(&account.device_key);
    if purchase.obfuscated_external_account_id.as_deref() != Some(expected_account_id.as_str()) {
        return Err(RedeemError::AccountMismatch);
    }

    if purchase.purchase_state == PURCHASE_STATE_PENDING {
        return Ok(Json(RedeemResponse::Pending));
    }
    if purchase.purchase_state != PURCHASE_STATE_PURCHASED {
        return Err(RedeemError::PurchaseInvalid);
    }

    let token_hash = crate::auth::hash_token(body.purchase_token.as_bytes());

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| RedeemError::Internal)?;

    let inserted: Option<(bool,)> = sqlx::query_as(
        "insert into purchases \
             (token_hash, account_id, product_id, status, credited_seconds, granted_entitlements) \
         values ($1, $2, $3, 'redeemed', $4, $5) \
         on conflict (token_hash) do nothing \
         returning granted_entitlements",
    )
    .bind(&token_hash)
    .bind(account.id)
    .bind(&body.product_id)
    .bind(product.credited_seconds)
    .bind(product.grants_entitlements)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|_| RedeemError::Internal)?;

    let Some((granted_entitlements,)) = inserted else {
        // Replay: já processado antes (idempotência PAY-04) — nenhum crédito novo.
        tx.commit().await.map_err(|_| RedeemError::Internal)?;
        return Ok(Json(RedeemResponse::Credited));
    };

    credit(
        &mut tx,
        account.id,
        product.credited_seconds,
        LedgerReason::Purchase,
        Some(&body.product_id),
    )
    .await
    .map_err(|_| RedeemError::Internal)?;

    if granted_entitlements {
        sqlx::query("update accounts set ads_off = true, skins_unlocked = true where id = $1")
            .bind(account.id)
            .execute(&mut *tx)
            .await
            .map_err(|_| RedeemError::Internal)?;
    }

    tx.commit().await.map_err(|_| RedeemError::Internal)?;

    // Acknowledge server-side só na primeira vez (design §Compras) — falha aqui não desfaz o
    // crédito já commitado; o job de voided purchases (T9) reconcilia se o Google discordar.
    let _ = state
        .publisher
        .acknowledge_product_purchase(&body.product_id, &body.purchase_token)
        .await;

    Ok(Json(RedeemResponse::Credited))
}

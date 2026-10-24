use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::hash_token;
use crate::catalog;
use crate::db::accounts::Account;
use crate::jobs::voided;
use crate::middleware::ratelimit::RateLimitRejection;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SimulatePurchaseRequest {
    purchase_receipt: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RefundPurchaseRequest {
    purchase_receipt: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SimulatedPurchaseResponse {
    status: &'static str,
}

pub enum SimulatedPurchaseError {
    RateLimited(RateLimitRejection),
    InvalidReceipt,
    InvalidProduct,
    AlreadyRefunded,
    Internal,
}

impl From<RateLimitRejection> for SimulatedPurchaseError {
    fn from(value: RateLimitRejection) -> Self {
        Self::RateLimited(value)
    }
}

impl IntoResponse for SimulatedPurchaseError {
    fn into_response(self) -> Response {
        match self {
            Self::RateLimited(error) => error.into_response(),
            Self::InvalidReceipt => (
                StatusCode::FORBIDDEN,
                Json(serde_json::json!({"error":"invalid_simulated_receipt"})),
            )
                .into_response(),
            Self::InvalidProduct => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error":"unsupported_simulated_product"})),
            )
                .into_response(),
            Self::AlreadyRefunded => (
                StatusCode::CONFLICT,
                Json(serde_json::json!({"error":"simulated_purchase_refunded"})),
            )
                .into_response(),
            Self::Internal => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    }
}

/// Compra simulada não chama Google Play nem grava o recibo em claro. A rota inteira é 404
/// quando o flag do servidor está desligado (o padrão, inclusive em produção).
pub async fn simulate_purchase(
    State(state): State<AppState>,
    account: Account,
    Json(body): Json<SimulatePurchaseRequest>,
) -> Result<Json<SimulatedPurchaseResponse>, SimulatedPurchaseError> {
    state.rate_limiters.check_account(account.id)?;
    let secret = state
        .simulated_purchase_hmac_secret
        .as_deref()
        .ok_or(SimulatedPurchaseError::InvalidReceipt)?;
    let receipt = crate::simulated::verify(
        &body.purchase_receipt,
        secret,
        "purchase",
        &account.device_key,
        Some("unlock_forever"),
    )
    .map_err(|_| SimulatedPurchaseError::InvalidReceipt)?;
    let product = catalog::find_product(
        receipt
            .product_id
            .as_deref()
            .ok_or(SimulatedPurchaseError::InvalidProduct)?,
    )
    .filter(|product| product.grants_entitlements)
    .ok_or(SimulatedPurchaseError::InvalidProduct)?;

    let token_hash = hash_token(body.purchase_receipt.as_bytes());
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| SimulatedPurchaseError::Internal)?;
    let inserted: Option<(bool,)> = sqlx::query_as(
        "insert into purchases (token_hash, account_id, product_id, status, credited_seconds, granted_entitlements) \
         values ($1, $2, $3, 'redeemed', $4, true) on conflict (token_hash) do nothing \
         returning granted_entitlements",
    )
    .bind(&token_hash)
    .bind(account.id)
    .bind(product.id)
    .bind(product.credited_seconds)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|_| SimulatedPurchaseError::Internal)?;

    if inserted.is_none() {
        let status: Option<(String,)> = sqlx::query_as(
            "select status from purchases where token_hash = $1 and account_id = $2",
        )
        .bind(&token_hash)
        .bind(account.id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| SimulatedPurchaseError::Internal)?;
        tx.rollback()
            .await
            .map_err(|_| SimulatedPurchaseError::Internal)?;
        return match status {
            Some((status,)) if status != "voided" => Ok(Json(SimulatedPurchaseResponse {
                status: "already_credited",
            })),
            _ => Err(SimulatedPurchaseError::AlreadyRefunded),
        };
    }

    sqlx::query("update accounts set ads_off = true, skins_unlocked = true where id = $1")
        .bind(account.id)
        .execute(&mut *tx)
        .await
        .map_err(|_| SimulatedPurchaseError::Internal)?;
    tx.commit()
        .await
        .map_err(|_| SimulatedPurchaseError::Internal)?;
    Ok(Json(SimulatedPurchaseResponse { status: "credited" }))
}

/// Reembolso usa a mesma rotina transacional do job de compras revogadas do Google.
pub async fn refund_purchase(
    State(state): State<AppState>,
    account: Account,
    Json(body): Json<RefundPurchaseRequest>,
) -> Result<Json<SimulatedPurchaseResponse>, SimulatedPurchaseError> {
    state.rate_limiters.check_account(account.id)?;
    let secret = state
        .simulated_purchase_hmac_secret
        .as_deref()
        .ok_or(SimulatedPurchaseError::InvalidReceipt)?;
    crate::simulated::verify(
        &body.purchase_receipt,
        secret,
        "purchase",
        &account.device_key,
        Some("unlock_forever"),
    )
    .map_err(|_| SimulatedPurchaseError::InvalidReceipt)?;

    let token_hash = hash_token(body.purchase_receipt.as_bytes());
    let owns_purchase: bool = sqlx::query_scalar(
        "select exists(select 1 from purchases where token_hash = $1 and account_id = $2 and product_id = 'unlock_forever')",
    )
    .bind(&token_hash)
    .bind(account.id)
    .fetch_one(&state.pool)
    .await
    .map_err(|_| SimulatedPurchaseError::Internal)?;
    if !owns_purchase {
        return Err(SimulatedPurchaseError::InvalidReceipt);
    }

    if !voided::revoke_purchase(&state.pool, &token_hash).await {
        let status: Option<(String,)> = sqlx::query_as(
            "select status from purchases where token_hash = $1 and account_id = $2",
        )
        .bind(&token_hash)
        .bind(account.id)
        .fetch_optional(&state.pool)
        .await
        .map_err(|_| SimulatedPurchaseError::Internal)?;
        return match status {
            Some((status,)) if status == "voided" => Err(SimulatedPurchaseError::AlreadyRefunded),
            _ => Err(SimulatedPurchaseError::Internal),
        };
    }
    Ok(Json(SimulatedPurchaseResponse { status: "refunded" }))
}

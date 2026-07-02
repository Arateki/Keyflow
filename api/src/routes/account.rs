use axum::Json;
use axum::extract::State;
use axum::response::{IntoResponse, Response};
use serde::Serialize;

use crate::AppState;
use crate::db::accounts::Account;
use crate::middleware::ratelimit::RateLimitRejection;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountResponse {
    balance_seconds: i64,
    ads_off: bool,
    skins_unlocked: bool,
}

pub enum AccountError {
    RateLimited(RateLimitRejection),
}

impl From<RateLimitRejection> for AccountError {
    fn from(rejection: RateLimitRejection) -> Self {
        AccountError::RateLimited(rejection)
    }
}

impl IntoResponse for AccountError {
    fn into_response(self) -> Response {
        match self {
            AccountError::RateLimited(rejection) => rejection.into_response(),
        }
    }
}

/// `Account` (extractor) já resolveu o Bearer antes deste handler rodar — 401 sem/inválido
/// acontece antes de chegar aqui (design ID-01..03).
pub async fn get_account(
    State(state): State<AppState>,
    account: Account,
) -> Result<Json<AccountResponse>, AccountError> {
    state.rate_limiters.check_account(account.id)?;

    Ok(Json(AccountResponse {
        balance_seconds: account.balance_seconds,
        ads_off: account.ads_off,
        skins_unlocked: account.skins_unlocked,
    }))
}

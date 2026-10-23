use std::net::SocketAddr;

use axum::Json;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::generate_token;
use crate::db::accounts;
use crate::google::integrity::verify_verdicts;
use crate::middleware::client_ip::client_ip;
use crate::middleware::ratelimit::RateLimitRejection;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterRequest {
    device_key: String,
    integrity_token: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterResponse {
    access_token: String,
}

pub enum RegisterError {
    RateLimited(RateLimitRejection),
    Integrity,
    Internal,
}

impl From<RateLimitRejection> for RegisterError {
    fn from(rejection: RateLimitRejection) -> Self {
        RegisterError::RateLimited(rejection)
    }
}

impl IntoResponse for RegisterError {
    fn into_response(self) -> Response {
        match self {
            RegisterError::RateLimited(rejection) => rejection.into_response(),
            RegisterError::Integrity => (
                StatusCode::FORBIDDEN,
                Json(serde_json::json!({ "error": "integrity" })),
            )
                .into_response(),
            RegisterError::Internal => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    }
}

pub async fn register(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, RegisterError> {
    let cf_connecting_ip = headers
        .get("cf-connecting-ip")
        .and_then(|value| value.to_str().ok());
    let ip = client_ip(addr.ip(), cf_connecting_ip, &state.trusted_proxies);
    state.rate_limiters.check_register(ip)?;

    let payload = state
        .integrity
        .decode(&body.integrity_token)
        .await
        .map_err(|_| RegisterError::Integrity)?;

    verify_verdicts(&payload, &state.package_name, state.integrity_relaxed)
        .map_err(|_| RegisterError::Integrity)?;

    let (access_token, token_hash) = generate_token();

    let existing = accounts::find_by_device_key(&state.pool, &body.device_key)
        .await
        .map_err(|_| RegisterError::Internal)?;

    match existing {
        Some(account) => {
            accounts::rotate_token(&state.pool, account.id, &token_hash)
                .await
                .map_err(|_| RegisterError::Internal)?;
        }
        None => {
            accounts::create(&state.pool, &body.device_key, &token_hash)
                .await
                .map_err(|_| RegisterError::Internal)?;
        }
    }

    Ok(Json(RegisterResponse { access_token }))
}

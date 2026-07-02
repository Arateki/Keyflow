use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::generate_token;
use crate::db::accounts;
use crate::google::integrity::verify_verdicts;

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
    Integrity,
    Internal,
}

impl IntoResponse for RegisterError {
    fn into_response(self) -> Response {
        match self {
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
    Json(body): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, RegisterError> {
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

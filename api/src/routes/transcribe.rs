use std::error::Error as StdError;
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::StreamExt;
use serde::Serialize;

use crate::AppState;
use crate::db::accounts::Account;
use crate::db::ledger::{LedgerReason, credit};
use crate::middleware::ratelimit::RateLimitRejection;

/// `AudioRecorder` do app grava AAC CBR 64 kbps → `segundos = ceil(bytes / 8000)` (design
/// §Proxy). Autoritativo: o header opcional `X-Audio-Seconds` do app é só cross-check/telemetria.
const BYTES_PER_SECOND: u64 = 8000;

/// Erro synthetic yielded pelo stream contador quando o corpo passa do cap — nunca chega a sair
/// do processo; existe só pra reconhecermos essa causa específica na cadeia de `source()` do
/// `reqwest::Error` (distingue 413 de erro genérico de conexão/provedor).
#[derive(Debug)]
struct StreamCapExceeded;

impl fmt::Display for StreamCapExceeded {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "request body exceeded the configured cap")
    }
}

impl StdError for StreamCapExceeded {}

pub enum TranscribeError {
    RateLimited(RateLimitRejection),
    MissingRequestId,
    InsufficientBalance { balance_seconds: i64 },
    PayloadTooLarge { max_bytes: u64 },
    UpstreamTimeout,
    UpstreamError,
    Internal,
}

impl From<RateLimitRejection> for TranscribeError {
    fn from(rejection: RateLimitRejection) -> Self {
        TranscribeError::RateLimited(rejection)
    }
}

#[derive(Serialize)]
struct BalanceBody {
    #[serde(rename = "balanceSeconds")]
    balance_seconds: i64,
}

impl IntoResponse for TranscribeError {
    fn into_response(self) -> Response {
        match self {
            TranscribeError::RateLimited(rejection) => rejection.into_response(),
            TranscribeError::MissingRequestId => (
                StatusCode::BAD_REQUEST,
                axum::Json(serde_json::json!({ "error": "missing_request_id" })),
            )
                .into_response(),
            TranscribeError::InsufficientBalance { balance_seconds } => (
                StatusCode::PAYMENT_REQUIRED,
                axum::Json(BalanceBody { balance_seconds }),
            )
                .into_response(),
            TranscribeError::PayloadTooLarge { max_bytes } => (
                StatusCode::PAYLOAD_TOO_LARGE,
                axum::Json(
                    serde_json::json!({ "error": "payload_too_large", "maxBytes": max_bytes }),
                ),
            )
                .into_response(),
            TranscribeError::UpstreamTimeout => StatusCode::GATEWAY_TIMEOUT.into_response(),
            TranscribeError::UpstreamError => StatusCode::BAD_GATEWAY.into_response(),
            TranscribeError::Internal => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    }
}

fn is_cap_exceeded(err: &reqwest::Error) -> bool {
    let mut source: Option<&(dyn StdError + 'static)> = StdError::source(err);
    while let Some(e) = source {
        if e.downcast_ref::<StreamCapExceeded>().is_some() {
            return true;
        }
        source = e.source();
    }
    false
}

pub async fn transcribe(
    State(state): State<AppState>,
    account: Account,
    headers: HeaderMap,
    body: Body,
) -> Result<Response, TranscribeError> {
    state.rate_limiters.check_account(account.id)?;

    let request_id = headers
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
        .ok_or(TranscribeError::MissingRequestId)?;

    let already_known: bool = sqlx::query_scalar(
        "select exists(select 1 from transcribe_requests where account_id = $1 and request_id = $2)",
    )
    .bind(account.id)
    .bind(&request_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|_| TranscribeError::Internal)?;

    if !already_known && account.balance_seconds <= 0 {
        return Err(TranscribeError::InsufficientBalance {
            balance_seconds: account.balance_seconds,
        });
    }

    let content_type = headers.get(header::CONTENT_TYPE).cloned();

    let counted = Arc::new(AtomicU64::new(0));
    let counted_for_stream = counted.clone();
    let max_bytes = state.transcription_max_bytes;
    let data_stream = body.into_data_stream().map(move |chunk| {
        let chunk = chunk.map_err(|e| Box::new(e) as Box<dyn StdError + Send + Sync>)?;
        let total = counted_for_stream.fetch_add(chunk.len() as u64, Ordering::Relaxed)
            + chunk.len() as u64;
        if total > max_bytes {
            return Err(Box::new(StreamCapExceeded) as Box<dyn StdError + Send + Sync>);
        }
        Ok::<Bytes, Box<dyn StdError + Send + Sync>>(chunk)
    });
    let reqwest_body = reqwest::Body::wrap_stream(data_stream);

    let provider_index = state.provider_health.pick();
    let provider = &state.providers[provider_index];

    let mut request = state
        .http
        .post(&provider.url)
        .header(
            provider.auth_header_name.as_str(),
            provider.auth_value.as_str(),
        )
        .timeout(state.transcription_timeout)
        .body(reqwest_body);
    if let Some(content_type) = content_type {
        request = request.header(header::CONTENT_TYPE, content_type);
    }

    let response = match request.send().await {
        Ok(response) => response,
        Err(e) if is_cap_exceeded(&e) => {
            return Err(TranscribeError::PayloadTooLarge { max_bytes });
        }
        Err(e) if e.is_timeout() => return Err(TranscribeError::UpstreamTimeout),
        Err(_) => {
            state
                .provider_health
                .mark_unhealthy(provider_index, state.provider_unhealthy_cooldown);
            return Err(TranscribeError::UpstreamError);
        }
    };

    if response.status().is_server_error() {
        state
            .provider_health
            .mark_unhealthy(provider_index, state.provider_unhealthy_cooldown);
        return Err(TranscribeError::UpstreamError);
    }
    if !response.status().is_success() {
        return Err(TranscribeError::UpstreamError);
    }

    let provider_body = response
        .bytes()
        .await
        .map_err(|_| TranscribeError::UpstreamError)?;
    let total_bytes = counted.load(Ordering::Relaxed);
    let seconds = total_bytes.div_ceil(BYTES_PER_SECOND) as i64;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| TranscribeError::Internal)?;
    let inserted: Option<(i32,)> = sqlx::query_as(
        "insert into transcribe_requests (account_id, request_id, seconds) values ($1, $2, $3) \
         on conflict (account_id, request_id) do nothing \
         returning 1",
    )
    .bind(account.id)
    .bind(&request_id)
    .bind(seconds)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|_| TranscribeError::Internal)?;

    // Idempotência (edge de retry, design §Proxy): já processado antes → re-executa upstream (não
    // persistimos texto, ID-03) mas não debita de novo.
    if inserted.is_some() {
        credit(
            &mut tx,
            account.id,
            -seconds,
            LedgerReason::Transcription,
            Some(&request_id),
        )
        .await
        .map_err(|_| TranscribeError::Internal)?;
    }
    tx.commit().await.map_err(|_| TranscribeError::Internal)?;

    let fresh_balance: (i64,) =
        sqlx::query_as("select balance_seconds from accounts where id = $1")
            .bind(account.id)
            .fetch_one(&state.pool)
            .await
            .map_err(|_| TranscribeError::Internal)?;

    let mut response = (StatusCode::OK, provider_body).into_response();
    response.headers_mut().insert(
        "x-keyflow-balance-seconds",
        HeaderValue::from_str(&fresh_balance.0.to_string())
            .map_err(|_| TranscribeError::Internal)?,
    );
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bytes_per_second_rounds_up() {
        assert_eq!(0u64.div_ceil(BYTES_PER_SECOND), 0);
        assert_eq!(1u64.div_ceil(BYTES_PER_SECOND), 1);
        assert_eq!(8000u64.div_ceil(BYTES_PER_SECOND), 1);
        assert_eq!(8001u64.div_ceil(BYTES_PER_SECOND), 2);
        assert_eq!(80_000u64.div_ceil(BYTES_PER_SECOND), 10);
    }
}

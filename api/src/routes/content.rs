use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use sha2::{Digest, Sha256};

use crate::AppState;

const CACHE_CONTROL: &str = "max-age=300";

pub async fn get_config(state: State<AppState>, headers: HeaderMap) -> Response {
    serve_document(state, headers, "config").await
}

pub async fn get_models(state: State<AppState>, headers: HeaderMap) -> Response {
    serve_document(state, headers, "models").await
}

async fn serve_document(State(state): State<AppState>, headers: HeaderMap, key: &str) -> Response {
    let body: Option<(serde_json::Value,)> =
        match sqlx::query_as("select body from documents where key = $1")
            .bind(key)
            .fetch_optional(&state.pool)
            .await
        {
            Ok(row) => row,
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        };

    let Some((body,)) = body else {
        return StatusCode::NOT_FOUND.into_response();
    };

    // Serializado uma única vez: os mesmos bytes viram tanto o corpo quanto a base do ETag —
    // garante que o header sempre corresponde ao que foi de fato enviado.
    let bytes = match serde_json::to_vec(&body) {
        Ok(bytes) => bytes,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let etag = etag_for(&bytes);

    if if_none_match_matches(&headers, &etag) {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        set_cache_headers(response.headers_mut(), &etag);
        return response;
    }

    let mut response = (StatusCode::OK, bytes).into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    set_cache_headers(response.headers_mut(), &etag);
    response
}

fn etag_for(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    format!("\"{hex}\"")
}

fn set_cache_headers(headers: &mut HeaderMap, etag: &str) {
    if let Ok(value) = HeaderValue::from_str(etag) {
        headers.insert(header::ETAG, value);
    }
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static(CACHE_CONTROL),
    );
}

fn if_none_match_matches(headers: &HeaderMap, etag: &str) -> bool {
    let Some(value) = headers
        .get(header::IF_NONE_MATCH)
        .and_then(|v| v.to_str().ok())
    else {
        return false;
    };
    value
        .split(',')
        .map(str::trim)
        .any(|candidate| candidate == "*" || candidate == etag)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn etag_is_stable_sha256_hex_wrapped_in_quotes() {
        let etag = etag_for(b"hello");
        // SHA-256("hello") — valor bem conhecido, não fabricado.
        assert_eq!(
            etag,
            "\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\""
        );
    }

    #[test]
    fn etag_changes_with_content() {
        assert_ne!(etag_for(b"a"), etag_for(b"b"));
    }

    #[test]
    fn if_none_match_matches_exact_etag() {
        let mut headers = HeaderMap::new();
        headers.insert(header::IF_NONE_MATCH, HeaderValue::from_static("\"abc\""));
        assert!(if_none_match_matches(&headers, "\"abc\""));
        assert!(!if_none_match_matches(&headers, "\"xyz\""));
    }

    #[test]
    fn if_none_match_wildcard_always_matches() {
        let mut headers = HeaderMap::new();
        headers.insert(header::IF_NONE_MATCH, HeaderValue::from_static("*"));
        assert!(if_none_match_matches(&headers, "\"anything\""));
    }

    #[test]
    fn missing_if_none_match_never_matches() {
        assert!(!if_none_match_matches(&HeaderMap::new(), "\"abc\""));
    }
}

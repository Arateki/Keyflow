use axum::extract::{FromRequestParts, State};
use axum::http::request::Parts;
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::db::accounts::Account;

/// Gera um novo access token: 32 bytes aleatórios (devolvidos ao cliente em base64url) + seu
/// hash SHA-256 (o único valor persistido — design.md §Identidade).
pub fn generate_token() -> (String, Vec<u8>) {
    let mut bytes = [0u8; 32];
    rand::fill(&mut bytes);
    let token = URL_SAFE_NO_PAD.encode(bytes);
    let hash = hash_token(token.as_bytes());
    (token, hash)
}

pub fn hash_token(token: &[u8]) -> Vec<u8> {
    Sha256::digest(token).to_vec()
}

#[derive(Debug)]
pub enum AuthRejection {
    Missing,
    Invalid,
}

impl IntoResponse for AuthRejection {
    fn into_response(self) -> Response {
        StatusCode::UNAUTHORIZED.into_response()
    }
}

impl FromRequestParts<AppState> for Account {
    type Rejection = AuthRejection;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let State(state) = State::<AppState>::from_request_parts(parts, state)
            .await
            .map_err(|_| AuthRejection::Missing)?;

        let raw = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or(AuthRejection::Missing)?;
        let token = raw.strip_prefix("Bearer ").ok_or(AuthRejection::Missing)?;

        let hash = hash_token(token.as_bytes());
        crate::db::accounts::find_by_token_hash(&state.pool, &hash)
            .await
            .map_err(|_| AuthRejection::Invalid)?
            .ok_or(AuthRejection::Invalid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_token_returns_matching_hash() {
        let (token, hash) = generate_token();

        assert_eq!(
            token.len(),
            43,
            "32 bytes em base64url sem padding têm 43 chars"
        );
        assert_eq!(hash, hash_token(token.as_bytes()));
    }

    #[test]
    fn generate_token_is_random() {
        let (token1, _) = generate_token();
        let (token2, _) = generate_token();
        assert_ne!(token1, token2);
    }

    #[test]
    fn hash_token_is_sha256() {
        // Vetor de teste: SHA-256("") — valor bem conhecido, não fabricado.
        let hash = hash_token(b"");
        assert_eq!(
            hash,
            hex_decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        );
    }

    fn hex_decode(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .filter_map(|i| s.get(i..i + 2))
            .map(|b| u8::from_str_radix(b, 16).unwrap())
            .collect()
    }
}

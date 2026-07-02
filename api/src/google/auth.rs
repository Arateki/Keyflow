use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use serde::{Deserialize, Serialize};

/// Margem de segurança: renova o token um pouco antes de expirar de fato, para não devolver um
/// token que expire no meio de uma requisição em voo.
const EXPIRY_SKEW_SECONDS: u64 = 30;
const JWT_LIFETIME_SECONDS: u64 = 3600;

/// Formato padrão do JSON de chave de service account do Google Cloud (campos que usamos).
#[derive(Debug, Clone, Deserialize)]
pub struct ServiceAccountKey {
    pub client_email: String,
    pub private_key: String,
    pub token_uri: String,
}

impl ServiceAccountKey {
    pub fn from_json_file(path: &str) -> std::io::Result<Self> {
        let raw = std::fs::read_to_string(path)?;
        serde_json::from_str(&raw)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
    }
}

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("failed to sign JWT: {0}")]
    Jwt(#[from] jsonwebtoken::errors::Error),
    #[error("token request failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("token endpoint returned status {0}")]
    TokenRequestFailed(u16),
}

#[derive(Serialize)]
struct Claims<'a> {
    iss: &'a str,
    scope: &'a str,
    aud: &'a str,
    exp: u64,
    iat: u64,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: u64,
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock before unix epoch")
        .as_secs()
}

/// Token OAuth de service account, cacheado até perto de expirar (RFC do fluxo JWT Bearer:
/// developers.google.com/identity/protocols/oauth2/service-account).
pub struct TokenCache {
    cached: Mutex<Option<(String, u64)>>,
}

impl Default for TokenCache {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenCache {
    pub fn new() -> Self {
        Self {
            cached: Mutex::new(None),
        }
    }

    pub async fn get_token(
        &self,
        http: &reqwest::Client,
        key: &ServiceAccountKey,
        scope: &str,
    ) -> Result<String, AuthError> {
        let now = now_unix();

        if let Some((token, expires_at)) = self.cached.lock().unwrap().as_ref()
            && *expires_at > now + EXPIRY_SKEW_SECONDS
        {
            return Ok(token.clone());
        }

        let (token, expires_in) = fetch_token(http, key, scope).await?;
        let expires_at = now + expires_in;
        *self.cached.lock().unwrap() = Some((token.clone(), expires_at));
        Ok(token)
    }
}

async fn fetch_token(
    http: &reqwest::Client,
    key: &ServiceAccountKey,
    scope: &str,
) -> Result<(String, u64), AuthError> {
    let now = now_unix();
    let claims = Claims {
        iss: &key.client_email,
        scope,
        aud: &key.token_uri,
        exp: now + JWT_LIFETIME_SECONDS,
        iat: now,
    };
    let encoding_key = EncodingKey::from_rsa_pem(key.private_key.as_bytes())?;
    let jwt = encode(&Header::new(Algorithm::RS256), &claims, &encoding_key)?;

    let response = http
        .post(&key.token_uri)
        .form(&[
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", jwt.as_str()),
        ])
        .send()
        .await?;

    if !response.status().is_success() {
        return Err(AuthError::TokenRequestFailed(response.status().as_u16()));
    }

    let body: TokenResponse = response.json().await?;
    Ok((body.access_token, body.expires_in))
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_string_contains, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn test_key(token_uri: String) -> ServiceAccountKey {
        ServiceAccountKey {
            client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
            private_key: include_str!("../../tests/fixtures/test_service_account_key.pem")
                .to_string(),
            token_uri,
        }
    }

    #[tokio::test]
    async fn fetches_and_caches_token_until_near_expiry() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .and(body_string_contains(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "fake-token-1",
                "scope": "https://www.googleapis.com/auth/playintegrity",
                "token_type": "Bearer",
                "expires_in": 3600
            })))
            .expect(1)
            .mount(&server)
            .await;

        let key = test_key(format!("{}/token", server.uri()));
        let http = reqwest::Client::new();
        let cache = TokenCache::new();

        let token1 = cache
            .get_token(&http, &key, "https://www.googleapis.com/auth/playintegrity")
            .await
            .unwrap();
        let token2 = cache
            .get_token(&http, &key, "https://www.googleapis.com/auth/playintegrity")
            .await
            .unwrap();

        assert_eq!(token1, "fake-token-1");
        assert_eq!(token2, "fake-token-1");
        // `.expect(1)` no mock: a 2ª chamada teria falhado o mock se tivesse batido de novo no
        // servidor — prova o cache.
    }

    #[tokio::test]
    async fn refetches_token_after_expiry() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "fake-token-short",
                "token_type": "Bearer",
                "expires_in": 1
            })))
            .up_to_n_times(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "fake-token-renewed",
                "token_type": "Bearer",
                "expires_in": 3600
            })))
            .mount(&server)
            .await;

        let key = test_key(format!("{}/token", server.uri()));
        let http = reqwest::Client::new();
        let cache = TokenCache::new();

        let token1 = cache.get_token(&http, &key, "scope").await.unwrap();
        // expires_in=1 já está dentro da margem de segurança (30s) — a próxima chamada renova.
        let token2 = cache.get_token(&http, &key, "scope").await.unwrap();

        assert_eq!(token1, "fake-token-short");
        assert_eq!(token2, "fake-token-renewed");
    }

    #[tokio::test]
    async fn token_endpoint_error_status_is_reported() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;

        let key = test_key(format!("{}/token", server.uri()));
        let http = reqwest::Client::new();
        let cache = TokenCache::new();

        let err = cache.get_token(&http, &key, "scope").await.unwrap_err();

        assert!(matches!(err, AuthError::TokenRequestFailed(401)));
    }
}

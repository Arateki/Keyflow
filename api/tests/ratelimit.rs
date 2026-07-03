mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::{test_pool, unused_ads_config, unused_transcription_config};
use keyflow_api::db::accounts::Account;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AppState, app};
use serde_json::json;
use std::net::SocketAddr;
use tower::ServiceExt;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PACKAGE_NAME: &str = "com.keyflow";

async fn mock_google_ok() -> MockServer {
    let google = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "fake-access-token",
            "token_type": "Bearer",
            "expires_in": 3600
        })))
        .mount(&google)
        .await;

    Mock::given(method("POST"))
        .and(path(format!("/v1/{PACKAGE_NAME}:decodeIntegrityToken")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "tokenPayloadExternal": {
                "requestDetails": { "requestPackageName": PACKAGE_NAME },
                "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED" },
                "deviceIntegrity": { "deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"] }
            }
        })))
        .mount(&google)
        .await;

    google
}

async fn state_with_limits(
    google_uri: String,
    register_per_minute: u32,
    account_per_minute: u32,
) -> AppState {
    let pool = test_pool().await;
    let key = ServiceAccountKey {
        client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
        private_key: include_str!("fixtures/test_service_account_key.pem").to_string(),
        token_uri: format!("{google_uri}/token"),
    };
    let integrity = IntegrityClient::new(
        reqwest::Client::new(),
        google_uri.clone(),
        PACKAGE_NAME.to_string(),
        key.clone(),
    );
    let publisher = PublisherClient::new(
        reqwest::Client::new(),
        google_uri,
        PACKAGE_NAME.to_string(),
        key,
    );
    AppState::new(
        pool,
        integrity,
        publisher,
        reqwest::Client::new(),
        PACKAGE_NAME.to_string(),
        false,
        std::num::NonZeroU32::new(register_per_minute).unwrap(),
        std::num::NonZeroU32::new(account_per_minute).unwrap(),
        unused_transcription_config(),
        unused_ads_config(),
    )
}

async fn register_once(state: AppState, addr: SocketAddr) -> axum::http::Response<Body> {
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    app(state)
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/register")
                .header(header::CONTENT_TYPE, "application/json")
                .extension(axum::extract::ConnectInfo(addr))
                .body(Body::from(
                    json!({ "deviceKey": device_key, "integrityToken": "fake-integrity-token" })
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap()
}

#[tokio::test]
async fn register_flood_from_same_ip_gets_429_with_retry_after_header() {
    let google = mock_google_ok().await;
    // Quota 1/min: a 1ª passa, a 2ª (mesmo IP, mesmo minuto) estoura.
    let state = state_with_limits(google.uri(), 1, 1000).await;
    let addr: SocketAddr = "203.0.113.7:0".parse().unwrap();

    let first = register_once(state.clone(), addr).await;
    assert_eq!(first.status(), StatusCode::OK);

    let second = register_once(state, addr).await;
    assert_eq!(second.status(), StatusCode::TOO_MANY_REQUESTS);
    let retry_after = second
        .headers()
        .get("retry-after")
        .expect("header Retry-After presente")
        .to_str()
        .unwrap()
        .parse::<u64>()
        .unwrap();
    assert!(retry_after >= 1);
}

#[tokio::test]
async fn register_below_quota_from_different_ips_is_not_limited() {
    let google = mock_google_ok().await;
    let state = state_with_limits(google.uri(), 1, 1000).await;
    let addr_a: SocketAddr = "203.0.113.10:0".parse().unwrap();
    let addr_b: SocketAddr = "203.0.113.11:0".parse().unwrap();

    let a = register_once(state.clone(), addr_a).await;
    let b = register_once(state, addr_b).await;

    assert_eq!(a.status(), StatusCode::OK);
    assert_eq!(
        b.status(),
        StatusCode::OK,
        "IP diferente não deveria ser afetado pela quota do outro"
    );
}

/// "Por conta" ainda não tem rota de negócio autenticada real (`GET /v1/account` é a API-T7);
/// prova a mesma checagem (`AppState::rate_limiters.check_account`) batendo num router efêmero
/// com o extractor `Account` real sobre o `AppState` real — mesmo padrão usado em
/// `tests/register.rs::missing_bearer_is_rejected_with_401` para o Bearer.
#[tokio::test]
async fn account_flood_gets_429_with_retry_after_header() {
    let google = mock_google_ok().await;
    let state = state_with_limits(google.uri(), 1000, 1).await;
    let addr: SocketAddr = "203.0.113.20:0".parse().unwrap();

    let response = register_once(state.clone(), addr).await;
    assert_eq!(response.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let access_token = body["accessToken"].as_str().unwrap().to_string();

    use axum::routing::get;
    async fn probe_handler(
        axum::extract::State(state): axum::extract::State<AppState>,
        account: Account,
    ) -> Result<StatusCode, keyflow_api::middleware::ratelimit::RateLimitRejection> {
        state.rate_limiters.check_account(account.id)?;
        Ok(StatusCode::OK)
    }
    let probe = axum::Router::new()
        .route("/probe", get(probe_handler))
        .with_state(state);

    let bearer = format!("Bearer {access_token}");
    let request = |probe: axum::Router| {
        probe.oneshot(
            Request::builder()
                .uri("/probe")
                .header(header::AUTHORIZATION, &bearer)
                .body(Body::empty())
                .unwrap(),
        )
    };

    let first = request(probe.clone()).await.unwrap();
    assert_eq!(first.status(), StatusCode::OK);

    let second = request(probe).await.unwrap();
    assert_eq!(second.status(), StatusCode::TOO_MANY_REQUESTS);
    let retry_after = second
        .headers()
        .get("retry-after")
        .expect("header Retry-After presente")
        .to_str()
        .unwrap()
        .parse::<u64>()
        .unwrap();
    assert!(retry_after >= 1);
}

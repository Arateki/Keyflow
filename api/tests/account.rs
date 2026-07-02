mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::test_pool;
use keyflow_api::app;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AppState, auth::generate_token, db::accounts};
use serde_json::Value;
use tower::ServiceExt;

const PACKAGE_NAME: &str = "com.keyflow";

async fn test_state() -> AppState {
    let pool = test_pool().await;
    // Nenhum teste deste arquivo bate no Google (só GET /v1/account, autenticado por Bearer já
    // emitido direto no banco).
    let unused_key = ServiceAccountKey {
        client_email: "unused@example.com".to_string(),
        private_key: String::new(),
        token_uri: "http://127.0.0.1:0/token".to_string(),
    };
    let integrity = IntegrityClient::new(
        reqwest::Client::new(),
        "http://127.0.0.1:0".to_string(),
        PACKAGE_NAME.to_string(),
        unused_key.clone(),
    );
    let publisher = PublisherClient::new(
        reqwest::Client::new(),
        "http://127.0.0.1:0".to_string(),
        PACKAGE_NAME.to_string(),
        unused_key,
    );
    AppState::new(
        pool,
        integrity,
        publisher,
        PACKAGE_NAME.to_string(),
        false,
        std::num::NonZeroU32::new(1000).unwrap(),
        std::num::NonZeroU32::new(1000).unwrap(),
    )
}

async fn get_account(state: AppState, bearer: Option<&str>) -> (StatusCode, Value) {
    let mut builder = Request::builder().uri("/v1/account");
    if let Some(token) = bearer {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let response = app(state)
        .oneshot(builder.body(Body::empty()).unwrap())
        .await
        .unwrap();

    let status = response.status();
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let body: Value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap()
    };
    (status, body)
}

#[tokio::test]
async fn new_account_has_zero_balance_and_no_entitlements() {
    let state = test_state().await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let (access_token, token_hash) = generate_token();
    accounts::create(&state.pool, &device_key, &token_hash)
        .await
        .unwrap();

    let (status, body) = get_account(state, Some(&access_token)).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["balanceSeconds"], 0);
    assert_eq!(body["adsOff"], false);
    assert_eq!(body["skinsUnlocked"], false);
}

#[tokio::test]
async fn account_after_ledger_and_entitlements_seed_reflects_real_values() {
    let state = test_state().await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let (access_token, token_hash) = generate_token();
    let account = accounts::create(&state.pool, &device_key, &token_hash)
        .await
        .unwrap();

    // Seed direto via SQL: crédito de compra + entitlements ligados (equivalente ao que a T8 vai
    // automatizar; aqui só provamos que GET /v1/account lê o estado real da conta).
    sqlx::query("update accounts set balance_seconds = 1200, ads_off = true, skins_unlocked = true where id = $1")
        .bind(account.id)
        .execute(&state.pool)
        .await
        .unwrap();

    let (status, body) = get_account(state, Some(&access_token)).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["balanceSeconds"], 1200);
    assert_eq!(body["adsOff"], true);
    assert_eq!(body["skinsUnlocked"], true);
}

#[tokio::test]
async fn missing_bearer_returns_401() {
    let state = test_state().await;

    let (status, _) = get_account(state, None).await;

    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn invalid_bearer_returns_401() {
    let state = test_state().await;

    let (status, _) = get_account(state, Some("not-a-real-token")).await;

    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

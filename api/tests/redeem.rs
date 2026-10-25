mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::{test_pool, unused_ads_config, unused_transcription_config};
use keyflow_api::auth::{device_key_account_id, generate_token};
use keyflow_api::db::accounts;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AppState, app};
use serde_json::{Value, json};
use tower::ServiceExt;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PACKAGE_NAME: &str = "com.arateki.keyflow";

async fn mock_integrity_ok(google: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "fake-access-token",
            "token_type": "Bearer",
            "expires_in": 3600
        })))
        .mount(google)
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
        .mount(google)
        .await;
}

async fn mock_purchase(
    google: &MockServer,
    product_id: &str,
    token: &str,
    purchase_state: i32,
    obfuscated_account_id: &str,
) {
    Mock::given(method("GET"))
        .and(path(format!(
            "/androidpublisher/v3/applications/{PACKAGE_NAME}/purchases/products/{product_id}/tokens/{token}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "purchaseState": purchase_state,
            "obfuscatedExternalAccountId": obfuscated_account_id
        })))
        .mount(google)
        .await;
}

async fn mock_acknowledge_expect_n(google: &MockServer, product_id: &str, token: &str, n: u64) {
    Mock::given(method("POST"))
        .and(path(format!(
            "/androidpublisher/v3/applications/{PACKAGE_NAME}/purchases/products/{product_id}/tokens/{token}:acknowledge"
        )))
        .respond_with(ResponseTemplate::new(200))
        .expect(n)
        .mount(google)
        .await;
}

async fn state_with_google(google_uri: String) -> AppState {
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
        std::num::NonZeroU32::new(1000).unwrap(),
        std::num::NonZeroU32::new(1000).unwrap(),
        unused_transcription_config(),
        unused_ads_config(),
    )
}

async fn create_account(state: &AppState) -> (String, uuid::Uuid, String) {
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let (access_token, token_hash) = generate_token();
    let account = accounts::create(&state.pool, &device_key, &token_hash)
        .await
        .unwrap();
    (access_token, account.id, device_key)
}

async fn redeem_request(
    state: AppState,
    bearer: &str,
    product_id: &str,
    purchase_token: &str,
) -> (StatusCode, Value) {
    let response = app(state)
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/purchases/redeem")
                .header(header::CONTENT_TYPE, "application/json")
                .header(header::AUTHORIZATION, format!("Bearer {bearer}"))
                .body(Body::from(
                    json!({
                        "productId": product_id,
                        "purchaseToken": purchase_token,
                        "integrityToken": "fake-integrity-token"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
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
async fn valid_purchase_credits_once_and_acknowledges() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, device_key) = create_account(&state).await;
    let purchase_token = format!("tok-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "credits_20",
        &purchase_token,
        keyflow_api::google::publisher::PURCHASE_STATE_PURCHASED,
        &device_key_account_id(&device_key),
    )
    .await;
    mock_acknowledge_expect_n(&google, "credits_20", &purchase_token, 1).await;

    let (status, body) =
        redeem_request(state.clone(), &bearer, "credits_20", &purchase_token).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "credited");

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 25 * 60);
    assert!(
        account.ads_off,
        "credits_20 (>= R$20) deve ligar entitlements"
    );
    assert!(account.skins_unlocked);
}

#[tokio::test]
async fn credits_10_does_not_grant_entitlements() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, device_key) = create_account(&state).await;
    let purchase_token = format!("tok-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "credits_10",
        &purchase_token,
        keyflow_api::google::publisher::PURCHASE_STATE_PURCHASED,
        &device_key_account_id(&device_key),
    )
    .await;
    mock_acknowledge_expect_n(&google, "credits_10", &purchase_token, 1).await;

    let (status, body) =
        redeem_request(state.clone(), &bearer, "credits_10", &purchase_token).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "credited");

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 10 * 60);
    assert!(
        !account.ads_off,
        "credits_10 (< R$20) não deve ligar entitlements"
    );
    assert!(!account.skins_unlocked);
}

#[tokio::test]
async fn replay_of_same_token_does_not_credit_twice() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, device_key) = create_account(&state).await;
    let purchase_token = format!("tok-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "credits_10",
        &purchase_token,
        keyflow_api::google::publisher::PURCHASE_STATE_PURCHASED,
        &device_key_account_id(&device_key),
    )
    .await;
    // Só 1 acknowledge esperado — a 2ª chamada (replay) não deve tentar de novo.
    mock_acknowledge_expect_n(&google, "credits_10", &purchase_token, 1).await;

    let (status1, body1) =
        redeem_request(state.clone(), &bearer, "credits_10", &purchase_token).await;
    let (status2, body2) =
        redeem_request(state.clone(), &bearer, "credits_10", &purchase_token).await;

    assert_eq!(status1, StatusCode::OK);
    assert_eq!(status2, StatusCode::OK);
    assert_eq!(body1["status"], "credited");
    assert_eq!(body2["status"], "credited");

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        account.balance_seconds,
        10 * 60,
        "replay não deve creditar de novo"
    );
}

#[tokio::test]
async fn pending_purchase_returns_pending_without_crediting() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, device_key) = create_account(&state).await;
    let purchase_token = format!("tok-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "credits_10",
        &purchase_token,
        keyflow_api::google::publisher::PURCHASE_STATE_PENDING,
        &device_key_account_id(&device_key),
    )
    .await;

    let (status, body) =
        redeem_request(state.clone(), &bearer, "credits_10", &purchase_token).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "pending");

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 0);
}

#[tokio::test]
async fn wrong_account_id_is_rejected() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, _device_key) = create_account(&state).await;
    let purchase_token = format!("tok-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "credits_10",
        &purchase_token,
        keyflow_api::google::publisher::PURCHASE_STATE_PURCHASED,
        "conta-de-outro-device-completamente-diferente",
    )
    .await;

    let (status, body) = redeem_request(state, &bearer, "credits_10", &purchase_token).await;

    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "account_mismatch");
}

#[tokio::test]
async fn unknown_product_is_rejected() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (bearer, _account_id, _device_key) = create_account(&state).await;

    let (status, body) = redeem_request(state, &bearer, "does_not_exist", "tok-anything").await;

    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "unknown_product");
}

#[tokio::test]
async fn deleting_then_registering_again_can_restore_the_same_play_unlock() {
    let google = MockServer::start().await;
    mock_integrity_ok(&google).await;
    let state = state_with_google(google.uri()).await;
    let (token, old_id, device_key) = create_account(&state).await;
    let purchase_token = format!("restore-{}", uuid::Uuid::new_v4());
    mock_purchase(
        &google,
        "unlock_forever",
        &purchase_token,
        0,
        &device_key_account_id(&device_key),
    )
    .await;
    mock_acknowledge_expect_n(&google, "unlock_forever", &purchase_token, 2).await;
    assert_eq!(
        redeem_request(state.clone(), &token, "unlock_forever", &purchase_token)
            .await
            .0,
        StatusCode::OK
    );
    let response = app(state.clone())
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/v1/account")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert!(!accounts::exists(&state.pool, old_id).await.unwrap());
    let response = app(state.clone())
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/register")
                .extension(axum::extract::ConnectInfo(
                    "127.0.0.1:0".parse::<std::net::SocketAddr>().unwrap(),
                ))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({"deviceKey": device_key, "integrityToken": "fake-integrity-token"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    let new_token = body["accessToken"].as_str().unwrap();
    assert_ne!(new_token, token);
    let fresh = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_ne!(fresh.id, old_id);
    assert!(!fresh.skins_unlocked);
    assert_eq!(
        redeem_request(state.clone(), new_token, "unlock_forever", &purchase_token)
            .await
            .0,
        StatusCode::OK
    );
    let restored = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert!(restored.ads_off && restored.skins_unlocked);
    assert_eq!(restored.balance_seconds, 0);
}

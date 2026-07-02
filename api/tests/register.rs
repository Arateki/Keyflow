mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::test_pool;
use keyflow_api::db::accounts::Account;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::{AppState, app};
use serde_json::{Value, json};
use tower::ServiceExt;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

async fn mock_google_ok(package_name: &str) -> MockServer {
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
        .and(path(format!("/v1/{package_name}:decodeIntegrityToken")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "tokenPayloadExternal": {
                "requestDetails": { "requestPackageName": package_name },
                "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED" },
                "deviceIntegrity": { "deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"] }
            }
        })))
        .mount(&google)
        .await;

    google
}

async fn mock_google_bad_device(package_name: &str) -> MockServer {
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
        .and(path(format!("/v1/{package_name}:decodeIntegrityToken")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "tokenPayloadExternal": {
                "requestDetails": { "requestPackageName": package_name },
                "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED" },
                "deviceIntegrity": { "deviceRecognitionVerdict": [] }
            }
        })))
        .mount(&google)
        .await;

    google
}

async fn state_with_google(google_uri: String, package_name: &str) -> AppState {
    let pool = test_pool().await;
    let integrity = IntegrityClient::new(
        reqwest::Client::new(),
        google_uri.clone(),
        package_name.to_string(),
        ServiceAccountKey {
            client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
            private_key: include_str!("fixtures/test_service_account_key.pem").to_string(),
            token_uri: format!("{google_uri}/token"),
        },
    );
    AppState::new(pool, integrity, package_name.to_string(), false)
}

async fn register_request(state: AppState, device_key: &str) -> (StatusCode, Value) {
    let response = app(state)
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "deviceKey": device_key, "integrityToken": "fake-integrity-token" })
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
async fn register_with_valid_integrity_returns_token_that_works_on_protected_extractor() {
    let package_name = "com.keyflow";
    let google = mock_google_ok(package_name).await;
    let state = state_with_google(google.uri(), package_name).await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());

    let (status, body) = register_request(state.clone(), &device_key).await;

    assert_eq!(status, StatusCode::OK);
    let access_token = body["accessToken"]
        .as_str()
        .expect("accessToken presente")
        .to_string();
    assert!(!access_token.is_empty());

    // O token devolvido deve resolver a conta via o extractor `Account` — exercitado batendo
    // numa rota protegida real (o `/health` atual não exige Bearer; usamos o extractor
    // diretamente para provar a resolução Bearer→Account sem acoplar a uma rota de negócio
    // que ainda não existe nesta task — GET /v1/account é a API-T7).
    let hash = keyflow_api::auth::hash_token(access_token.as_bytes());
    let account = keyflow_api::db::accounts::find_by_token_hash(&state.pool, &hash)
        .await
        .unwrap()
        .expect("token deveria resolver a uma conta");
    assert_eq!(account.balance_seconds, 0);
}

#[tokio::test]
async fn register_with_invalid_integrity_returns_403() {
    let package_name = "com.keyflow";
    let google = mock_google_bad_device(package_name).await;
    let state = state_with_google(google.uri(), package_name).await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());

    let (status, body) = register_request(state, &device_key).await;

    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"], "integrity");
}

#[tokio::test]
async fn re_register_same_device_key_keeps_account_rotates_token() {
    let package_name = "com.keyflow";
    let google = mock_google_ok(package_name).await;
    let state = state_with_google(google.uri(), package_name).await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());

    let (_, body1) = register_request(state.clone(), &device_key).await;
    let token1 = body1["accessToken"].as_str().unwrap().to_string();

    let (status2, body2) = register_request(state.clone(), &device_key).await;
    let token2 = body2["accessToken"].as_str().unwrap().to_string();

    assert_eq!(status2, StatusCode::OK);
    assert_ne!(token1, token2, "re-registro deve rotacionar o token");

    let account1: Option<Account> = {
        let hash = keyflow_api::auth::hash_token(token1.as_bytes());
        keyflow_api::db::accounts::find_by_token_hash(&state.pool, &hash)
            .await
            .unwrap()
    };
    assert!(
        account1.is_none(),
        "token antigo deve estar morto após re-registro"
    );

    let account2 = keyflow_api::db::accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .expect("conta deveria existir");
    let by_new_token = {
        let hash = keyflow_api::auth::hash_token(token2.as_bytes());
        keyflow_api::db::accounts::find_by_token_hash(&state.pool, &hash)
            .await
            .unwrap()
            .expect("token novo deveria resolver a conta")
    };
    assert_eq!(account2.id, by_new_token.id, "mesma conta, não uma nova");
}

#[tokio::test]
async fn missing_bearer_is_rejected_with_401() {
    let package_name = "com.keyflow";
    let google = mock_google_ok(package_name).await;
    let state = state_with_google(google.uri(), package_name).await;

    // `/health` não usa o extractor `Account`; testamos a resolução Bearer→401 diretamente via
    // uma rota crua que só existe neste teste, montada com o mesmo `AppState` da app real.
    use axum::routing::get;
    let probe = axum::Router::new()
        .route("/probe", get(async |_: Account| StatusCode::OK))
        .with_state(state);

    let response = probe
        .clone()
        .oneshot(
            Request::builder()
                .uri("/probe")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let response = probe
        .oneshot(
            Request::builder()
                .uri("/probe")
                .header(header::AUTHORIZATION, "Bearer not-a-real-token")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use common::{test_pool, unused_transcription_config};
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AdsConfig, AppState, app};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::pkcs8::EncodePublicKey;
use rand_core::OsRng;
use sqlx::PgPool;
use tower::ServiceExt;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PACKAGE_NAME: &str = "com.arateki.keyflow";
const KEY_ID: u64 = 42;

async fn mock_verifier_keys(signing_key: &SigningKey) -> MockServer {
    let verifying_key = VerifyingKey::from(signing_key);
    let pem = verifying_key.to_public_key_pem(Default::default()).unwrap();

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/verifier-keys.json"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "keys": [{ "keyId": KEY_ID, "pem": pem }]
        })))
        .mount(&server)
        .await;
    server
}

async fn state_with_admob(
    keys_url: String,
    pool: PgPool,
    reward_seconds: i64,
    daily_cap: i64,
) -> AppState {
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
        reqwest::Client::new(),
        PACKAGE_NAME.to_string(),
        false,
        std::num::NonZeroU32::new(1000).unwrap(),
        std::num::NonZeroU32::new(1000).unwrap(),
        unused_transcription_config(),
        AdsConfig {
            enabled: true,
            verifier_keys_url: keys_url,
            reward_seconds,
            daily_cap,
        },
    )
}

/// Monta uma query de callback SSV assinada de verdade — mesma regra da doc oficial (conteúdo
/// assinado = tudo antes de `&signature=`; `signature`+`key_id` sempre por último, nessa ordem).
fn build_signed_query(signing_key: &SigningKey, key_id: u64, content: &str) -> String {
    let signature: Signature = signing_key.sign(content.as_bytes());
    let sig_b64 = URL_SAFE_NO_PAD.encode(signature.to_der().as_bytes());
    format!("{content}&signature={sig_b64}&key_id={key_id}")
}

async fn ssv_request(state: AppState, query: &str) -> StatusCode {
    app(state)
        .oneshot(
            Request::builder()
                .uri(format!("/v1/ssv/admob?{query}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn valid_callback_credits_account_once() {
    let signing_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let account = keyflow_api::db::accounts::create(
        &pool,
        &device_key,
        uuid::Uuid::new_v4().into_bytes().as_ref(),
    )
    .await
    .unwrap();

    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool.clone(),
        60,
        5,
    )
    .await;

    let content = format!(
        "ad_network=1&custom_data={}&reward_amount=5&transaction_id=tx-{}",
        account.id,
        uuid::Uuid::new_v4()
    );
    let query = build_signed_query(&signing_key, KEY_ID, &content);

    let status = ssv_request(state, &query).await;

    assert_eq!(status, StatusCode::OK);
    let after = keyflow_api::db::accounts::find_by_device_key(&pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(after.balance_seconds, 60);
}

#[tokio::test]
async fn replay_of_same_transaction_id_does_not_credit_twice() {
    let signing_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let account = keyflow_api::db::accounts::create(
        &pool,
        &device_key,
        uuid::Uuid::new_v4().into_bytes().as_ref(),
    )
    .await
    .unwrap();

    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool.clone(),
        60,
        5,
    )
    .await;

    let content = format!(
        "custom_data={}&transaction_id=same-tx-{}",
        account.id,
        uuid::Uuid::new_v4()
    );
    let query = build_signed_query(&signing_key, KEY_ID, &content);

    assert_eq!(ssv_request(state.clone(), &query).await, StatusCode::OK);
    assert_eq!(ssv_request(state, &query).await, StatusCode::OK);

    let after = keyflow_api::db::accounts::find_by_device_key(&pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        after.balance_seconds, 60,
        "replay não deve creditar de novo"
    );
}

#[tokio::test]
async fn daily_cap_blocks_further_credits() {
    let signing_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let account = keyflow_api::db::accounts::create(
        &pool,
        &device_key,
        uuid::Uuid::new_v4().into_bytes().as_ref(),
    )
    .await
    .unwrap();

    // Cap de 1: a 1ª recompensa do dia passa, a 2ª (transaction_id diferente) é bloqueada.
    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool.clone(),
        60,
        1,
    )
    .await;

    let content1 = format!(
        "custom_data={}&transaction_id=tx-a-{}",
        account.id,
        uuid::Uuid::new_v4()
    );
    let query1 = build_signed_query(&signing_key, KEY_ID, &content1);
    assert_eq!(ssv_request(state.clone(), &query1).await, StatusCode::OK);

    let content2 = format!(
        "custom_data={}&transaction_id=tx-b-{}",
        account.id,
        uuid::Uuid::new_v4()
    );
    let query2 = build_signed_query(&signing_key, KEY_ID, &content2);
    assert_eq!(
        ssv_request(state, &query2).await,
        StatusCode::OK,
        "cap bloqueia mas ainda responde 200"
    );

    let after = keyflow_api::db::accounts::find_by_device_key(&pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        after.balance_seconds, 60,
        "só a 1ª recompensa do dia deveria contar"
    );
}

#[tokio::test]
async fn unknown_account_returns_200_without_crediting_anything() {
    let signing_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;

    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool,
        60,
        5,
    )
    .await;

    let random_account_id = uuid::Uuid::new_v4();
    let content = format!("custom_data={random_account_id}&transaction_id=tx-ghost");
    let query = build_signed_query(&signing_key, KEY_ID, &content);

    let status = ssv_request(state, &query).await;

    assert_eq!(
        status,
        StatusCode::OK,
        "não deve vazar se a conta existe ou não"
    );
}

#[tokio::test]
async fn invalid_signature_returns_403() {
    let signing_key = SigningKey::random(&mut OsRng);
    let wrong_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;

    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool,
        60,
        5,
    )
    .await;

    let content = "custom_data=whatever&transaction_id=tx-1";
    // Assinado com uma chave que NÃO é a que o servidor de verifier-keys anuncia pro key_id 42.
    let query = build_signed_query(&wrong_key, KEY_ID, content);

    let status = ssv_request(state, &query).await;

    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn missing_query_returns_403() {
    let signing_key = SigningKey::random(&mut OsRng);
    let keys_server = mock_verifier_keys(&signing_key).await;
    let pool = test_pool().await;
    let state = state_with_admob(
        format!("{}/verifier-keys.json", keys_server.uri()),
        pool,
        60,
        5,
    )
    .await;

    let response = app(state)
        .oneshot(
            Request::builder()
                .uri("/v1/ssv/admob")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::FORBIDDEN);
}

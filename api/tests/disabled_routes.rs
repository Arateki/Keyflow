mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::Engine as _;
use common::{test_pool, unused_ads_config, unused_transcription_config};
use hmac::{Hmac, KeyInit, Mac};
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AdsConfig, AppState, TranscriptionConfig, app};
use serde_json::json;
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};
use tower::ServiceExt;

const PACKAGE_NAME: &str = "com.arateki.keyflow";

fn state(transcription: TranscriptionConfig, ads: AdsConfig, pool: sqlx::PgPool) -> AppState {
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
        transcription,
        ads,
    )
}

/// Pool que não abre conexão. Qualquer query falha; um 404 com ele prova que a rota
/// não chegou no banco.
fn lazy_pool() -> sqlx::PgPool {
    sqlx::PgPool::connect_lazy("postgres://keyflow:keyflow@127.0.0.1:1/keyflow_absent")
        .expect("connect_lazy só rejeita URL malformada")
}

async fn status_of(state: AppState, method: &str, uri: &str, bearer: Option<&str>) -> StatusCode {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = bearer {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    app(state)
        .oneshot(builder.body(Body::empty()).unwrap())
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn transcribe_off_is_404_without_touching_the_database() {
    // Bearer faria o extractor `Account` consultar `accounts` antes do handler.
    // O pool nem conecta: se a query rodasse, a resposta não seria 404.
    let status = status_of(
        state(
            unused_transcription_config(),
            unused_ads_config(),
            lazy_pool(),
        ),
        "POST",
        "/v1/transcribe",
        Some("not-a-real-token"),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn ssv_off_is_404_before_signature_verification() {
    // A mesma query, com a rota ligada, entra em `verify` (chave desconhecida → 403)
    // antes de qualquer crédito. Desligada, o 404 sai antes dessa verificação.
    let uri = "/v1/ssv/admob?transaction_id=tx1&signature=AAAA&key_id=1";
    let off = status_of(
        state(
            unused_transcription_config(),
            unused_ads_config(),
            lazy_pool(),
        ),
        "GET",
        uri,
        None,
    )
    .await;
    assert_eq!(off, StatusCode::NOT_FOUND);

    let pool = test_pool().await;
    let mut ads = unused_ads_config();
    ads.enabled = true;
    let on = status_of(
        state(unused_transcription_config(), ads, pool),
        "GET",
        uri,
        None,
    )
    .await;
    assert_eq!(on, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn transcribe_on_without_bearer_reaches_the_handler() {
    let pool = test_pool().await;
    let mut transcription = unused_transcription_config();
    transcription.enabled = true;
    let status = status_of(
        state(transcription, unused_ads_config(), pool),
        "POST",
        "/v1/transcribe",
        None,
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn ssv_on_without_query_reaches_the_handler() {
    let pool = test_pool().await;
    let mut ads = unused_ads_config();
    ads.enabled = true;
    let status = status_of(
        state(unused_transcription_config(), ads, pool),
        "GET",
        "/v1/ssv/admob",
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn simulated_purchase_routes_are_404_by_default_before_auth_or_database() {
    for (method, uri) in [
        ("POST", "/v1/test/purchases/simulate"),
        ("POST", "/v1/test/purchases/refund"),
    ] {
        let status = status_of(
            state(
                unused_transcription_config(),
                unused_ads_config(),
                lazy_pool(),
            ),
            method,
            uri,
            Some("not-a-real-token"),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{method} {uri}");
    }
}

#[tokio::test]
async fn production_default_rejects_a_valid_simulated_integrity_receipt() {
    let secret = "fake-simulation-key-for-tests-only-0001";
    let payload = json!({
        "purpose": "integrity",
        "deviceKey": "production-rejection-test-device",
        "productId": null,
        "nonce": "nonce-0123456789abcdef",
        "issuedAt": SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64
    });
    let payload_part = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(serde_json::to_vec(&payload).unwrap());
    let message = format!("kfsim1.{payload_part}");
    let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes()).unwrap();
    mac.update(message.as_bytes());
    let receipt = format!(
        "{message}.{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    );
    let request = Request::builder()
        .method("POST")
        .uri("/v1/register")
        .header(header::CONTENT_TYPE, "application/json")
        .extension(axum::extract::ConnectInfo(
            "127.0.0.1:0".parse::<std::net::SocketAddr>().unwrap(),
        ))
        .body(Body::from(
            json!({
                "deviceKey": "production-rejection-test-device",
                "integrityToken": receipt
            })
            .to_string(),
        ))
        .unwrap();
    let response = app(state(
        unused_transcription_config(),
        unused_ads_config(),
        lazy_pool(),
    ))
    .oneshot(request)
    .await
    .unwrap();
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
}

mod common;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode, header};
use base64::Engine as _;
use common::{test_pool, unused_ads_config, unused_transcription_config};
use hmac::{Hmac, KeyInit, Mac};
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::simulated::SimulatedReceipt;
use keyflow_api::{AppState, app};
use serde_json::{Value, json};
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};
use tower::ServiceExt;

const TEST_KEY: &str = "fake-simulation-key-for-tests-only-0001";

fn signed_receipt(purpose: &str, device_key: &str, product_id: Option<&str>) -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let payload = serde_json::to_vec(&SimulatedReceipt {
        purpose: purpose.to_string(),
        device_key: device_key.to_string(),
        product_id: product_id.map(str::to_string),
        nonce: format!("test-nonce-{}", uuid::Uuid::new_v4()),
        issued_at: now,
    })
    .unwrap();
    let payload_part = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload);
    let message = format!("kfsim1.{payload_part}");
    let mut mac = Hmac::<Sha256>::new_from_slice(TEST_KEY.as_bytes()).unwrap();
    mac.update(message.as_bytes());
    format!(
        "{message}.{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    )
}

async fn request(
    state: AppState,
    method: &str,
    uri: &str,
    bearer: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/json")
        .extension(ConnectInfo(
            "127.0.0.1:0".parse::<std::net::SocketAddr>().unwrap(),
        ));
    if let Some(token) = bearer {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let response = app(state)
        .oneshot(builder.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

#[tokio::test]
async fn simulated_unlock_register_restore_and_refund_use_the_real_entitlement_flow() {
    let pool = test_pool().await;
    let key = ServiceAccountKey {
        client_email: "unused@example.com".into(),
        private_key: String::new(),
        token_uri: "http://127.0.0.1:0/token".into(),
    };
    let google = "http://127.0.0.1:0".to_string();
    let state = AppState::new(
        pool,
        IntegrityClient::new(
            reqwest::Client::new(),
            google.clone(),
            "com.keyflow".into(),
            key.clone(),
        ),
        PublisherClient::new(reqwest::Client::new(), google, "com.keyflow".into(), key),
        reqwest::Client::new(),
        "com.keyflow".into(),
        false,
        std::num::NonZeroU32::new(1000).unwrap(),
        std::num::NonZeroU32::new(1000).unwrap(),
        unused_transcription_config(),
        unused_ads_config(),
    )
    .with_simulated_purchases(true, Some(TEST_KEY.to_string()));
    let device_key = format!("sim-test-{}", uuid::Uuid::new_v4());

    let (status, registered) = request(
        state.clone(),
        "POST",
        "/v1/register",
        None,
        json!({"deviceKey": device_key, "integrityToken": signed_receipt("integrity", &device_key, None)}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let token = registered["accessToken"].as_str().unwrap().to_string();

    let purchase_receipt = signed_receipt("purchase", &device_key, Some("unlock_forever"));
    let (status, purchase) = request(
        state.clone(),
        "POST",
        "/v1/test/purchases/simulate",
        Some(&token),
        json!({"purchaseReceipt": purchase_receipt}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(purchase["status"], "credited");

    // Simula a perda do token local: o aparelho se registra de novo, mantendo os direitos remotos.
    let (status, restored) = request(
        state.clone(),
        "POST",
        "/v1/register",
        None,
        json!({"deviceKey": device_key, "integrityToken": signed_receipt("integrity", &device_key, None)}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let token = restored["accessToken"].as_str().unwrap().to_string();
    let (status, account) =
        request(state.clone(), "GET", "/v1/account", Some(&token), json!({})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(account["adsOff"], true);
    assert_eq!(account["skinsUnlocked"], true);

    let (status, refund) = request(
        state.clone(),
        "POST",
        "/v1/test/purchases/refund",
        Some(&token),
        json!({"purchaseReceipt": purchase_receipt}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(refund["status"], "refunded");
    let (status, repeated_refund) = request(
        state.clone(),
        "POST",
        "/v1/test/purchases/refund",
        Some(&token),
        json!({"purchaseReceipt": purchase_receipt}),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(repeated_refund["error"], "simulated_purchase_refunded");
    let (status, account) = request(state, "GET", "/v1/account", Some(&token), json!({})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(account["adsOff"], false);
    assert_eq!(account["skinsUnlocked"], false);
}

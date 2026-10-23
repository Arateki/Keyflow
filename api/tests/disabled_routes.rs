mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::{test_pool, unused_ads_config, unused_transcription_config};
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AdsConfig, AppState, TranscriptionConfig, app};
use tower::ServiceExt;

const PACKAGE_NAME: &str = "com.keyflow";

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

async fn status_of(state: AppState, method: &str, uri: &str) -> StatusCode {
    app(state)
        .oneshot(
            Request::builder()
                .method(method)
                .uri(uri)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn transcribe_off_is_404_before_the_account_extractor() {
    let pool = test_pool().await;
    // Sem Bearer. Se o handler rodasse, o extractor `Account` responderia 401
    // (e, com token, consultaria o banco). 404 sai na camada de cima.
    let status = status_of(
        state(unused_transcription_config(), unused_ads_config(), pool),
        "POST",
        "/v1/transcribe",
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn ssv_off_is_404_before_signature_verification() {
    let pool = test_pool().await;
    // Sem query a rota ligada responde 403, ainda sem banco. 404 prova que nem isso rodou.
    let status = status_of(
        state(unused_transcription_config(), unused_ads_config(), pool),
        "GET",
        "/v1/ssv/admob",
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
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
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

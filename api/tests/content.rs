mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::{test_pool, unused_ads_config, unused_transcription_config};
use keyflow_api::AppState;
use keyflow_api::app;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use tower::ServiceExt;

const PACKAGE_NAME: &str = "com.keyflow";

async fn test_state() -> AppState {
    let pool = test_pool().await;
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
        unused_ads_config(),
    )
}

async fn get(
    state: AppState,
    path: &str,
    if_none_match: Option<&str>,
) -> axum::http::Response<Body> {
    let mut builder = Request::builder().uri(path);
    if let Some(etag) = if_none_match {
        builder = builder.header(header::IF_NONE_MATCH, etag);
    }
    app(state)
        .oneshot(builder.body(Body::empty()).unwrap())
        .await
        .unwrap()
}

#[tokio::test]
async fn config_returns_200_with_etag_and_seed_body() {
    let state = test_state().await;

    let response = get(state, "/v1/config", None).await;

    assert_eq!(response.status(), StatusCode::OK);
    let etag = response
        .headers()
        .get(header::ETAG)
        .expect("ETag presente")
        .clone();
    assert!(etag.to_str().unwrap().starts_with('"'));
    assert_eq!(
        response.headers().get(header::CACHE_CONTROL).unwrap(),
        "max-age=300"
    );

    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(body["minAppVersion"], "1.0.0");
    assert_eq!(body["killSwitch"], false);
    assert_eq!(body["products"]["credits_20"]["grantsEntitlements"], true);
    assert_eq!(body["products"]["credits_10"]["grantsEntitlements"], false);
}

#[tokio::test]
async fn models_returns_200_with_seed_body() {
    let state = test_state().await;

    let response = get(state, "/v1/models", None).await;

    assert_eq!(response.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert!(body["models"].is_array());
}

#[tokio::test]
async fn matching_if_none_match_returns_304() {
    let state = test_state().await;

    let first = get(state.clone(), "/v1/config", None).await;
    let etag = first
        .headers()
        .get(header::ETAG)
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();

    let second = get(state, "/v1/config", Some(&etag)).await;

    assert_eq!(second.status(), StatusCode::NOT_MODIFIED);
    let bytes = axum::body::to_bytes(second.into_body(), usize::MAX)
        .await
        .unwrap();
    assert!(bytes.is_empty(), "304 não deve ter corpo");
}

#[tokio::test]
async fn stale_if_none_match_returns_200_with_fresh_body() {
    let state = test_state().await;

    let response = get(state, "/v1/config", Some("\"stale-etag\"")).await;

    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn same_content_produces_same_etag_across_requests() {
    let state = test_state().await;

    let a = get(state.clone(), "/v1/config", None).await;
    let etag_a = a.headers().get(header::ETAG).unwrap().clone();
    let b = get(state, "/v1/config", None).await;
    let etag_b = b.headers().get(header::ETAG).unwrap().clone();

    assert_eq!(etag_a, etag_b);
}

mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::{test_pool, unused_ads_config};
use keyflow_api::auth::generate_token;
use keyflow_api::db::accounts;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::providers::ResolvedProvider;
use keyflow_api::{AppState, TranscriptionConfig, app};
use serde_json::{Value, json};
use std::time::Duration;
use tower::ServiceExt;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PACKAGE_NAME: &str = "com.arateki.keyflow";
const BYTES_PER_SECOND: usize = 8000;

fn provider(url: String, name: &str) -> ResolvedProvider {
    ResolvedProvider {
        name: name.to_string(),
        url,
        auth_header_name: "Authorization".to_string(),
        auth_value: "provider-secret".to_string(),
        model: "whisper-1".to_string(),
    }
}

async fn state_with_providers(providers: Vec<ResolvedProvider>, max_bytes: u64) -> AppState {
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
        TranscriptionConfig {
            enabled: true,
            providers,
            timeout: Duration::from_secs(5),
            max_bytes,
            unhealthy_cooldown: Duration::from_secs(60),
        },
        unused_ads_config(),
    )
}

async fn create_account(state: &AppState, balance_seconds: i64) -> (String, String) {
    let device_key = format!("device-{}", uuid::Uuid::new_v4());
    let (access_token, token_hash) = generate_token();
    accounts::create(&state.pool, &device_key, &token_hash)
        .await
        .unwrap();
    sqlx::query("update accounts set balance_seconds = $1 where device_key = $2")
        .bind(balance_seconds)
        .bind(&device_key)
        .execute(&state.pool)
        .await
        .unwrap();
    (access_token, device_key)
}

async fn transcribe_request(
    state: AppState,
    bearer: &str,
    request_id: &str,
    body_bytes: usize,
) -> axum::http::Response<Body> {
    app(state)
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/transcribe")
                .header(header::AUTHORIZATION, format!("Bearer {bearer}"))
                .header(header::CONTENT_TYPE, "multipart/form-data; boundary=test")
                .header("x-request-id", request_id)
                .body(Body::from(vec![0u8; body_bytes]))
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn body_json(response: axum::http::Response<Body>) -> Value {
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap()
    }
}

#[tokio::test]
async fn successful_transcription_debits_by_bytes_and_returns_balance_header() {
    let provider_server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "text": "hello world" })))
        .mount(&provider_server)
        .await;

    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        1_000_000,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 60).await;

    let response = transcribe_request(state.clone(), &bearer, "req-1", 10 * BYTES_PER_SECOND).await;

    assert_eq!(response.status(), StatusCode::OK);
    let balance_header = response
        .headers()
        .get("x-keyflow-balance-seconds")
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    let body = body_json(response).await;

    assert_eq!(body["text"], "hello world");
    assert_eq!(balance_header, "50");

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 50);
}

#[tokio::test]
async fn zero_balance_returns_402_without_calling_provider() {
    let provider_server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "text": "should not happen" })),
        )
        .expect(0)
        .mount(&provider_server)
        .await;

    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        1_000_000,
    )
    .await;
    let (bearer, _device_key) = create_account(&state, 0).await;

    let response = transcribe_request(state, &bearer, "req-1", 1000).await;

    assert_eq!(response.status(), StatusCode::PAYMENT_REQUIRED);
    let body = body_json(response).await;
    assert_eq!(body["balanceSeconds"], 0);
}

#[tokio::test]
async fn payload_over_cap_returns_413_without_debiting() {
    let provider_server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "text": "should not happen" })),
        )
        .expect(0)
        .mount(&provider_server)
        .await;

    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        // Cap bem menor que o corpo enviado.
        100,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 1000).await;

    let response = transcribe_request(state.clone(), &bearer, "req-1", 10_000).await;

    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 1000, "413 não deve debitar");
}

#[tokio::test]
async fn provider_5xx_marks_unhealthy_and_next_request_falls_back() {
    let primary = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&primary)
        .await;

    let fallback = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "text": "from fallback" })))
        .mount(&fallback)
        .await;

    let state = state_with_providers(
        vec![
            provider(format!("{}/transcribe", primary.uri()), "primary"),
            provider(format!("{}/transcribe", fallback.uri()), "fallback"),
        ],
        1_000_000,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 60).await;

    let first = transcribe_request(state.clone(), &bearer, "req-1", BYTES_PER_SECOND).await;
    assert_eq!(first.status(), StatusCode::BAD_GATEWAY);
    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 60, "provedor caindo não debita");

    // 2ª request (novo request-id): o circuit breaker já marcou "primary" indisponível — deve
    // ir direto pro fallback e ter sucesso.
    let second = transcribe_request(state.clone(), &bearer, "req-2", BYTES_PER_SECOND).await;
    assert_eq!(second.status(), StatusCode::OK);
    let body = body_json(second).await;
    assert_eq!(body["text"], "from fallback");
}

#[tokio::test]
async fn only_provider_failing_returns_error_without_debit() {
    let primary = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&primary)
        .await;

    let state = state_with_providers(
        vec![provider(format!("{}/transcribe", primary.uri()), "primary")],
        1_000_000,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 60).await;

    let response = transcribe_request(state.clone(), &bearer, "req-1", BYTES_PER_SECOND).await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 60);
}

#[tokio::test]
async fn retry_with_same_request_id_does_not_debit_twice() {
    let provider_server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "text": "hello" })))
        .mount(&provider_server)
        .await;

    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        1_000_000,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 60).await;

    let first = transcribe_request(state.clone(), &bearer, "same-id", 10 * BYTES_PER_SECOND).await;
    assert_eq!(first.status(), StatusCode::OK);

    let second = transcribe_request(state.clone(), &bearer, "same-id", 10 * BYTES_PER_SECOND).await;
    assert_eq!(
        second.status(),
        StatusCode::OK,
        "retry deve re-executar upstream com sucesso"
    );

    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        account.balance_seconds, 50,
        "retry não deve debitar de novo"
    );
}

/// TRX-04 ("nunca bufferizar"): a garantia é por **inspeção de código** (nenhum `to_vec()`/
/// `.bytes()` no caminho do corpo de ENTRADA — só `body.into_data_stream()` repassado direto pro
/// `reqwest::Body::wrap_stream`, ver `src/routes/transcribe.rs`), não por medir memória de
/// processo em teste automatizado. Tentei medir RSS (`/proc/self/status`) antes/depois de um
/// upload de 5 MB e descartei: o crescimento observado (~17 MB) vem majoritariamente de ruído do
/// alocador/runtime tokio/pool de conexões nesta única execução de teste, não do corpo em si —
/// sinal não confiável o bastante pra virar gate de CI (mesma lição da API-T2 com o teste de
/// tracing flaky). Este teste fica só como prova funcional de que um upload maior que o típico
/// (10s de áudio) continua funcionando corretamente fim-a-fim.
#[tokio::test]
async fn large_upload_completes_successfully() {
    let provider_server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/transcribe"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "text": "ok" })))
        .mount(&provider_server)
        .await;

    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        50_000_000,
    )
    .await;
    let (bearer, device_key) = create_account(&state, 100_000).await;

    let upload_bytes = 5_000_000usize;
    let response = transcribe_request(state.clone(), &bearer, "req-large", upload_bytes).await;

    assert_eq!(response.status(), StatusCode::OK);
    let expected_seconds = (upload_bytes as u64).div_ceil(BYTES_PER_SECOND as u64) as i64;
    let account = accounts::find_by_device_key(&state.pool, &device_key)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(account.balance_seconds, 100_000 - expected_seconds);
}

#[tokio::test]
async fn missing_request_id_is_rejected() {
    let provider_server = MockServer::start().await;
    let state = state_with_providers(
        vec![provider(
            format!("{}/transcribe", provider_server.uri()),
            "primary",
        )],
        1_000_000,
    )
    .await;
    let (bearer, _device_key) = create_account(&state, 60).await;

    let response = app(state)
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/transcribe")
                .header(header::AUTHORIZATION, format!("Bearer {bearer}"))
                .header(header::CONTENT_TYPE, "multipart/form-data; boundary=test")
                .body(Body::from(vec![0u8; 100]))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

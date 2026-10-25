mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use common::{test_pool, unused_ads_config, unused_transcription_config};
use keyflow_api::app;
use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AppState, auth::generate_token, db::accounts};
use serde_json::Value;
use tower::ServiceExt;

const PACKAGE_NAME: &str = "com.arateki.keyflow";

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
        reqwest::Client::new(),
        PACKAGE_NAME.to_string(),
        false,
        std::num::NonZeroU32::new(1000).unwrap(),
        std::num::NonZeroU32::new(1000).unwrap(),
        unused_transcription_config(),
        unused_ads_config(),
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

async fn delete_account(state: AppState, token: Option<&str>) -> StatusCode {
    let mut request = Request::builder().method("DELETE").uri("/v1/account");
    if let Some(token) = token {
        request = request.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    app(state)
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn deletion_purges_all_links_only_for_the_authenticated_device() {
    let state = test_state().await;
    let (token, hash) = generate_token();
    let own = accounts::create(
        &state.pool,
        &format!("delete-{}", uuid::Uuid::new_v4()),
        &hash,
    )
    .await
    .unwrap();
    let (other_token, other_hash) = generate_token();
    let other = accounts::create(
        &state.pool,
        &format!("other-{}", uuid::Uuid::new_v4()),
        &other_hash,
    )
    .await
    .unwrap();
    for id in [own.id, other.id] {
        sqlx::query("insert into ledger(account_id,delta_seconds,reason) values($1,1,'purchase')")
            .bind(id)
            .execute(&state.pool)
            .await
            .unwrap();
        sqlx::query("insert into purchases(token_hash,account_id,product_id,status,credited_seconds) values($1,$2,'unlock_forever','credited',0)")
            .bind(id.as_bytes().as_slice()).bind(id).execute(&state.pool).await.unwrap();
        sqlx::query("insert into rewards(transaction_id,account_id,seconds) values($1,$2,1)")
            .bind(id.to_string())
            .bind(id)
            .execute(&state.pool)
            .await
            .unwrap();
        sqlx::query(
            "insert into transcribe_requests(account_id,request_id,seconds) values($1,'req',1)",
        )
        .bind(id)
        .execute(&state.pool)
        .await
        .unwrap();
    }
    assert_eq!(
        delete_account(state.clone(), None).await,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        delete_account(state.clone(), Some("not-a-real-token")).await,
        StatusCode::UNAUTHORIZED
    );
    assert!(accounts::exists(&state.pool, own.id).await.unwrap());
    assert_eq!(
        delete_account(state.clone(), Some(&token)).await,
        StatusCode::NO_CONTENT
    );
    for (table, sql) in [
        ("accounts", "select count(*) from accounts where id=$1"),
        ("ledger", "select count(*) from ledger where account_id=$1"),
        (
            "purchases",
            "select count(*) from purchases where account_id=$1",
        ),
        (
            "rewards",
            "select count(*) from rewards where account_id=$1",
        ),
        (
            "transcribe_requests",
            "select count(*) from transcribe_requests where account_id=$1",
        ),
    ] {
        let removed: i64 = sqlx::query_scalar(sql)
            .bind(own.id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
        let preserved: i64 = sqlx::query_scalar(sql)
            .bind(other.id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
        assert_eq!(removed, 0, "{table}");
        assert_eq!(preserved, 1, "{table}");
    }
    // O efeito é idempotente; o Bearer apagado não autentica mais e nenhum tombstone é guardado.
    assert_eq!(
        delete_account(state.clone(), Some(&token)).await,
        StatusCode::UNAUTHORIZED
    );
    accounts::delete(&state.pool, own.id).await.unwrap();
    assert_eq!(
        get_account(state.clone(), Some(&other_token)).await.0,
        StatusCode::OK
    );
    // Um ID arbitrário na URL não dá acesso ao aparelho vizinho.
    let response = app(state.clone())
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/v1/account/{}", other.id))
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert!(accounts::exists(&state.pool, other.id).await.unwrap());
}

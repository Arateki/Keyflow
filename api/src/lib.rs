pub mod auth;
pub mod catalog;
pub mod config;
pub mod db;
pub mod google;
pub mod middleware;
pub mod routes;

use std::num::NonZeroU32;
use std::sync::Arc;

use axum::extract::{MatchedPath, State};
use axum::http::{Request, StatusCode};
use axum::routing::post;
use axum::{Router, routing::get};
use sqlx::PgPool;
use tower_http::trace::TraceLayer;

use google::integrity::IntegrityClient;
use google::publisher::PublisherClient;
use middleware::ratelimit::RateLimiters;

pub struct AppStateInner {
    pub pool: PgPool,
    pub integrity: IntegrityClient,
    pub publisher: PublisherClient,
    pub package_name: String,
    pub integrity_relaxed: bool,
    pub rate_limiters: RateLimiters,
}

/// Estado compartilhado entre handlers — `Clone` barato (`Arc` por dentro; `PgPool` também já é
/// `Arc` por dentro, mas o `IntegrityClient`/`TokenCache` não são `Clone`, daí o wrapper).
#[derive(Clone)]
pub struct AppState(Arc<AppStateInner>);

impl AppState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        pool: PgPool,
        integrity: IntegrityClient,
        publisher: PublisherClient,
        package_name: String,
        integrity_relaxed: bool,
        register_rate_limit_per_minute: NonZeroU32,
        account_rate_limit_per_minute: NonZeroU32,
    ) -> Self {
        Self(Arc::new(AppStateInner {
            pool,
            integrity,
            publisher,
            package_name,
            integrity_relaxed,
            rate_limiters: RateLimiters::new(
                register_rate_limit_per_minute,
                account_rate_limit_per_minute,
            ),
        }))
    }
}

impl std::ops::Deref for AppState {
    type Target = AppStateInner;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

async fn health(State(state): State<AppState>) -> Result<&'static str, StatusCode> {
    sqlx::query("select 1")
        .execute(&state.pool)
        .await
        .map(|_| "ok")
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

pub fn app(state: AppState) -> Router {
    // `route_layer` (não `layer`): só assim a extensão `MatchedPath` já está no request quando
    // `make_span_with` roda — `layer` envolve o roteamento inteiro e roda ANTES da extensão ser
    // inserida (gotcha documentado do axum).
    Router::new()
        .route("/health", get(health))
        .route("/v1/register", post(routes::register::register))
        .route("/v1/account", get(routes::account::get_account))
        .route("/v1/purchases/redeem", post(routes::redeem::redeem))
        .route_layer(
            TraceLayer::new_for_http().make_span_with(|request: &Request<_>| {
                let matched_path = request
                    .extensions()
                    .get::<MatchedPath>()
                    .map(MatchedPath::as_str);

                tracing::info_span!(
                    "http_request",
                    method = %request.method(),
                    matched_path,
                )
            }),
        )
        .with_state(state)
}

pub fn init_tracing() {
    // Sem RUST_LOG, mantém a latência por rota visível (tower_http::trace roda em DEBUG).
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,tower_http=debug"));

    tracing_subscriber::fmt()
        .json()
        .with_env_filter(filter)
        .init();
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use reqwest::Client;
    use tower::ServiceExt;

    async fn test_pool() -> PgPool {
        let database_url = std::env::var("DATABASE_URL").expect(
            "DATABASE_URL deve estar setada para os testes de integração (ver api/docker-compose.yml)",
        );
        let pool = crate::db::connect(&database_url)
            .await
            .expect("falha ao conectar no Postgres de teste");
        crate::db::MIGRATOR
            .run(&pool)
            .await
            .expect("falha ao rodar migrations");
        pool
    }

    async fn test_state() -> AppState {
        let pool = test_pool().await;
        // Nenhum teste deste módulo bate de verdade no Google — a IntegrityClient aponta pra um
        // endereço que nunca é chamado (health não usa integrity).
        let unused_key = google::auth::ServiceAccountKey {
            client_email: "unused@example.com".to_string(),
            private_key: String::new(),
            token_uri: "http://127.0.0.1:0/token".to_string(),
        };
        let integrity = IntegrityClient::new(
            Client::new(),
            "http://127.0.0.1:0".to_string(),
            "com.keyflow".to_string(),
            unused_key.clone(),
        );
        let publisher = PublisherClient::new(
            Client::new(),
            "http://127.0.0.1:0".to_string(),
            "com.keyflow".to_string(),
            unused_key,
        );
        AppState::new(
            pool,
            integrity,
            publisher,
            "com.keyflow".to_string(),
            false,
            std::num::NonZeroU32::new(1000).unwrap(),
            std::num::NonZeroU32::new(1000).unwrap(),
        )
    }

    #[tokio::test]
    async fn health_returns_200_with_real_select_1() {
        let state = test_state().await;

        let response = app(state)
            .oneshot(
                Request::builder()
                    .uri("/health")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
    }

    // Log JSON com rota (`matched_path`) e latência: verificado por inspeção documentada em vez
    // de unit test (alternativa prevista no design §Plano de testes). Um teste que instala um
    // subscriber via `tracing::subscriber::set_default`/`with_default` fica flaky quando roda em
    // paralelo com as outras `#[tokio::test]` deste binário — o cache global de `Interest` por
    // callsite do crate `tracing` é compartilhado entre threads, e o resultado depende de qual
    // thread pergunta "enabled?" primeiro. Evidência real capturada com `cargo run` (RUST_LOG=
    // info,tower_http=debug), request real em GET /health:
    // {"timestamp":"...","level":"DEBUG","fields":{"message":"started processing request"},
    //  "target":"tower_http::trace::on_request","span":{"matched_path":"/health","method":"GET",
    //  "name":"http_request"},"spans":[...]}
    // {"timestamp":"...","level":"DEBUG","fields":{"message":"finished processing request",
    //  "latency":"0 ms","status":200},"target":"tower_http::trace::on_response",
    //  "span":{"matched_path":"/health",...},"spans":[...]}
}

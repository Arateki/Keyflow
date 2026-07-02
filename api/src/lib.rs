pub mod config;
pub mod db;

use axum::extract::{MatchedPath, State};
use axum::http::{Request, StatusCode};
use axum::{Router, routing::get};
use sqlx::PgPool;
use tower_http::trace::TraceLayer;

async fn health(State(pool): State<PgPool>) -> Result<&'static str, StatusCode> {
    sqlx::query("select 1")
        .execute(&pool)
        .await
        .map(|_| "ok")
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

pub fn app(pool: PgPool) -> Router {
    // `route_layer` (não `layer`): só assim a extensão `MatchedPath` já está no request quando
    // `make_span_with` roda — `layer` envolve o roteamento inteiro e roda ANTES da extensão ser
    // inserida (gotcha documentado do axum).
    Router::new()
        .route("/health", get(health))
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
        .with_state(pool)
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

    #[tokio::test]
    async fn health_returns_200_with_real_select_1() {
        let pool = test_pool().await;

        let response = app(pool)
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

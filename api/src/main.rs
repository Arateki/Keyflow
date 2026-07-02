mod config;

use axum::extract::MatchedPath;
use axum::http::Request;
use axum::{Router, routing::get};
use config::Config;
use tokio::net::TcpListener;
use tower_http::trace::TraceLayer;

async fn health() -> &'static str {
    "ok"
}

fn app() -> Router {
    // `route_layer` (não `layer`): só assim a extensão `MatchedPath` já está no request quando
    // `make_span_with` roda — `layer` envolve o roteamento inteiro e roda ANTES da extensão ser
    // inserida (gotcha documentado do axum).
    Router::new().route("/health", get(health)).route_layer(
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
}

fn init_tracing() {
    // Sem RUST_LOG, mantém a latência por rota visível (tower_http::trace roda em DEBUG).
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,tower_http=debug"));

    tracing_subscriber::fmt()
        .json()
        .with_env_filter(filter)
        .init();
}

#[tokio::main]
async fn main() {
    init_tracing();

    let config = Config::from_env().unwrap_or_else(|e| panic!("config error: {e}"));

    let listener = TcpListener::bind(config.bind_addr)
        .await
        .unwrap_or_else(|e| panic!("failed to bind {}: {e}", config.bind_addr));
    tracing::info!(addr = %config.bind_addr, "keyflow-api listening");
    axum::serve(listener, app()).await.expect("server error");
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_returns_200() {
        let response = app()
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

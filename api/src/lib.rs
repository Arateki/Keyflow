pub mod auth;
pub mod catalog;
pub mod config;
pub mod db;
pub mod google;
pub mod jobs;
pub mod middleware;
pub mod providers;
pub mod routes;
pub mod simulated;

use std::num::NonZeroU32;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::extract::{MatchedPath, State};
use axum::http::{Request, StatusCode};
use axum::middleware::Next;
use axum::response::IntoResponse;
use axum::routing::post;
use axum::{Router, routing::get};
use sqlx::PgPool;
use tower_http::trace::TraceLayer;

use google::admob::AdmobKeysCache;
use google::integrity::IntegrityClient;
use google::publisher::PublisherClient;
use ipnet::IpNet;
use middleware::ratelimit::RateLimiters;
use providers::{ProviderHealth, ResolvedProvider};

/// Config do proxy de transcrição (design §Proxy, API-T11) — agrupado à parte pra não inchar
/// ainda mais `AppState::new`.
pub struct TranscriptionConfig {
    /// Na v1 a rota nasce desligada. Ligar não altera o default da SSV.
    pub enabled: bool,
    pub providers: Vec<ResolvedProvider>,
    pub timeout: Duration,
    pub max_bytes: u64,
    pub unhealthy_cooldown: Duration,
}

/// Config do rewarded SSV (design §Rewarded, API-T12).
pub struct AdsConfig {
    /// Na v1 a rota nasce desligada. Ligar não altera o default da transcrição.
    pub enabled: bool,
    pub verifier_keys_url: String,
    pub reward_seconds: i64,
    pub daily_cap: i64,
}

pub struct AppStateInner {
    pub pool: PgPool,
    pub integrity: IntegrityClient,
    pub publisher: PublisherClient,
    pub http: reqwest::Client,
    pub package_name: String,
    pub integrity_relaxed: bool,
    pub rate_limiters: Arc<RateLimiters>,
    /// Redes de proxy que podem mandar `CF-Connecting-IP`. Vazia = o header é ignorado.
    pub trusted_proxies: Vec<IpNet>,
    pub providers: Vec<ResolvedProvider>,
    pub provider_health: ProviderHealth,
    pub transcription_timeout: Duration,
    pub transcription_max_bytes: u64,
    pub provider_unhealthy_cooldown: Duration,
    pub admob_keys: AdmobKeysCache,
    pub ads_reward_seconds: i64,
    pub ads_daily_cap: i64,
    pub transcribe_enabled: bool,
    pub ssv_enabled: bool,
    pub simulated_purchases_enabled: bool,
    pub simulated_purchase_hmac_secret: Option<String>,
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
        http: reqwest::Client,
        package_name: String,
        integrity_relaxed: bool,
        register_rate_limit_per_minute: NonZeroU32,
        account_rate_limit_per_minute: NonZeroU32,
        transcription: TranscriptionConfig,
        ads: AdsConfig,
    ) -> Self {
        let provider_health = ProviderHealth::new(transcription.providers.len());
        let admob_keys = AdmobKeysCache::new(reqwest::Client::new(), ads.verifier_keys_url);
        Self(Arc::new(AppStateInner {
            pool,
            integrity,
            publisher,
            http,
            package_name,
            integrity_relaxed,
            rate_limiters: Arc::new(RateLimiters::new(
                register_rate_limit_per_minute,
                account_rate_limit_per_minute,
            )),
            trusted_proxies: Vec::new(),
            providers: transcription.providers,
            provider_health,
            transcription_timeout: transcription.timeout,
            transcription_max_bytes: transcription.max_bytes,
            provider_unhealthy_cooldown: transcription.unhealthy_cooldown,
            admob_keys,
            ads_reward_seconds: ads.reward_seconds,
            ads_daily_cap: ads.daily_cap,
            transcribe_enabled: transcription.enabled,
            ssv_enabled: ads.enabled,
            simulated_purchases_enabled: false,
            simulated_purchase_hmac_secret: None,
        }))
    }

    /// Liga a lista de proxies confiáveis. Chamar antes de clonar o estado: o `Arc` ainda
    /// é único logo depois de `new`.
    pub fn with_trusted_proxies(self, trusted_proxies: Vec<IpNet>) -> Self {
        let mut inner = match Arc::try_unwrap(self.0) {
            Ok(inner) => inner,
            Err(_) => panic!("estado ainda não foi compartilhado"),
        };
        inner.trusted_proxies = trusted_proxies;
        Self(Arc::new(inner))
    }

    pub fn with_trusted_proxy_cidrs(self, cidrs: &[&str]) -> Self {
        let nets = crate::config::parse_trusted_proxies(&cidrs.join(","))
            .expect("CIDR de proxy confiável");
        self.with_trusted_proxies(nets)
    }

    pub fn with_simulated_purchases(mut self, enabled: bool, hmac_secret: Option<String>) -> Self {
        let inner = Arc::get_mut(&mut self.0).expect("estado ainda não foi compartilhado");
        inner.simulated_purchases_enabled = enabled;
        inner.simulated_purchase_hmac_secret = if enabled { hmac_secret } else { None };
        self
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

/// 404 antes do handler. O extractor `Account` (que consulta o banco) não chega a rodar.
///
/// Expande no ponto da rota: o tipo concreto do `from_fn` precisa aparecer onde o
/// `MethodRouter::layer` exige o `Service` associado.
macro_rules! hide_unless {
    ($enabled:expr) => {{
        let enabled = $enabled;
        axum::middleware::from_fn(move |request: Request<Body>, next: Next| {
            let enabled = enabled;
            async move {
                if enabled {
                    next.run(request).await
                } else {
                    StatusCode::NOT_FOUND.into_response()
                }
            }
        })
    }};
}

pub fn app(state: AppState) -> Router {
    let transcribe_enabled = state.transcribe_enabled;
    let ssv_enabled = state.ssv_enabled;
    // `route_layer` (não `layer`): só assim a extensão `MatchedPath` já está no request quando
    // `make_span_with` roda — `layer` envolve o roteamento inteiro e roda ANTES da extensão ser
    // inserida (gotcha documentado do axum).
    Router::new()
        .route("/health", get(health))
        .route("/v1/register", post(routes::register::register))
        .route("/v1/account", get(routes::account::get_account))
        .route("/v1/purchases/redeem", post(routes::redeem::redeem))
        .route(
            "/v1/test/purchases/simulate",
            post(routes::simulated_purchase::simulate_purchase)
                .layer(hide_unless!(state.simulated_purchases_enabled)),
        )
        .route(
            "/v1/test/purchases/refund",
            post(routes::simulated_purchase::refund_purchase)
                .layer(hide_unless!(state.simulated_purchases_enabled)),
        )
        .route("/v1/config", get(routes::content::get_config))
        .route("/v1/models", get(routes::content::get_models))
        .route(
            "/v1/transcribe",
            post(routes::transcribe::transcribe).layer(hide_unless!(transcribe_enabled)),
        )
        .route(
            "/v1/ssv/admob",
            get(routes::ssv::ssv_admob).layer(hide_unless!(ssv_enabled)),
        )
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
            Client::new(),
            "com.keyflow".to_string(),
            false,
            std::num::NonZeroU32::new(1000).unwrap(),
            std::num::NonZeroU32::new(1000).unwrap(),
            test_transcription_config(),
            test_ads_config(),
        )
    }

    fn test_transcription_config() -> TranscriptionConfig {
        TranscriptionConfig {
            enabled: false,
            providers: vec![ResolvedProvider {
                name: "unused".to_string(),
                url: "http://127.0.0.1:0".to_string(),
                auth_header_name: "Authorization".to_string(),
                auth_value: "unused".to_string(),
                model: "unused".to_string(),
            }],
            timeout: Duration::from_secs(30),
            max_bytes: 26_214_400,
            unhealthy_cooldown: Duration::from_secs(60),
        }
    }

    fn test_ads_config() -> AdsConfig {
        AdsConfig {
            enabled: false,
            verifier_keys_url: "http://127.0.0.1:0".to_string(),
            reward_seconds: 60,
            daily_cap: 5,
        }
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

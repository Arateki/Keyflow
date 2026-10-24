use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::middleware::ratelimit::{CLEANUP_INTERVAL, RateLimiters};
use keyflow_api::{AppState, app, config::Config, db, init_tracing, jobs};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpListener;

const ADMOB_VERIFIER_KEYS_URL: &str = "https://www.gstatic.com/admob/reward/verifier-keys.json";

fn main() {
    init_tracing();

    let config = Config::from_env().unwrap_or_else(|e| panic!("config error: {e}"));

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(config.tokio_worker_threads.get() as usize)
        .max_blocking_threads(config.tokio_max_blocking_threads.get() as usize)
        .enable_all()
        .build()
        .unwrap_or_else(|e| panic!("failed to start tokio runtime: {e}"));

    runtime.block_on(run(config));
}

async fn run(config: Config) {
    let pool = db::connect_with(&config.database_url, config.pool_settings())
        .await
        .unwrap_or_else(|e| panic!("failed to connect to database: {e}"));
    db::MIGRATOR
        .run(&pool)
        .await
        .unwrap_or_else(|e| panic!("failed to run migrations: {e}"));

    let service_account =
        ServiceAccountKey::from_json_file(&config.google_service_account_json_path)
            .unwrap_or_else(|e| panic!("failed to load Google service account key: {e}"));
    let integrity = IntegrityClient::new(
        reqwest::Client::new(),
        config.play_integrity_base_url.clone(),
        config.package_name.clone(),
        service_account.clone(),
    );
    let publisher = PublisherClient::new(
        reqwest::Client::new(),
        config.android_publisher_base_url.clone(),
        config.package_name.clone(),
        service_account.clone(),
    );

    // Cliente próprio pro job (TokenCache separada do que atende requests) — mesma credencial.
    let job_publisher = PublisherClient::new(
        reqwest::Client::new(),
        config.android_publisher_base_url.clone(),
        config.package_name.clone(),
        service_account,
    );
    tokio::spawn(jobs::voided::run_periodically(
        pool.clone(),
        job_publisher,
        Duration::from_secs(config.voided_purchases_job_interval_secs.get() as u64),
    ));

    let state = AppState::new(
        pool,
        integrity,
        publisher,
        reqwest::Client::new(),
        config.package_name,
        config.integrity_relaxed,
        config.register_rate_limit_per_minute,
        config.account_rate_limit_per_minute,
        keyflow_api::TranscriptionConfig {
            enabled: config.transcribe_enabled,
            providers: config.providers,
            timeout: Duration::from_millis(config.transcription_timeout_ms.get() as u64),
            max_bytes: config.transcription_max_bytes.get() as u64,
            unhealthy_cooldown: Duration::from_secs(
                config.provider_unhealthy_cooldown_secs.get() as u64
            ),
        },
        keyflow_api::AdsConfig {
            enabled: config.ssv_enabled,
            verifier_keys_url: ADMOB_VERIFIER_KEYS_URL.to_string(),
            reward_seconds: config.ads_reward_seconds.get() as i64,
            daily_cap: config.ads_daily_cap.get() as i64,
        },
    )
    .with_trusted_proxies(config.trusted_proxies.clone())
    .with_simulated_purchases(
        config.simulated_purchases_enabled,
        config.simulated_purchase_hmac_secret.clone(),
    );

    // O handle fica vivo enquanto o servidor roda. Dropar no fim só desanexa a tarefa.
    let _cleanup = RateLimiters::spawn_cleanup(Arc::clone(&state.rate_limiters), CLEANUP_INTERVAL);

    let listener = TcpListener::bind(config.bind_addr)
        .await
        .unwrap_or_else(|e| panic!("failed to bind {}: {e}", config.bind_addr));
    tracing::info!(addr = %config.bind_addr, "keyflow-api listening");
    axum::serve(
        listener,
        app(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .await
    .expect("server error");
}

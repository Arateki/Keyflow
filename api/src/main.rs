use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::google::publisher::PublisherClient;
use keyflow_api::{AppState, app, config::Config, db, init_tracing, jobs};
use std::time::Duration;
use tokio::net::TcpListener;

const PLAY_INTEGRITY_BASE_URL: &str = "https://playintegrity.googleapis.com";
const ANDROID_PUBLISHER_BASE_URL: &str = "https://androidpublisher.googleapis.com";
const ADMOB_VERIFIER_KEYS_URL: &str = "https://www.gstatic.com/admob/reward/verifier-keys.json";

#[tokio::main]
async fn main() {
    init_tracing();

    let config = Config::from_env().unwrap_or_else(|e| panic!("config error: {e}"));

    let pool = db::connect(&config.database_url)
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
        PLAY_INTEGRITY_BASE_URL.to_string(),
        config.package_name.clone(),
        service_account.clone(),
    );
    let publisher = PublisherClient::new(
        reqwest::Client::new(),
        ANDROID_PUBLISHER_BASE_URL.to_string(),
        config.package_name.clone(),
        service_account.clone(),
    );

    // Cliente próprio pro job (TokenCache separada do que atende requests) — mesma credencial.
    let job_publisher = PublisherClient::new(
        reqwest::Client::new(),
        ANDROID_PUBLISHER_BASE_URL.to_string(),
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
            providers: config.providers,
            timeout: Duration::from_millis(config.transcription_timeout_ms.get() as u64),
            max_bytes: config.transcription_max_bytes.get() as u64,
            unhealthy_cooldown: Duration::from_secs(
                config.provider_unhealthy_cooldown_secs.get() as u64
            ),
        },
        keyflow_api::AdsConfig {
            verifier_keys_url: ADMOB_VERIFIER_KEYS_URL.to_string(),
            reward_seconds: config.ads_reward_seconds.get() as i64,
            daily_cap: config.ads_daily_cap.get() as i64,
        },
    );

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

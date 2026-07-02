use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use keyflow_api::{AppState, app, config::Config, db, init_tracing};
use tokio::net::TcpListener;

const PLAY_INTEGRITY_BASE_URL: &str = "https://playintegrity.googleapis.com";

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
        service_account,
    );
    let state = AppState::new(
        pool,
        integrity,
        config.package_name,
        config.integrity_relaxed,
    );

    let listener = TcpListener::bind(config.bind_addr)
        .await
        .unwrap_or_else(|e| panic!("failed to bind {}: {e}", config.bind_addr));
    tracing::info!(addr = %config.bind_addr, "keyflow-api listening");
    axum::serve(listener, app(state))
        .await
        .expect("server error");
}

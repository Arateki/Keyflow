use keyflow_api::{app, config::Config, db, init_tracing};
use tokio::net::TcpListener;

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

    let listener = TcpListener::bind(config.bind_addr)
        .await
        .unwrap_or_else(|e| panic!("failed to bind {}: {e}", config.bind_addr));
    tracing::info!(addr = %config.bind_addr, "keyflow-api listening");
    axum::serve(listener, app(pool))
        .await
        .expect("server error");
}

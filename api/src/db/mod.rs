pub mod accounts;
pub mod ledger;

use std::time::Duration;

use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

pub static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!();

/// Tamanho do pool. Produção lê isto da config; os testes usam [`PoolSettings::for_tests`].
#[derive(Debug, Clone, Copy)]
pub struct PoolSettings {
    pub max_connections: u32,
    pub min_connections: u32,
    pub idle_timeout: Duration,
    pub acquire_timeout: Duration,
}

impl PoolSettings {
    /// O default antigo (10 conexões). Os testes de integração não mudam de comportamento.
    pub fn for_tests() -> Self {
        Self {
            max_connections: 10,
            min_connections: 0,
            idle_timeout: Duration::from_secs(300),
            acquire_timeout: Duration::from_secs(5),
        }
    }
}

pub async fn connect(database_url: &str) -> sqlx::Result<PgPool> {
    connect_with(database_url, PoolSettings::for_tests()).await
}

pub async fn connect_with(database_url: &str, settings: PoolSettings) -> sqlx::Result<PgPool> {
    PgPoolOptions::new()
        .max_connections(settings.max_connections)
        .min_connections(settings.min_connections)
        .idle_timeout(settings.idle_timeout)
        .acquire_timeout(settings.acquire_timeout)
        .connect(database_url)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pool_stops_at_max_connections() {
        let database_url = std::env::var("DATABASE_URL").expect(
            "DATABASE_URL deve estar setada para os testes de integração (ver api/docker-compose.yml)",
        );
        let pool = connect_with(
            &database_url,
            PoolSettings {
                max_connections: 2,
                min_connections: 0,
                idle_timeout: Duration::from_secs(300),
                // Curto o bastante para a 3ª conexão falhar no teste, longo o bastante
                // para o connect sobreviver ao Postgres compartilhado com os outros testes.
                acquire_timeout: Duration::from_secs(2),
            },
        )
        .await
        .expect("falha ao conectar no Postgres de teste");

        // `connect` faz um ping e devolve essa conexão ao pool; min_connections 0 não a fecha
        // na hora (só o idle_timeout). O que o limite garante é não passar de max_connections.
        let _first = pool.acquire().await.expect("1ª conexão");
        let _second = pool.acquire().await.expect("2ª conexão");
        let third = pool.acquire().await;
        assert!(
            third.is_err(),
            "a 3ª conexão deveria estourar o acquire_timeout"
        );
    }
}

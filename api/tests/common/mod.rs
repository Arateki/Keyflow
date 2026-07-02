use sqlx::PgPool;

pub async fn test_pool() -> PgPool {
    let database_url = std::env::var("DATABASE_URL").expect(
        "DATABASE_URL deve estar setada para os testes de integração (ver api/docker-compose.yml)",
    );
    let pool = keyflow_api::db::connect(&database_url)
        .await
        .expect("falha ao conectar no Postgres de teste");
    keyflow_api::db::MIGRATOR
        .run(&pool)
        .await
        .expect("falha ao rodar migrations");
    pool
}

/// Cria uma conta de teste com `device_key`/`token_hash` únicos e o saldo inicial dado.
/// `token_hash` não precisa ser um hash real aqui — os testes de ledger não validam auth.
/// `tests/common/mod.rs` é compilado por cada binário de teste em `tests/` separadamente; nem
/// todos usam todos os helpers — sem `allow`, o clippy (`-D warnings`) reclamaria de dead_code
/// num binário específico mesmo com uso real em outro.
#[allow(dead_code)]
pub async fn create_test_account(pool: &PgPool, balance_seconds: i64) -> uuid::Uuid {
    let device_key = format!("test-{}", uuid::Uuid::new_v4());
    let token_hash = uuid::Uuid::new_v4().into_bytes().to_vec();

    let (id,): (uuid::Uuid,) = sqlx::query_as(
        "insert into accounts (device_key, token_hash, balance_seconds) values ($1, $2, $3) \
         returning id",
    )
    .bind(device_key)
    .bind(token_hash)
    .bind(balance_seconds)
    .fetch_one(pool)
    .await
    .expect("falha ao criar conta de teste");

    id
}

#[allow(dead_code)]
pub async fn balance_of(pool: &PgPool, account_id: uuid::Uuid) -> i64 {
    let (balance,): (i64,) = sqlx::query_as("select balance_seconds from accounts where id = $1")
        .bind(account_id)
        .fetch_one(pool)
        .await
        .expect("conta de teste deveria existir");
    balance
}

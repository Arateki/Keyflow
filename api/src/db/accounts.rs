use sqlx::PgPool;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq, sqlx::FromRow)]
pub struct Account {
    pub id: Uuid,
    pub device_key: String,
    pub balance_seconds: i64,
    pub ads_off: bool,
    pub skins_unlocked: bool,
}

pub async fn exists(pool: &PgPool, account_id: Uuid) -> sqlx::Result<bool> {
    sqlx::query_scalar("select exists(select 1 from accounts where id = $1)")
        .bind(account_id)
        .fetch_one(pool)
        .await
}

pub async fn find_by_token_hash(pool: &PgPool, token_hash: &[u8]) -> sqlx::Result<Option<Account>> {
    sqlx::query_as(
        "select id, device_key, balance_seconds, ads_off, skins_unlocked from accounts \
         where token_hash = $1",
    )
    .bind(token_hash)
    .fetch_optional(pool)
    .await
}

pub async fn find_by_device_key(pool: &PgPool, device_key: &str) -> sqlx::Result<Option<Account>> {
    sqlx::query_as(
        "select id, device_key, balance_seconds, ads_off, skins_unlocked from accounts \
         where device_key = $1",
    )
    .bind(device_key)
    .fetch_optional(pool)
    .await
}

pub async fn create(pool: &PgPool, device_key: &str, token_hash: &[u8]) -> sqlx::Result<Account> {
    sqlx::query_as(
        "insert into accounts (device_key, token_hash) values ($1, $2) \
         returning id, device_key, balance_seconds, ads_off, skins_unlocked",
    )
    .bind(device_key)
    .bind(token_hash)
    .fetch_one(pool)
    .await
}

/// Re-registro: rotaciona o token da conta existente, preservando `id`/saldo/entitlements — é o
/// fluxo de recuperação do design (deviceKey existente, "conta corrompida" no device).
pub async fn rotate_token(
    pool: &PgPool,
    account_id: Uuid,
    new_token_hash: &[u8],
) -> sqlx::Result<()> {
    sqlx::query("update accounts set token_hash = $1 where id = $2")
        .bind(new_token_hash)
        .bind(account_id)
        .execute(pool)
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
    async fn create_then_find_by_device_key_and_token_hash() {
        let pool = test_pool().await;
        let device_key = format!("test-{}", Uuid::new_v4());
        let token_hash = Uuid::new_v4().into_bytes().to_vec();

        let created = create(&pool, &device_key, &token_hash).await.unwrap();

        assert_eq!(created.balance_seconds, 0);
        assert!(!created.ads_off);
        assert!(!created.skins_unlocked);

        let by_device = find_by_device_key(&pool, &device_key)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(by_device, created);

        let by_token = find_by_token_hash(&pool, &token_hash)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(by_token, created);
    }

    #[tokio::test]
    async fn rotate_token_kills_old_hash_keeps_same_account() {
        let pool = test_pool().await;
        let device_key = format!("test-{}", Uuid::new_v4());
        let old_hash = Uuid::new_v4().into_bytes().to_vec();
        let new_hash = Uuid::new_v4().into_bytes().to_vec();

        let created = create(&pool, &device_key, &old_hash).await.unwrap();
        rotate_token(&pool, created.id, &new_hash).await.unwrap();

        assert!(
            find_by_token_hash(&pool, &old_hash)
                .await
                .unwrap()
                .is_none()
        );
        let by_new = find_by_token_hash(&pool, &new_hash).await.unwrap().unwrap();
        assert_eq!(by_new.id, created.id);
    }

    #[tokio::test]
    async fn token_hash_is_unique() {
        let pool = test_pool().await;
        let token_hash = Uuid::new_v4().into_bytes().to_vec();
        create(&pool, &format!("test-{}", Uuid::new_v4()), &token_hash)
            .await
            .unwrap();

        let err = create(&pool, &format!("test-{}", Uuid::new_v4()), &token_hash)
            .await
            .unwrap_err();
        let db_err = err
            .as_database_error()
            .expect("violação de unique é erro do Postgres");
        assert_eq!(db_err.code().as_deref(), Some("23505"));
        assert!(
            db_err.message().contains("accounts_token_hash_idx"),
            "mensagem foi: {}",
            db_err.message()
        );
    }
}

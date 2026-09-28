use std::time::Duration;

use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::hash_token;
use crate::db::ledger::revoke;
use crate::google::publisher::PublisherClient;

/// Roda `process_once` a cada `interval` — nunca para em erro (loga e tenta de novo na próxima
/// janela); é assim que o design trata reembolsos: melhor esforço, não é caminho crítico de
/// request.
pub async fn run_periodically(pool: PgPool, publisher: PublisherClient, interval: Duration) {
    let mut ticker = tokio::time::interval(interval);
    loop {
        ticker.tick().await;
        match process_once(&pool, &publisher).await {
            Ok(reverted) => {
                if reverted > 0 {
                    tracing::info!(reverted, "voided purchases job reverted purchases");
                }
            }
            Err(e) => tracing::error!(error = %e, "voided purchases job failed"),
        }
    }
}

/// Busca `voidedpurchases.list` e reverte cada token conhecido (PAY-06). Retorna quantos foram
/// de fato revertidos nesta execução (idempotente: já revertidos antes não contam de novo).
pub async fn process_once(
    pool: &PgPool,
    publisher: &PublisherClient,
) -> Result<usize, crate::google::publisher::PublisherError> {
    let voided = publisher.list_voided_purchases().await?;

    let mut reverted = 0;
    for purchase in &voided.voided_purchases {
        let token_hash = hash_token(purchase.purchase_token.as_bytes());
        if revoke_purchase(pool, &token_hash).await {
            reverted += 1;
        }
    }
    Ok(reverted)
}

/// `true` se esta chamada de fato reverteu algo (primeira vez que vemos este token voided);
/// `false` se o token é desconhecido ou já tinha sido revertido antes (idempotência).
async fn revoke_purchase(pool: &PgPool, token_hash: &[u8]) -> bool {
    let mut tx = match pool.begin().await {
        Ok(tx) => tx,
        Err(e) => {
            tracing::error!(error = %e, "failed to begin tx for voided purchase");
            return false;
        }
    };

    let row: Option<(Uuid, i64)> = match sqlx::query_as(
        "update purchases set status = 'voided' where token_hash = $1 and status != 'voided' \
         returning account_id, credited_seconds",
    )
    .bind(token_hash)
    .fetch_optional(&mut *tx)
    .await
    {
        Ok(row) => row,
        Err(e) => {
            tracing::error!(error = %e, "failed to mark purchase as voided");
            return false;
        }
    };

    let Some((account_id, credited_seconds)) = row else {
        return false;
    };

    if let Err(e) = revoke(
        &mut tx,
        account_id,
        -credited_seconds,
        Some("voided_purchase"),
    )
    .await
    {
        tracing::error!(error = %e, "failed to revoke ledger for voided purchase");
        return false;
    }

    if let Err(e) = recompute_entitlements(&mut tx, account_id).await {
        tracing::error!(error = %e, "failed to recompute entitlements after void");
        return false;
    }

    if let Err(e) = tx.commit().await {
        tracing::error!(error = %e, "failed to commit voided purchase revert");
        return false;
    }

    true
}

/// Entitlements ligados se AINDA existir alguma compra não-voided que os concede — recomputado do
/// zero a cada void (não decrementado), evitando drift entre múltiplas compras que concedem
/// entitlements na mesma conta.
async fn recompute_entitlements(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    account_id: Uuid,
) -> sqlx::Result<()> {
    let (still_entitled,): (bool,) = sqlx::query_as(
        "select exists( \
             select 1 from purchases \
             where account_id = $1 and status != 'voided' and granted_entitlements = true \
         )",
    )
    .bind(account_id)
    .fetch_one(&mut **tx)
    .await?;

    sqlx::query("update accounts set ads_off = $1, skins_unlocked = $1 where id = $2")
        .bind(still_entitled)
        .bind(account_id)
        .execute(&mut **tx)
        .await?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::accounts;
    use crate::db::ledger::{LedgerReason, credit};
    use crate::google::auth::ServiceAccountKey;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

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

    async fn mock_voided(tokens: &[&str]) -> (MockServer, PublisherClient) {
        let google = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "fake-access-token",
                "token_type": "Bearer",
                "expires_in": 3600
            })))
            .mount(&google)
            .await;
        Mock::given(method("GET"))
            .and(path("/androidpublisher/v3/applications/com.keyflow/purchases/voidedpurchases"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "voidedPurchases": tokens.iter().map(|t| serde_json::json!({"purchaseToken": t})).collect::<Vec<_>>()
            })))
            .mount(&google)
            .await;

        let publisher = PublisherClient::new(
            reqwest::Client::new(),
            google.uri(),
            "com.keyflow".to_string(),
            ServiceAccountKey {
                client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
                private_key: include_str!("../../tests/fixtures/test_service_account_key.pem")
                    .to_string(),
                token_uri: format!("{}/token", google.uri()),
            },
        );
        (google, publisher)
    }

    /// Simula uma compra já "redeemed" (como a T8 deixaria) para o job reverter.
    async fn seed_redeemed_purchase(
        pool: &PgPool,
        account_id: Uuid,
        purchase_token: &str,
        credited_seconds: i64,
        grants_entitlements: bool,
    ) {
        let token_hash = hash_token(purchase_token.as_bytes());
        sqlx::query(
            "insert into purchases \
                 (token_hash, account_id, product_id, status, credited_seconds, granted_entitlements) \
             values ($1, $2, 'credits_20', 'redeemed', $3, $4)",
        )
        .bind(&token_hash)
        .bind(account_id)
        .bind(credited_seconds)
        .bind(grants_entitlements)
        .execute(pool)
        .await
        .unwrap();
        credit(
            pool,
            account_id,
            credited_seconds,
            LedgerReason::Purchase,
            Some(purchase_token),
        )
        .await
        .unwrap();
        if grants_entitlements {
            sqlx::query("update accounts set ads_off = true, skins_unlocked = true where id = $1")
                .bind(account_id)
                .execute(pool)
                .await
                .unwrap();
        }
    }

    #[tokio::test]
    async fn refund_reverts_credit_and_entitlements() {
        let pool = test_pool().await;
        let device_key = format!("device-{}", Uuid::new_v4());
        let account = accounts::create(&pool, &device_key, Uuid::new_v4().into_bytes().as_ref())
            .await
            .unwrap();
        let purchase_token = format!("tok-{}", Uuid::new_v4());
        seed_redeemed_purchase(&pool, account.id, &purchase_token, 1500, true).await;

        let (_google, publisher) = mock_voided(&[&purchase_token]).await;

        let reverted = process_once(&pool, &publisher).await.unwrap();

        assert_eq!(reverted, 1);
        let after = accounts::find_by_device_key(&pool, &device_key)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(after.balance_seconds, 0);
        assert!(
            !after.ads_off,
            "sem outras compras válidas, entitlements devem cair"
        );
        assert!(!after.skins_unlocked);
    }

    #[tokio::test]
    async fn negative_balance_after_void_is_allowed() {
        let pool = test_pool().await;
        let device_key = format!("device-{}", Uuid::new_v4());
        let account = accounts::create(&pool, &device_key, Uuid::new_v4().into_bytes().as_ref())
            .await
            .unwrap();
        let purchase_token = format!("tok-{}", Uuid::new_v4());
        seed_redeemed_purchase(&pool, account.id, &purchase_token, 600, false).await;

        // Gasta o saldo antes do reembolso (equivalente ao app já ter usado os minutos).
        crate::db::ledger::debit_guarded(&pool, account.id, 600, LedgerReason::Transcription, None)
            .await
            .unwrap();

        let (_google, publisher) = mock_voided(&[&purchase_token]).await;
        process_once(&pool, &publisher).await.unwrap();

        let after = accounts::find_by_device_key(&pool, &device_key)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            after.balance_seconds, -600,
            "revoke não tem guarda — saldo pode negativar"
        );
    }

    #[tokio::test]
    async fn running_job_twice_does_not_double_revert() {
        let pool = test_pool().await;
        let device_key = format!("device-{}", Uuid::new_v4());
        let account = accounts::create(&pool, &device_key, Uuid::new_v4().into_bytes().as_ref())
            .await
            .unwrap();
        let purchase_token = format!("tok-{}", Uuid::new_v4());
        seed_redeemed_purchase(&pool, account.id, &purchase_token, 900, false).await;

        let (_google, publisher) = mock_voided(&[&purchase_token]).await;

        let first = process_once(&pool, &publisher).await.unwrap();
        let second = process_once(&pool, &publisher).await.unwrap();

        assert_eq!(first, 1);
        assert_eq!(second, 0, "2ª execução não deve reverter de novo");
        let after = accounts::find_by_device_key(&pool, &device_key)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(after.balance_seconds, 0, "saldo não deve cair duas vezes");
    }

    #[tokio::test]
    async fn entitlements_survive_if_another_purchase_still_grants_them() {
        let pool = test_pool().await;
        let device_key = format!("device-{}", Uuid::new_v4());
        let account = accounts::create(&pool, &device_key, Uuid::new_v4().into_bytes().as_ref())
            .await
            .unwrap();
        let voided_token = format!("tok-{}", Uuid::new_v4());
        let kept_token = format!("tok-{}", Uuid::new_v4());
        seed_redeemed_purchase(&pool, account.id, &voided_token, 1500, true).await;
        seed_redeemed_purchase(&pool, account.id, &kept_token, 4200, true).await;

        let (_google, publisher) = mock_voided(&[&voided_token]).await;
        process_once(&pool, &publisher).await.unwrap();

        let after = accounts::find_by_device_key(&pool, &device_key)
            .await
            .unwrap()
            .unwrap();
        assert!(after.ads_off, "a 2ª compra ainda concede entitlements");
        assert!(after.skins_unlocked);
    }
}

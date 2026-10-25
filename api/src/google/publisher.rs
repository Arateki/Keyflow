use serde::Deserialize;

use super::auth::{AuthError, ServiceAccountKey, TokenCache};

const PUBLISHER_SCOPE: &str = "https://www.googleapis.com/auth/androidpublisher";

/// `purchaseState` (design conferido na discovery doc oficial, `androidpublisher.googleapis.com/
/// $discovery/rest?version=v3`, schema `ProductPurchase`, 2026-07-02): 0 Purchased, 1 Canceled,
/// 2 Pending.
pub const PURCHASE_STATE_PURCHASED: i32 = 0;
pub const PURCHASE_STATE_CANCELED: i32 = 1;
pub const PURCHASE_STATE_PENDING: i32 = 2;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProductPurchase {
    pub purchase_state: i32,
    #[serde(default)]
    pub obfuscated_external_account_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoidedPurchase {
    pub purchase_token: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct VoidedPurchasesListResponse {
    #[serde(default)]
    pub voided_purchases: Vec<VoidedPurchase>,
}

#[derive(Debug, thiserror::Error)]
pub enum PublisherError {
    #[error("failed to obtain access token: {0}")]
    Auth(#[from] AuthError),
    #[error("request to Play Developer API failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("Play Developer API returned status {0}")]
    RequestFailed(u16),
}

pub struct PublisherClient {
    http: reqwest::Client,
    base_url: String,
    package_name: String,
    service_account: ServiceAccountKey,
    tokens: TokenCache,
}

impl PublisherClient {
    pub fn new(
        http: reqwest::Client,
        base_url: String,
        package_name: String,
        service_account: ServiceAccountKey,
    ) -> Self {
        Self {
            http,
            base_url,
            package_name,
            service_account,
            tokens: TokenCache::new(),
        }
    }

    async fn access_token(&self) -> Result<String, PublisherError> {
        Ok(self
            .tokens
            .get_token(&self.http, &self.service_account, PUBLISHER_SCOPE)
            .await?)
    }

    /// `GET .../purchases/products/{productId}/tokens/{token}` — checa status/dono da compra.
    pub async fn get_product_purchase(
        &self,
        product_id: &str,
        purchase_token: &str,
    ) -> Result<ProductPurchase, PublisherError> {
        let access_token = self.access_token().await?;
        let url = format!(
            "{}/androidpublisher/v3/applications/{}/purchases/products/{}/tokens/{}",
            self.base_url, self.package_name, product_id, purchase_token
        );

        let response = self.http.get(&url).bearer_auth(access_token).send().await?;
        if !response.status().is_success() {
            return Err(PublisherError::RequestFailed(response.status().as_u16()));
        }
        Ok(response.json().await?)
    }

    /// `POST .../tokens/{token}:acknowledge` — server-side, depois de creditar (design §Compras).
    pub async fn acknowledge_product_purchase(
        &self,
        product_id: &str,
        purchase_token: &str,
    ) -> Result<(), PublisherError> {
        let access_token = self.access_token().await?;
        let url = format!(
            "{}/androidpublisher/v3/applications/{}/purchases/products/{}/tokens/{}:acknowledge",
            self.base_url, self.package_name, product_id, purchase_token
        );

        let response = self
            .http
            .post(&url)
            .bearer_auth(access_token)
            .json(&serde_json::json!({}))
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(PublisherError::RequestFailed(response.status().as_u16()));
        }
        Ok(())
    }

    /// `GET .../purchases/voidedpurchases` — compras canceladas/reembolsadas/chargeback (design
    /// §Compras, job PAY-06). Sem paginação: o job roda periodicamente e a próxima janela cobre o
    /// que ficou de fora (`startTime` default do Google já é "agora - 30 dias").
    pub async fn list_voided_purchases(
        &self,
    ) -> Result<VoidedPurchasesListResponse, PublisherError> {
        let access_token = self.access_token().await?;
        let url = format!(
            "{}/androidpublisher/v3/applications/{}/purchases/voidedpurchases",
            self.base_url, self.package_name
        );

        let response = self.http.get(&url).bearer_auth(access_token).send().await?;
        if !response.status().is_success() {
            return Err(PublisherError::RequestFailed(response.status().as_u16()));
        }
        Ok(response.json().await?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn test_key(token_uri: String) -> ServiceAccountKey {
        ServiceAccountKey {
            client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
            private_key: include_str!("../../tests/fixtures/test_service_account_key.pem")
                .to_string(),
            token_uri,
        }
    }

    async fn mock_token(google: &MockServer) {
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "fake-access-token",
                "token_type": "Bearer",
                "expires_in": 3600
            })))
            .mount(google)
            .await;
    }

    #[tokio::test]
    async fn get_product_purchase_parses_response() {
        let google = MockServer::start().await;
        mock_token(&google).await;
        Mock::given(method("GET"))
            .and(path(
                "/androidpublisher/v3/applications/com.arateki.keyflow/purchases/products/credits_20/tokens/tok-1",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "purchaseState": 0,
                "obfuscatedExternalAccountId": "abc123"
            })))
            .mount(&google)
            .await;

        let client = PublisherClient::new(
            reqwest::Client::new(),
            google.uri(),
            "com.arateki.keyflow".to_string(),
            test_key(format!("{}/token", google.uri())),
        );

        let purchase = client
            .get_product_purchase("credits_20", "tok-1")
            .await
            .unwrap();

        assert_eq!(purchase.purchase_state, PURCHASE_STATE_PURCHASED);
        assert_eq!(
            purchase.obfuscated_external_account_id.as_deref(),
            Some("abc123")
        );
    }

    #[tokio::test]
    async fn acknowledge_sends_expected_request() {
        let google = MockServer::start().await;
        mock_token(&google).await;
        Mock::given(method("POST"))
            .and(path(
                "/androidpublisher/v3/applications/com.arateki.keyflow/purchases/products/credits_20/tokens/tok-1:acknowledge",
            ))
            .respond_with(ResponseTemplate::new(200))
            .expect(1)
            .mount(&google)
            .await;

        let client = PublisherClient::new(
            reqwest::Client::new(),
            google.uri(),
            "com.arateki.keyflow".to_string(),
            test_key(format!("{}/token", google.uri())),
        );

        client
            .acknowledge_product_purchase("credits_20", "tok-1")
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn list_voided_purchases_parses_response() {
        let google = MockServer::start().await;
        mock_token(&google).await;
        Mock::given(method("GET"))
            .and(path(
                "/androidpublisher/v3/applications/com.arateki.keyflow/purchases/voidedpurchases",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "voidedPurchases": [
                    { "purchaseToken": "voided-tok-1" },
                    { "purchaseToken": "voided-tok-2" }
                ]
            })))
            .mount(&google)
            .await;

        let client = PublisherClient::new(
            reqwest::Client::new(),
            google.uri(),
            "com.arateki.keyflow".to_string(),
            test_key(format!("{}/token", google.uri())),
        );

        let response = client.list_voided_purchases().await.unwrap();

        assert_eq!(response.voided_purchases.len(), 2);
        assert_eq!(response.voided_purchases[0].purchase_token, "voided-tok-1");
    }
}

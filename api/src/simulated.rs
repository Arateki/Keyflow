//! Recibos exclusivos do teste de compra em staging. A chave HMAC é configurada à parte e
//! o verificador só é consultado por rotas cobertas pelo flag `SIMULATED_PURCHASES_ENABLED`.
use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hmac::{Hmac, KeyInit, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};

type HmacSha256 = Hmac<Sha256>;
const PREFIX: &str = "kfsim1";
const MAX_AGE_SECS: i64 = 24 * 60 * 60;
const FUTURE_SKEW_SECS: i64 = 30;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SimulatedReceipt {
    pub purpose: String,
    pub device_key: String,
    pub product_id: Option<String>,
    pub nonce: String,
    pub issued_at: i64,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ReceiptError {
    #[error("formato inválido")]
    Format,
    #[error("assinatura inválida")]
    Signature,
    #[error("recibo expirado")]
    Expired,
    #[error("recibo não corresponde à operação")]
    Mismatch,
}

/// Verifica HMAC antes de confiar nos campos; comparação da assinatura é constante.
pub fn verify(
    token: &str,
    secret: &str,
    expected_purpose: &str,
    expected_device_key: &str,
    expected_product_id: Option<&str>,
) -> Result<SimulatedReceipt, ReceiptError> {
    let mut pieces = token.split('.');
    if pieces.next() != Some(PREFIX) {
        return Err(ReceiptError::Format);
    }
    let payload_part = pieces.next().ok_or(ReceiptError::Format)?;
    let signature_part = pieces.next().ok_or(ReceiptError::Format)?;
    if pieces.next().is_some() {
        return Err(ReceiptError::Format);
    }
    let signature = URL_SAFE_NO_PAD
        .decode(signature_part)
        .map_err(|_| ReceiptError::Format)?;
    let mut mac =
        HmacSha256::new_from_slice(secret.as_bytes()).map_err(|_| ReceiptError::Signature)?;
    mac.update(format!("{PREFIX}.{payload_part}").as_bytes());
    mac.verify_slice(&signature)
        .map_err(|_| ReceiptError::Signature)?;

    let payload = URL_SAFE_NO_PAD
        .decode(payload_part)
        .map_err(|_| ReceiptError::Format)?;
    let receipt: SimulatedReceipt =
        serde_json::from_slice(&payload).map_err(|_| ReceiptError::Format)?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| ReceiptError::Expired)?
        .as_secs() as i64;
    if receipt.issued_at > now + FUTURE_SKEW_SECS
        || now.saturating_sub(receipt.issued_at) > MAX_AGE_SECS
    {
        return Err(ReceiptError::Expired);
    }
    if receipt.purpose != expected_purpose
        || receipt.device_key != expected_device_key
        || receipt.product_id.as_deref() != expected_product_id
        || receipt.nonce.len() < 16
        || receipt.nonce.len() > 128
    {
        return Err(ReceiptError::Mismatch);
    }
    Ok(receipt)
}

/// Emite recibo só em testes Rust. A app de debug produz o mesmo envelope com a chave de teste
/// externa; nenhuma chave privada de produção ou credencial do Google participa.
#[cfg(test)]
fn sign_for_test(secret: &str, receipt: &SimulatedReceipt) -> String {
    let payload = serde_json::to_vec(receipt).unwrap();
    let payload_part = URL_SAFE_NO_PAD.encode(payload);
    let message = format!("{PREFIX}.{payload_part}");
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes()).unwrap();
    mac.update(message.as_bytes());
    format!(
        "{message}.{}",
        URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_KEY: &str = "fake-simulation-key-for-tests-only-0001";

    fn receipt() -> SimulatedReceipt {
        SimulatedReceipt {
            purpose: "purchase".to_string(),
            device_key: "00000000-0000-4000-8000-000000000001".to_string(),
            product_id: Some("unlock_forever".to_string()),
            nonce: "test-nonce-0123456789abcdef".to_string(),
            issued_at: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs() as i64,
        }
    }

    #[test]
    fn signed_receipt_is_bound_to_purpose_device_and_product() {
        let signed = sign_for_test(TEST_KEY, &receipt());
        assert!(
            verify(
                &signed,
                TEST_KEY,
                "purchase",
                "00000000-0000-4000-8000-000000000001",
                Some("unlock_forever")
            )
            .is_ok()
        );
        assert_eq!(
            verify(
                &signed,
                TEST_KEY,
                "refund",
                "00000000-0000-4000-8000-000000000001",
                Some("unlock_forever")
            ),
            Err(ReceiptError::Mismatch)
        );
        assert_eq!(
            verify(
                &signed,
                TEST_KEY,
                "purchase",
                "other-device",
                Some("unlock_forever")
            ),
            Err(ReceiptError::Mismatch)
        );
        assert_eq!(
            verify(
                &signed,
                TEST_KEY,
                "purchase",
                "00000000-0000-4000-8000-000000000001",
                Some("credits_20")
            ),
            Err(ReceiptError::Mismatch)
        );
    }

    #[test]
    fn modified_or_expired_receipts_are_rejected() {
        let signed = sign_for_test(TEST_KEY, &receipt());
        let mut altered = signed.clone().into_bytes();
        let last = altered.len() - 1;
        altered[last] = if altered[last] == b'A' { b'B' } else { b'A' };
        assert_eq!(
            verify(
                std::str::from_utf8(&altered).unwrap(),
                TEST_KEY,
                "purchase",
                "00000000-0000-4000-8000-000000000001",
                Some("unlock_forever")
            ),
            Err(ReceiptError::Signature)
        );

        let mut old = receipt();
        old.issued_at -= MAX_AGE_SECS + 1;
        assert_eq!(
            verify(
                &sign_for_test(TEST_KEY, &old),
                TEST_KEY,
                "purchase",
                &old.device_key,
                Some("unlock_forever")
            ),
            Err(ReceiptError::Expired)
        );

        let mut extreme_old = receipt();
        extreme_old.issued_at = i64::MIN;
        assert_eq!(
            verify(
                &sign_for_test(TEST_KEY, &extreme_old),
                TEST_KEY,
                "purchase",
                &extreme_old.device_key,
                Some("unlock_forever")
            ),
            Err(ReceiptError::Expired)
        );
    }
}

//! Verificação ECDSA das callbacks de rewarded SSV do AdMob (design §Rewarded, ADS-01).
//! Algoritmo e endpoint conferidos na doc oficial
//! (developers.google.com/admob/android/rewarded-video-ssv, 2026-07-03):
//! - Conteúdo assinado = a query string inteira **até** (exclusive) `&signature=`.
//! - `signature` (base64url) + `key_id` (inteiro) vêm depois, nessa ordem, sempre por último.
//! - ECDSA P-256, SHA-256, assinatura em DER — chave pública buscada em
//!   `https://www.gstatic.com/admob/reward/verifier-keys.json` (JSON `{"keys":[{"keyId","pem"}]}`),
//!   cacheável, mas nunca por mais de 24h (rotação regular).

use std::collections::HashMap;
use std::sync::Mutex;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use serde::Deserialize;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SsvVerifyError {
    #[error("missing signature query parameter")]
    MissingSignature,
    #[error("missing key_id query parameter")]
    MissingKeyId,
    #[error("key_id is not a valid integer")]
    InvalidKeyId,
    #[error("signature is not valid base64url")]
    InvalidSignatureEncoding,
    #[error("signature is not a valid DER ECDSA signature")]
    InvalidSignatureFormat,
    #[error("no public key known for key_id {0}")]
    UnknownKeyId(u64),
    #[error("signature does not match the content")]
    SignatureMismatch,
}

/// Extrai `(conteúdo assinado, key_id, signature bytes)` de uma query string crua de callback SSV
/// — nunca reordena/reserializa (a doc é explícita: "Content ... should not be modified in any
/// way, including keeping the order of query parameters").
fn split_signed_content(raw_query: &str) -> Result<(&str, u64, Vec<u8>), SsvVerifyError> {
    const SIGNATURE_PARAM: &str = "signature=";
    const KEY_ID_PARAM: &str = "key_id=";

    let sig_start = raw_query
        .find(SIGNATURE_PARAM)
        .ok_or(SsvVerifyError::MissingSignature)?;
    // -1 porque tem um '&' antes de "signature=" (exceto se fosse o único param, que nunca é o
    // caso real de uma callback do AdMob).
    let content = &raw_query[..sig_start.saturating_sub(1)];

    let after_sig = &raw_query[sig_start + SIGNATURE_PARAM.len()..];
    let key_id_marker = after_sig
        .find(KEY_ID_PARAM)
        .ok_or(SsvVerifyError::MissingKeyId)?;
    let sig_b64 = &after_sig[..key_id_marker.saturating_sub(1)];
    let key_id_str = after_sig[key_id_marker + KEY_ID_PARAM.len()..]
        .split('&')
        .next()
        .unwrap_or_default();

    let key_id: u64 = key_id_str
        .parse()
        .map_err(|_| SsvVerifyError::InvalidKeyId)?;
    let signature_bytes = URL_SAFE_NO_PAD
        .decode(sig_b64)
        .map_err(|_| SsvVerifyError::InvalidSignatureEncoding)?;

    Ok((content, key_id, signature_bytes))
}

/// Verifica a assinatura ECDSA de uma callback SSV crua contra o mapa de chaves públicas
/// conhecidas (indexado por `key_id`). Retorna o conteúdo assinado (pra extrair os parâmetros de
/// negócio depois) só se a assinatura bater.
pub fn verify<'a>(
    raw_query: &'a str,
    known_keys: &HashMap<u64, VerifyingKey>,
) -> Result<&'a str, SsvVerifyError> {
    let (content, key_id, signature_bytes) = split_signed_content(raw_query)?;

    let public_key = known_keys
        .get(&key_id)
        .ok_or(SsvVerifyError::UnknownKeyId(key_id))?;
    let signature = Signature::from_der(&signature_bytes)
        .map_err(|_| SsvVerifyError::InvalidSignatureFormat)?;

    public_key
        .verify(content.as_bytes(), &signature)
        .map_err(|_| SsvVerifyError::SignatureMismatch)?;

    Ok(content)
}

#[derive(Debug, Deserialize)]
struct VerifierKeysResponse {
    keys: Vec<VerifierKeyEntry>,
}

#[derive(Debug, Deserialize)]
struct VerifierKeyEntry {
    #[serde(rename = "keyId")]
    key_id: u64,
    pem: String,
}

#[derive(Debug, thiserror::Error)]
pub enum AdmobKeysError {
    #[error("failed to fetch verifier keys: {0}")]
    Http(#[from] reqwest::Error),
    #[error("verifier keys endpoint returned status {0}")]
    RequestFailed(u16),
    #[error("failed to parse a public key from the verifier keys response")]
    InvalidKey,
}

/// Cache das chaves públicas do AdMob — refetch completo quando um `key_id` desconhecido aparece
/// (design: "cacheadas e re-buscadas quando key_id desconhecido"; nunca por mais de 24h segundo a
/// doc oficial, mas o refresh-on-miss já cobre rotação na prática sem precisar de um timer).
pub struct AdmobKeysCache {
    http: reqwest::Client,
    url: String,
    keys: Mutex<HashMap<u64, VerifyingKey>>,
}

impl AdmobKeysCache {
    pub fn new(http: reqwest::Client, url: String) -> Self {
        Self {
            http,
            url,
            keys: Mutex::new(HashMap::new()),
        }
    }

    /// Mapa de chaves conhecidas *neste instante*; chame `refresh` antes se `key_id` não estiver
    /// aqui — não faz a busca sozinho pra manter esta função sync e barata de clonar.
    pub fn snapshot(&self) -> HashMap<u64, VerifyingKey> {
        self.keys.lock().unwrap().clone()
    }

    pub async fn refresh(&self) -> Result<(), AdmobKeysError> {
        let response = self.http.get(&self.url).send().await?;
        if !response.status().is_success() {
            return Err(AdmobKeysError::RequestFailed(response.status().as_u16()));
        }
        let body: VerifierKeysResponse = response.json().await?;

        let mut parsed = HashMap::with_capacity(body.keys.len());
        for entry in body.keys {
            let key = VerifyingKey::from_public_key_pem(&entry.pem)
                .map_err(|_| AdmobKeysError::InvalidKey)?;
            parsed.insert(entry.key_id, key);
        }

        *self.keys.lock().unwrap() = parsed;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::SigningKey;
    use p256::ecdsa::signature::Signer;
    use rand_core::OsRng;

    fn sign(signing_key: &SigningKey, content: &str) -> String {
        let signature: Signature = signing_key.sign(content.as_bytes());
        URL_SAFE_NO_PAD.encode(signature.to_der().as_bytes())
    }

    #[test]
    fn valid_signature_verifies_and_returns_content() {
        let signing_key = SigningKey::random(&mut OsRng);
        let verifying_key = VerifyingKey::from(&signing_key);
        let content = "ad_network=1&custom_data=abc&transaction_id=tx1";
        let sig = sign(&signing_key, content);
        let raw_query = format!("{content}&signature={sig}&key_id=42");

        let mut keys = HashMap::new();
        keys.insert(42, verifying_key);

        let verified_content = verify(&raw_query, &keys).unwrap();
        assert_eq!(verified_content, content);
    }

    #[test]
    fn tampered_content_fails_verification() {
        let signing_key = SigningKey::random(&mut OsRng);
        let verifying_key = VerifyingKey::from(&signing_key);
        let content = "ad_network=1&custom_data=abc&transaction_id=tx1";
        let sig = sign(&signing_key, content);
        // Adultera um parâmetro depois de assinar.
        let raw_query =
            format!("ad_network=1&custom_data=abc&transaction_id=tx2&signature={sig}&key_id=42");

        let mut keys = HashMap::new();
        keys.insert(42, verifying_key);

        assert_eq!(
            verify(&raw_query, &keys),
            Err(SsvVerifyError::SignatureMismatch)
        );
    }

    #[test]
    fn unknown_key_id_is_rejected() {
        let signing_key = SigningKey::random(&mut OsRng);
        let content = "transaction_id=tx1";
        let sig = sign(&signing_key, content);
        let raw_query = format!("{content}&signature={sig}&key_id=999");

        let keys = HashMap::new(); // vazio — key_id 999 nunca visto.

        assert_eq!(
            verify(&raw_query, &keys),
            Err(SsvVerifyError::UnknownKeyId(999))
        );
    }

    #[test]
    fn wrong_key_fails_verification() {
        let signing_key = SigningKey::random(&mut OsRng);
        let other_key = SigningKey::random(&mut OsRng);
        let content = "transaction_id=tx1";
        let sig = sign(&signing_key, content);
        let raw_query = format!("{content}&signature={sig}&key_id=1");

        let mut keys = HashMap::new();
        keys.insert(1, VerifyingKey::from(&other_key));

        assert_eq!(
            verify(&raw_query, &keys),
            Err(SsvVerifyError::SignatureMismatch)
        );
    }

    #[test]
    fn missing_signature_param_is_rejected() {
        let keys = HashMap::new();
        assert_eq!(
            verify("transaction_id=tx1&key_id=1", &keys),
            Err(SsvVerifyError::MissingSignature)
        );
    }

    #[test]
    fn missing_key_id_param_is_rejected() {
        let keys = HashMap::new();
        assert_eq!(
            verify("transaction_id=tx1&signature=abc", &keys),
            Err(SsvVerifyError::MissingKeyId)
        );
    }
}

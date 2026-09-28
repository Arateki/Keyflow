use serde::{Deserialize, Serialize};

use super::auth::{AuthError, ServiceAccountKey, TokenCache};

const INTEGRITY_SCOPE: &str = "https://www.googleapis.com/auth/playintegrity";

/// Contrato exato conferido na discovery doc oficial (`playintegrity.googleapis.com/$discovery/
/// rest?version=v1`, 2026-07-02) — schemas `DecodeIntegrityTokenRequest`/`...Response`/
/// `TokenPayloadExternal` — e em developer.android.com/google/play/integrity/verdicts.
#[derive(Debug, Clone, Serialize)]
struct DecodeIntegrityTokenRequest<'a> {
    integrity_token: &'a str,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DecodeIntegrityTokenResponse {
    token_payload_external: TokenPayloadExternal,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TokenPayloadExternal {
    pub request_details: RequestDetails,
    pub app_integrity: AppIntegrity,
    #[serde(default)]
    pub device_integrity: DeviceIntegrity,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RequestDetails {
    pub request_package_name: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppIntegrity {
    pub app_recognition_verdict: String,
}

/// `deviceRecognitionVerdict` é omitido pelo Google quando o device não passa em nenhum label —
/// por isso `#[serde(default)]` (payload vira lista vazia, não erro de parse).
#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct DeviceIntegrity {
    #[serde(default)]
    pub device_recognition_verdict: Vec<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum IntegrityError {
    #[error("failed to obtain access token: {0}")]
    Auth(#[from] AuthError),
    #[error("request to Play Integrity failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("Play Integrity returned status {0}")]
    RequestFailed(u16),
}

pub struct IntegrityClient {
    http: reqwest::Client,
    base_url: String,
    package_name: String,
    service_account: ServiceAccountKey,
    tokens: TokenCache,
}

impl IntegrityClient {
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

    pub async fn decode(
        &self,
        integrity_token: &str,
    ) -> Result<TokenPayloadExternal, IntegrityError> {
        let token = self
            .tokens
            .get_token(&self.http, &self.service_account, INTEGRITY_SCOPE)
            .await?;

        let url = format!(
            "{}/v1/{}:decodeIntegrityToken",
            self.base_url, self.package_name
        );
        let response = self
            .http
            .post(&url)
            .bearer_auth(token)
            .json(&DecodeIntegrityTokenRequest { integrity_token })
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(IntegrityError::RequestFailed(response.status().as_u16()));
        }

        let body: DecodeIntegrityTokenResponse = response.json().await?;
        Ok(body.token_payload_external)
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum VerdictError {
    #[error("unexpected package name: {0}")]
    WrongPackage(String),
    #[error("app recognition verdict rejected: {0}")]
    AppNotRecognized(String),
    #[error("device does not meet integrity requirements")]
    DeviceNotTrusted,
}

/// Valida os verdicts exigidos pelo design (§Identidade): pacote correto, app reconhecido pelo
/// Play (`relax_app_recognition` permite builds de teste/interno sem `PLAY_RECOGNIZED`) e device
/// íntegro (`MEETS_DEVICE_INTEGRITY` presente).
pub fn verify_verdicts(
    payload: &TokenPayloadExternal,
    expected_package: &str,
    relax_app_recognition: bool,
) -> Result<(), VerdictError> {
    if payload.request_details.request_package_name != expected_package {
        return Err(VerdictError::WrongPackage(
            payload.request_details.request_package_name.clone(),
        ));
    }

    if !relax_app_recognition && payload.app_integrity.app_recognition_verdict != "PLAY_RECOGNIZED"
    {
        return Err(VerdictError::AppNotRecognized(
            payload.app_integrity.app_recognition_verdict.clone(),
        ));
    }

    if !payload
        .device_integrity
        .device_recognition_verdict
        .iter()
        .any(|v| v == "MEETS_DEVICE_INTEGRITY")
    {
        return Err(VerdictError::DeviceNotTrusted);
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload(package: &str, app_verdict: &str, device_verdicts: &[&str]) -> TokenPayloadExternal {
        TokenPayloadExternal {
            request_details: RequestDetails {
                request_package_name: package.to_string(),
            },
            app_integrity: AppIntegrity {
                app_recognition_verdict: app_verdict.to_string(),
            },
            device_integrity: DeviceIntegrity {
                device_recognition_verdict: device_verdicts.iter().map(|s| s.to_string()).collect(),
            },
        }
    }

    #[test]
    fn accepts_valid_verdicts() {
        let p = payload(
            "com.keyflow",
            "PLAY_RECOGNIZED",
            &["MEETS_DEVICE_INTEGRITY"],
        );
        assert_eq!(verify_verdicts(&p, "com.keyflow", false), Ok(()));
    }

    #[test]
    fn rejects_wrong_package() {
        let p = payload("com.evil", "PLAY_RECOGNIZED", &["MEETS_DEVICE_INTEGRITY"]);
        assert_eq!(
            verify_verdicts(&p, "com.keyflow", false),
            Err(VerdictError::WrongPackage("com.evil".to_string()))
        );
    }

    #[test]
    fn rejects_device_out_of_integrity() {
        // deviceRecognitionVerdict vazio = "sinais de ataque/root/emulador" (doc oficial).
        let p = payload("com.keyflow", "PLAY_RECOGNIZED", &[]);
        assert_eq!(
            verify_verdicts(&p, "com.keyflow", false),
            Err(VerdictError::DeviceNotTrusted)
        );
    }

    #[test]
    fn rejects_unrecognized_app_without_relax_flag() {
        let p = payload(
            "com.keyflow",
            "UNRECOGNIZED_VERSION",
            &["MEETS_DEVICE_INTEGRITY"],
        );
        assert_eq!(
            verify_verdicts(&p, "com.keyflow", false),
            Err(VerdictError::AppNotRecognized(
                "UNRECOGNIZED_VERSION".to_string()
            ))
        );
    }

    #[test]
    fn accepts_unrecognized_app_with_relax_flag() {
        let p = payload(
            "com.keyflow",
            "UNRECOGNIZED_VERSION",
            &["MEETS_DEVICE_INTEGRITY"],
        );
        assert_eq!(verify_verdicts(&p, "com.keyflow", true), Ok(()));
    }

    #[test]
    fn relax_flag_does_not_bypass_package_or_device_checks() {
        let p = payload("com.evil", "UNRECOGNIZED_VERSION", &[]);
        assert_eq!(
            verify_verdicts(&p, "com.keyflow", true),
            Err(VerdictError::WrongPackage("com.evil".to_string()))
        );
    }
}

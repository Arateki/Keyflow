use std::collections::HashMap;
use std::fmt;
use std::net::SocketAddr;
use std::num::NonZeroU32;

const DEFAULT_BIND_ADDR: &str = "0.0.0.0:8080";
const DEFAULT_REGISTER_RATE_LIMIT_PER_MINUTE: u32 = 5;
const DEFAULT_ACCOUNT_RATE_LIMIT_PER_MINUTE: u32 = 60;
const DEFAULT_VOIDED_PURCHASES_JOB_INTERVAL_SECS: u32 = 3600;
const DEFAULT_TRANSCRIPTION_TIMEOUT_MS: u32 = 30_000;
const DEFAULT_TRANSCRIPTION_MAX_BYTES: u32 = 26_214_400; // 25 MiB — mesmo placeholder da T10.
const DEFAULT_PROVIDER_UNHEALTHY_COOLDOWN_SECS: u32 = 60;

#[derive(Debug, Clone)]
pub struct Config {
    pub database_url: String,
    pub bind_addr: SocketAddr,
    /// Caminho do JSON de credencial da service account do Google (Play Integrity/Developer API).
    pub google_service_account_json_path: String,
    /// Package name do app Android (`com.keyflow`) — conferido contra `requestPackageName`.
    pub package_name: String,
    /// Relaxa `appRecognitionVerdict` != PLAY_RECOGNIZED (builds de teste/interno, design
    /// §Identidade) — nunca relaxa pacote nem device.
    pub integrity_relaxed: bool,
    /// Limite de `POST /v1/register` por IP (ID-04).
    pub register_rate_limit_per_minute: NonZeroU32,
    /// Limite das rotas autenticadas por conta (ID-04).
    pub account_rate_limit_per_minute: NonZeroU32,
    /// Intervalo do job de `voidedpurchases.list` (PAY-06).
    pub voided_purchases_job_interval_secs: NonZeroU32,
    /// Provedores de transcrição, na ordem de tentativa (design §Proxy, TRX-05).
    pub providers: Vec<crate::providers::ResolvedProvider>,
    pub transcription_timeout_ms: NonZeroU32,
    pub transcription_max_bytes: NonZeroU32,
    /// Cooldown do circuit breaker entre requests (ver `providers::ProviderHealth`).
    pub provider_unhealthy_cooldown_secs: NonZeroU32,
}

#[derive(Debug)]
pub enum ConfigError {
    MissingEnv(&'static str),
    InvalidEnv {
        var: &'static str,
        value: String,
        reason: String,
    },
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConfigError::MissingEnv(var) => {
                write!(f, "missing required environment variable: {var}")
            }
            ConfigError::InvalidEnv { var, value, reason } => {
                write!(f, "invalid value for {var} ({value:?}): {reason}")
            }
        }
    }
}

impl std::error::Error for ConfigError {}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_map(&std::env::vars().collect())
    }

    /// Lê a config de um mapa em vez do processo real — isola os testes de env vars globais
    /// (mutar `std::env` é estado compartilhado do processo e corre risco de vazar entre
    /// `#[test]`s executando em paralelo no mesmo binário).
    fn from_map(vars: &HashMap<String, String>) -> Result<Self, ConfigError> {
        let database_url = vars
            .get("DATABASE_URL")
            .cloned()
            .ok_or(ConfigError::MissingEnv("DATABASE_URL"))?;

        let bind_addr = match vars.get("BIND_ADDR") {
            Some(value) => value
                .parse::<SocketAddr>()
                .map_err(|e| ConfigError::InvalidEnv {
                    var: "BIND_ADDR",
                    value: value.clone(),
                    reason: e.to_string(),
                })?,
            None => DEFAULT_BIND_ADDR
                .parse()
                .expect("default bind addr is valid"),
        };

        let google_service_account_json_path = vars
            .get("GOOGLE_SERVICE_ACCOUNT_JSON_PATH")
            .cloned()
            .ok_or(ConfigError::MissingEnv("GOOGLE_SERVICE_ACCOUNT_JSON_PATH"))?;

        let package_name = vars
            .get("PACKAGE_NAME")
            .cloned()
            .ok_or(ConfigError::MissingEnv("PACKAGE_NAME"))?;

        let integrity_relaxed = vars
            .get("INTEGRITY_RELAXED")
            .map(|v| v == "true" || v == "1")
            .unwrap_or(false);

        let register_rate_limit_per_minute = parse_nonzero_u32(
            vars,
            "REGISTER_RATE_LIMIT_PER_MINUTE",
            DEFAULT_REGISTER_RATE_LIMIT_PER_MINUTE,
        )?;
        let account_rate_limit_per_minute = parse_nonzero_u32(
            vars,
            "ACCOUNT_RATE_LIMIT_PER_MINUTE",
            DEFAULT_ACCOUNT_RATE_LIMIT_PER_MINUTE,
        )?;
        let voided_purchases_job_interval_secs = parse_nonzero_u32(
            vars,
            "VOIDED_PURCHASES_JOB_INTERVAL_SECS",
            DEFAULT_VOIDED_PURCHASES_JOB_INTERVAL_SECS,
        )?;

        let providers_json = vars
            .get("TRANSCRIPTION_PROVIDERS_JSON")
            .cloned()
            .ok_or(ConfigError::MissingEnv("TRANSCRIPTION_PROVIDERS_JSON"))?;
        let providers =
            crate::providers::parse_and_resolve(&providers_json, vars).map_err(|reason| {
                ConfigError::InvalidEnv {
                    var: "TRANSCRIPTION_PROVIDERS_JSON",
                    value: providers_json.clone(),
                    reason,
                }
            })?;

        let transcription_timeout_ms = parse_nonzero_u32(
            vars,
            "TRANSCRIPTION_TIMEOUT_MS",
            DEFAULT_TRANSCRIPTION_TIMEOUT_MS,
        )?;
        let transcription_max_bytes = parse_nonzero_u32(
            vars,
            "TRANSCRIPTION_MAX_BYTES",
            DEFAULT_TRANSCRIPTION_MAX_BYTES,
        )?;
        let provider_unhealthy_cooldown_secs = parse_nonzero_u32(
            vars,
            "PROVIDER_UNHEALTHY_COOLDOWN_SECS",
            DEFAULT_PROVIDER_UNHEALTHY_COOLDOWN_SECS,
        )?;

        Ok(Config {
            database_url,
            bind_addr,
            google_service_account_json_path,
            package_name,
            integrity_relaxed,
            register_rate_limit_per_minute,
            account_rate_limit_per_minute,
            voided_purchases_job_interval_secs,
            providers,
            transcription_timeout_ms,
            transcription_max_bytes,
            provider_unhealthy_cooldown_secs,
        })
    }
}

fn parse_nonzero_u32(
    vars: &HashMap<String, String>,
    var: &'static str,
    default: u32,
) -> Result<NonZeroU32, ConfigError> {
    let raw = match vars.get(var) {
        Some(value) => value,
        None => {
            return Ok(NonZeroU32::new(default).expect("default rate limit is non-zero"));
        }
    };

    let parsed: u32 =
        raw.parse()
            .map_err(|e: std::num::ParseIntError| ConfigError::InvalidEnv {
                var,
                value: raw.clone(),
                reason: e.to_string(),
            })?;

    NonZeroU32::new(parsed).ok_or(ConfigError::InvalidEnv {
        var,
        value: raw.clone(),
        reason: "must be greater than zero".to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn missing_database_url_fails_with_specific_message() {
        let err = Config::from_map(&vars(&[])).unwrap_err();

        assert_eq!(
            err.to_string(),
            "missing required environment variable: DATABASE_URL"
        );
    }

    const REQUIRED: &[(&str, &str)] = &[
        (
            "DATABASE_URL",
            "postgres://keyflow:keyflow@localhost/keyflow_api",
        ),
        (
            "GOOGLE_SERVICE_ACCOUNT_JSON_PATH",
            "/secrets/google-sa.json",
        ),
        ("PACKAGE_NAME", "com.keyflow"),
        (
            "TRANSCRIPTION_PROVIDERS_JSON",
            r#"[{"name":"primary","url":"https://p1.example/transcribe","authHeaderName":"Authorization","authValueEnv":"TEST_PROVIDER_KEY","model":"whisper-1"}]"#,
        ),
        ("TEST_PROVIDER_KEY", "test-secret"),
    ];

    fn with_required(extra: &[(&str, &str)]) -> HashMap<String, String> {
        let mut all = REQUIRED.to_vec();
        all.extend_from_slice(extra);
        vars(&all)
    }

    #[test]
    fn valid_env_produces_config() {
        let config = Config::from_map(&with_required(&[("BIND_ADDR", "127.0.0.1:9090")])).unwrap();

        assert_eq!(
            config.database_url,
            "postgres://keyflow:keyflow@localhost/keyflow_api"
        );
        assert_eq!(config.bind_addr, "127.0.0.1:9090".parse().unwrap());
        assert_eq!(
            config.google_service_account_json_path,
            "/secrets/google-sa.json"
        );
        assert_eq!(config.package_name, "com.keyflow");
        assert!(!config.integrity_relaxed);
        assert_eq!(
            config.register_rate_limit_per_minute.get(),
            DEFAULT_REGISTER_RATE_LIMIT_PER_MINUTE
        );
        assert_eq!(
            config.account_rate_limit_per_minute.get(),
            DEFAULT_ACCOUNT_RATE_LIMIT_PER_MINUTE
        );
        assert_eq!(
            config.voided_purchases_job_interval_secs.get(),
            DEFAULT_VOIDED_PURCHASES_JOB_INTERVAL_SECS
        );
        assert_eq!(config.providers.len(), 1);
        assert_eq!(config.providers[0].name, "primary");
        assert_eq!(config.providers[0].auth_value, "test-secret");
        assert_eq!(
            config.transcription_timeout_ms.get(),
            DEFAULT_TRANSCRIPTION_TIMEOUT_MS
        );
        assert_eq!(
            config.transcription_max_bytes.get(),
            DEFAULT_TRANSCRIPTION_MAX_BYTES
        );
    }

    #[test]
    fn voided_purchases_job_interval_is_configurable() {
        let config = Config::from_map(&with_required(&[(
            "VOIDED_PURCHASES_JOB_INTERVAL_SECS",
            "60",
        )]))
        .unwrap();

        assert_eq!(config.voided_purchases_job_interval_secs.get(), 60);
    }

    #[test]
    fn rate_limits_are_configurable() {
        let config = Config::from_map(&with_required(&[
            ("REGISTER_RATE_LIMIT_PER_MINUTE", "10"),
            ("ACCOUNT_RATE_LIMIT_PER_MINUTE", "120"),
        ]))
        .unwrap();

        assert_eq!(config.register_rate_limit_per_minute.get(), 10);
        assert_eq!(config.account_rate_limit_per_minute.get(), 120);
    }

    #[test]
    fn zero_rate_limit_fails_with_specific_message() {
        let err = Config::from_map(&with_required(&[("REGISTER_RATE_LIMIT_PER_MINUTE", "0")]))
            .unwrap_err();

        assert!(err.to_string().contains("REGISTER_RATE_LIMIT_PER_MINUTE"));
        assert!(err.to_string().contains("greater than zero"));
    }

    #[test]
    fn non_numeric_rate_limit_fails_with_specific_message() {
        let err = Config::from_map(&with_required(&[("ACCOUNT_RATE_LIMIT_PER_MINUTE", "many")]))
            .unwrap_err();

        assert!(err.to_string().contains("ACCOUNT_RATE_LIMIT_PER_MINUTE"));
        assert!(err.to_string().contains("many"));
    }

    #[test]
    fn missing_bind_addr_falls_back_to_default() {
        let config = Config::from_map(&with_required(&[])).unwrap();

        assert_eq!(config.bind_addr, DEFAULT_BIND_ADDR.parse().unwrap());
    }

    #[test]
    fn invalid_bind_addr_fails_with_specific_message() {
        let err = Config::from_map(&with_required(&[("BIND_ADDR", "not-an-addr")])).unwrap_err();

        assert!(err.to_string().contains("BIND_ADDR"));
        assert!(err.to_string().contains("not-an-addr"));
    }

    #[test]
    fn missing_google_service_account_path_fails_with_specific_message() {
        let err = Config::from_map(&vars(&[
            (
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            ),
            ("PACKAGE_NAME", "com.keyflow"),
        ]))
        .unwrap_err();

        assert_eq!(
            err.to_string(),
            "missing required environment variable: GOOGLE_SERVICE_ACCOUNT_JSON_PATH"
        );
    }

    #[test]
    fn missing_package_name_fails_with_specific_message() {
        let err = Config::from_map(&vars(&[
            (
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            ),
            (
                "GOOGLE_SERVICE_ACCOUNT_JSON_PATH",
                "/secrets/google-sa.json",
            ),
        ]))
        .unwrap_err();

        assert_eq!(
            err.to_string(),
            "missing required environment variable: PACKAGE_NAME"
        );
    }

    #[test]
    fn integrity_relaxed_parses_true_and_1_as_true() {
        for value in ["true", "1"] {
            let config = Config::from_map(&with_required(&[("INTEGRITY_RELAXED", value)])).unwrap();
            assert!(config.integrity_relaxed, "esperava true para {value:?}");
        }
    }

    #[test]
    fn integrity_relaxed_defaults_to_false() {
        let config = Config::from_map(&with_required(&[])).unwrap();
        assert!(!config.integrity_relaxed);
    }

    #[test]
    fn missing_transcription_providers_json_fails_with_specific_message() {
        let err = Config::from_map(&vars(&[
            (
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            ),
            (
                "GOOGLE_SERVICE_ACCOUNT_JSON_PATH",
                "/secrets/google-sa.json",
            ),
            ("PACKAGE_NAME", "com.keyflow"),
        ]))
        .unwrap_err();

        assert_eq!(
            err.to_string(),
            "missing required environment variable: TRANSCRIPTION_PROVIDERS_JSON"
        );
    }

    #[test]
    fn invalid_providers_json_fails_with_specific_message() {
        let mut map = with_required(&[]);
        map.insert(
            "TRANSCRIPTION_PROVIDERS_JSON".to_string(),
            "not json".to_string(),
        );
        let err = Config::from_map(&map).unwrap_err();

        assert!(err.to_string().contains("TRANSCRIPTION_PROVIDERS_JSON"));
    }

    #[test]
    fn transcription_timeout_and_max_bytes_are_configurable() {
        let config = Config::from_map(&with_required(&[
            ("TRANSCRIPTION_TIMEOUT_MS", "5000"),
            ("TRANSCRIPTION_MAX_BYTES", "1000"),
        ]))
        .unwrap();

        assert_eq!(config.transcription_timeout_ms.get(), 5000);
        assert_eq!(config.transcription_max_bytes.get(), 1000);
    }
}

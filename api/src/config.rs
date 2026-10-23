use std::collections::HashMap;
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::num::NonZeroU32;
use std::time::Duration;

use ipnet::IpNet;

const DEFAULT_BIND_ADDR: &str = "0.0.0.0:8080";
const DEFAULT_REGISTER_RATE_LIMIT_PER_MINUTE: u32 = 5;
const DEFAULT_ACCOUNT_RATE_LIMIT_PER_MINUTE: u32 = 60;
const DEFAULT_VOIDED_PURCHASES_JOB_INTERVAL_SECS: u32 = 3600;
const DEFAULT_TRANSCRIPTION_TIMEOUT_MS: u32 = 30_000;
const DEFAULT_TRANSCRIPTION_MAX_BYTES: u32 = 26_214_400; // 25 MiB — mesmo placeholder da T10.
const DEFAULT_PROVIDER_UNHEALTHY_COOLDOWN_SECS: u32 = 60;
const DEFAULT_ADS_REWARD_SECONDS: u32 = 60;
const DEFAULT_ADS_DAILY_CAP: u32 = 5;
/// Pool enxuto para 0,5 GB (aws.md §2.4 / §5). Testes de integração usam `db::connect`, não isto.
const DEFAULT_DB_MAX_CONNECTIONS: u32 = 5;
const DEFAULT_DB_MIN_CONNECTIONS: u32 = 0;
const DEFAULT_DB_IDLE_TIMEOUT_SECS: u32 = 300;
const DEFAULT_DB_ACQUIRE_TIMEOUT_SECS: u32 = 5;
const DEFAULT_TOKIO_WORKER_THREADS: u32 = 2;
const DEFAULT_TOKIO_MAX_BLOCKING_THREADS: u32 = 8;
const DEFAULT_PLAY_INTEGRITY_BASE_URL: &str = "https://playintegrity.googleapis.com";
const DEFAULT_ANDROID_PUBLISHER_BASE_URL: &str = "https://androidpublisher.googleapis.com";

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
    /// Segundos creditados por rewarded ad verificado (design §Rewarded) — decisão nossa, não vem
    /// do `reward_amount` do AdMob (que é só o valor configurado no console, sem unidade fixa).
    pub ads_reward_seconds: NonZeroU32,
    /// Cap diário de recompensas por conta (ADS-01).
    pub ads_daily_cap: NonZeroU32,
    /// Redes (CIDR ou IP) de onde `CF-Connecting-IP` é aceito. Vazio = header ignorado.
    pub trusted_proxies: Vec<IpNet>,
    pub db_max_connections: u32,
    pub db_min_connections: u32,
    pub db_idle_timeout: Duration,
    pub db_acquire_timeout: Duration,
    pub tokio_worker_threads: NonZeroU32,
    pub tokio_max_blocking_threads: NonZeroU32,
    /// Base do Play Integrity. Default é a URL oficial; o teste de carga aponta para o mock.
    pub play_integrity_base_url: String,
    /// Base da Play Developer API. Default é a URL oficial.
    pub android_publisher_base_url: String,
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

        let ads_reward_seconds =
            parse_nonzero_u32(vars, "ADS_REWARD_SECONDS", DEFAULT_ADS_REWARD_SECONDS)?;
        let ads_daily_cap = parse_nonzero_u32(vars, "ADS_DAILY_CAP", DEFAULT_ADS_DAILY_CAP)?;

        let trusted_proxies = parse_trusted_proxies(
            vars.get("TRUSTED_PROXIES")
                .map(String::as_str)
                .unwrap_or(""),
        )?;

        let db_max_connections =
            parse_u32_at_least(vars, "DB_MAX_CONNECTIONS", DEFAULT_DB_MAX_CONNECTIONS, 1)?;
        let db_min_connections =
            parse_u32_at_least(vars, "DB_MIN_CONNECTIONS", DEFAULT_DB_MIN_CONNECTIONS, 0)?;
        if db_min_connections > db_max_connections {
            return Err(ConfigError::InvalidEnv {
                var: "DB_MIN_CONNECTIONS",
                value: db_min_connections.to_string(),
                reason: format!("must be <= DB_MAX_CONNECTIONS ({db_max_connections})"),
            });
        }
        let db_idle_timeout = Duration::from_secs(parse_u32_at_least(
            vars,
            "DB_IDLE_TIMEOUT_SECS",
            DEFAULT_DB_IDLE_TIMEOUT_SECS,
            1,
        )? as u64);
        let db_acquire_timeout = Duration::from_secs(parse_u32_at_least(
            vars,
            "DB_ACQUIRE_TIMEOUT_SECS",
            DEFAULT_DB_ACQUIRE_TIMEOUT_SECS,
            1,
        )? as u64);
        let tokio_worker_threads =
            parse_nonzero_u32(vars, "TOKIO_WORKER_THREADS", DEFAULT_TOKIO_WORKER_THREADS)?;
        let tokio_max_blocking_threads = parse_nonzero_u32(
            vars,
            "TOKIO_MAX_BLOCKING_THREADS",
            DEFAULT_TOKIO_MAX_BLOCKING_THREADS,
        )?;
        let play_integrity_base_url = parse_nonempty_string(
            vars,
            "PLAY_INTEGRITY_BASE_URL",
            DEFAULT_PLAY_INTEGRITY_BASE_URL,
        )?;
        let android_publisher_base_url = parse_nonempty_string(
            vars,
            "ANDROID_PUBLISHER_BASE_URL",
            DEFAULT_ANDROID_PUBLISHER_BASE_URL,
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
            ads_reward_seconds,
            ads_daily_cap,
            trusted_proxies,
            db_max_connections,
            db_min_connections,
            db_idle_timeout,
            db_acquire_timeout,
            tokio_worker_threads,
            tokio_max_blocking_threads,
            play_integrity_base_url,
            android_publisher_base_url,
        })
    }

    pub fn pool_settings(&self) -> crate::db::PoolSettings {
        crate::db::PoolSettings {
            max_connections: self.db_max_connections,
            min_connections: self.db_min_connections,
            idle_timeout: self.db_idle_timeout,
            acquire_timeout: self.db_acquire_timeout,
        }
    }
}

/// `TRUSTED_PROXIES`: vazio, ou IPs/CIDRs separados por vírgula (`10.0.0.0/8, 127.0.0.1`).
/// IP sem máscara vira `/32` ou `/128`.
pub fn parse_trusted_proxies(raw: &str) -> Result<Vec<IpNet>, ConfigError> {
    let mut nets = Vec::new();
    for part in raw.split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let net = if part.contains('/') {
            part.parse::<IpNet>().map_err(|e| ConfigError::InvalidEnv {
                var: "TRUSTED_PROXIES",
                value: part.to_string(),
                reason: e.to_string(),
            })?
        } else {
            let ip = part
                .parse::<IpAddr>()
                .map_err(|e: std::net::AddrParseError| ConfigError::InvalidEnv {
                    var: "TRUSTED_PROXIES",
                    value: part.to_string(),
                    reason: e.to_string(),
                })?;
            IpNet::from(ip)
        };
        nets.push(net);
    }
    Ok(nets)
}

fn parse_nonzero_u32(
    vars: &HashMap<String, String>,
    var: &'static str,
    default: u32,
) -> Result<NonZeroU32, ConfigError> {
    // `VAR=""` conta como ausente: o default vale. Valor inválido continua erro.
    let raw = match vars.get(var).filter(|value| !value.is_empty()) {
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

fn parse_u32_at_least(
    vars: &HashMap<String, String>,
    var: &'static str,
    default: u32,
    min: u32,
) -> Result<u32, ConfigError> {
    let raw = match vars.get(var).filter(|value| !value.is_empty()) {
        Some(value) => value,
        None => return Ok(default),
    };
    let parsed: u32 =
        raw.parse()
            .map_err(|e: std::num::ParseIntError| ConfigError::InvalidEnv {
                var,
                value: raw.clone(),
                reason: e.to_string(),
            })?;
    if parsed < min {
        return Err(ConfigError::InvalidEnv {
            var,
            value: raw.clone(),
            reason: format!("must be at least {min}"),
        });
    }
    Ok(parsed)
}

fn parse_nonempty_string(
    vars: &HashMap<String, String>,
    var: &'static str,
    default: &str,
) -> Result<String, ConfigError> {
    match vars.get(var).filter(|value| !value.is_empty()) {
        None => Ok(default.to_string()),
        Some(value) => Ok((*value).clone()),
    }
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
        assert_eq!(config.ads_reward_seconds.get(), DEFAULT_ADS_REWARD_SECONDS);
        assert_eq!(config.ads_daily_cap.get(), DEFAULT_ADS_DAILY_CAP);
    }

    #[test]
    fn ads_reward_and_cap_are_configurable() {
        let config = Config::from_map(&with_required(&[
            ("ADS_REWARD_SECONDS", "30"),
            ("ADS_DAILY_CAP", "3"),
        ]))
        .unwrap();

        assert_eq!(config.ads_reward_seconds.get(), 30);
        assert_eq!(config.ads_daily_cap.get(), 3);
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

    #[test]
    fn trusted_proxies_default_to_empty_and_parse_cidr_or_ip() {
        let config = Config::from_map(&with_required(&[])).unwrap();
        assert!(config.trusted_proxies.is_empty());

        let config = Config::from_map(&with_required(&[(
            "TRUSTED_PROXIES",
            "10.0.0.0/8, 127.0.0.1, 2001:db8::1",
        )]))
        .unwrap();
        assert_eq!(config.trusted_proxies.len(), 3);
        assert!(config.trusted_proxies[0].contains(&"10.1.2.3".parse::<IpAddr>().unwrap()));
        assert_eq!(config.trusted_proxies[1].prefix_len(), 32);
        assert_eq!(config.trusted_proxies[2].prefix_len(), 128);
    }

    #[test]
    fn invalid_trusted_proxy_fails_with_specific_message() {
        let err =
            Config::from_map(&with_required(&[("TRUSTED_PROXIES", "not-an-ip")])).unwrap_err();
        assert!(err.to_string().contains("TRUSTED_PROXIES"));
        assert!(err.to_string().contains("not-an-ip"));
    }

    #[test]
    fn pool_and_tokio_defaults_match_the_small_machine() {
        let config = Config::from_map(&with_required(&[])).unwrap();
        assert_eq!(config.db_max_connections, DEFAULT_DB_MAX_CONNECTIONS);
        assert_eq!(config.db_min_connections, 0);
        assert_eq!(config.db_idle_timeout, Duration::from_secs(300));
        assert_eq!(config.db_acquire_timeout, Duration::from_secs(5));
        assert_eq!(config.tokio_worker_threads.get(), 2);
        assert_eq!(config.tokio_max_blocking_threads.get(), 8);
        assert_eq!(
            config.play_integrity_base_url,
            DEFAULT_PLAY_INTEGRITY_BASE_URL
        );
        assert_eq!(
            config.android_publisher_base_url,
            DEFAULT_ANDROID_PUBLISHER_BASE_URL
        );
        let pool = config.pool_settings();
        assert_eq!(pool.max_connections, 5);
        assert_eq!(pool.min_connections, 0);
    }

    #[test]
    fn db_min_connections_cannot_exceed_max() {
        let err = Config::from_map(&with_required(&[
            ("DB_MAX_CONNECTIONS", "2"),
            ("DB_MIN_CONNECTIONS", "3"),
        ]))
        .unwrap_err();
        assert!(err.to_string().contains("DB_MIN_CONNECTIONS"));
    }

    #[test]
    fn empty_optional_env_counts_as_absent() {
        let config = Config::from_map(&with_required(&[
            ("DB_ACQUIRE_TIMEOUT_SECS", ""),
            ("TOKIO_WORKER_THREADS", ""),
            ("TOKIO_MAX_BLOCKING_THREADS", ""),
            ("PLAY_INTEGRITY_BASE_URL", ""),
            ("ANDROID_PUBLISHER_BASE_URL", ""),
        ]))
        .unwrap();

        assert_eq!(config.db_acquire_timeout, Duration::from_secs(5));
        assert_eq!(
            config.tokio_worker_threads.get(),
            DEFAULT_TOKIO_WORKER_THREADS
        );
        assert_eq!(
            config.tokio_max_blocking_threads.get(),
            DEFAULT_TOKIO_MAX_BLOCKING_THREADS
        );
        assert_eq!(
            config.play_integrity_base_url,
            DEFAULT_PLAY_INTEGRITY_BASE_URL
        );
        assert_eq!(
            config.android_publisher_base_url,
            DEFAULT_ANDROID_PUBLISHER_BASE_URL
        );
    }

    #[test]
    fn google_base_urls_are_configurable() {
        let config = Config::from_map(&with_required(&[
            ("PLAY_INTEGRITY_BASE_URL", "http://mock:8081"),
            ("ANDROID_PUBLISHER_BASE_URL", "http://mock:8081"),
            ("TOKIO_WORKER_THREADS", "1"),
        ]))
        .unwrap();
        assert_eq!(config.play_integrity_base_url, "http://mock:8081");
        assert_eq!(config.android_publisher_base_url, "http://mock:8081");
        assert_eq!(config.tokio_worker_threads.get(), 1);
    }
}

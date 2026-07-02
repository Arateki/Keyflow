use std::collections::HashMap;
use std::fmt;
use std::net::SocketAddr;

const DEFAULT_BIND_ADDR: &str = "0.0.0.0:8080";

#[derive(Debug, Clone)]
pub struct Config {
    pub database_url: String,
    pub bind_addr: SocketAddr,
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

        Ok(Config {
            database_url,
            bind_addr,
        })
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

    #[test]
    fn valid_env_produces_config() {
        let config = Config::from_map(&vars(&[
            (
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            ),
            ("BIND_ADDR", "127.0.0.1:9090"),
        ]))
        .unwrap();

        assert_eq!(
            config.database_url,
            "postgres://keyflow:keyflow@localhost/keyflow_api"
        );
        assert_eq!(config.bind_addr, "127.0.0.1:9090".parse().unwrap());
    }

    #[test]
    fn missing_bind_addr_falls_back_to_default() {
        let config = Config::from_map(&vars(&[(
            "DATABASE_URL",
            "postgres://keyflow:keyflow@localhost/keyflow_api",
        )]))
        .unwrap();

        assert_eq!(config.bind_addr, DEFAULT_BIND_ADDR.parse().unwrap());
    }

    #[test]
    fn invalid_bind_addr_fails_with_specific_message() {
        let err = Config::from_map(&vars(&[
            (
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            ),
            ("BIND_ADDR", "not-an-addr"),
        ]))
        .unwrap_err();

        assert!(err.to_string().contains("BIND_ADDR"));
        assert!(err.to_string().contains("not-an-addr"));
    }
}

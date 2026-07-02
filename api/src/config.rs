use std::fmt;
use std::net::SocketAddr;

const DEFAULT_BIND_ADDR: &str = "0.0.0.0:8080";

#[derive(Debug, Clone)]
pub struct Config {
    // Consumido pelo pool sqlx da API-T3; validado (presença) já aqui na T2.
    #[allow(dead_code)]
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
        let database_url =
            std::env::var("DATABASE_URL").map_err(|_| ConfigError::MissingEnv("DATABASE_URL"))?;

        let bind_addr = match std::env::var("BIND_ADDR") {
            Ok(value) => value
                .parse::<SocketAddr>()
                .map_err(|e| ConfigError::InvalidEnv {
                    var: "BIND_ADDR",
                    value,
                    reason: e.to_string(),
                })?,
            Err(_) => DEFAULT_BIND_ADDR
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
    use std::sync::Mutex;

    // Env vars são estado global do processo: serializa os testes deste módulo para não correr
    // um contra o outro (cada teste seta/remove as vars antes de rodar).
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn missing_database_url_fails_with_specific_message() {
        let _guard = ENV_LOCK.lock().unwrap();
        // SAFETY: testes deste módulo seguram ENV_LOCK antes de tocar env vars de processo.
        unsafe {
            std::env::remove_var("DATABASE_URL");
            std::env::remove_var("BIND_ADDR");
        }

        let err = Config::from_env().unwrap_err();

        assert_eq!(
            err.to_string(),
            "missing required environment variable: DATABASE_URL"
        );
    }

    #[test]
    fn valid_env_produces_config() {
        let _guard = ENV_LOCK.lock().unwrap();
        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::set_var(
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            );
            std::env::set_var("BIND_ADDR", "127.0.0.1:9090");
        }

        let config = Config::from_env().unwrap();

        assert_eq!(
            config.database_url,
            "postgres://keyflow:keyflow@localhost/keyflow_api"
        );
        assert_eq!(config.bind_addr, "127.0.0.1:9090".parse().unwrap());

        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::remove_var("DATABASE_URL");
            std::env::remove_var("BIND_ADDR");
        }
    }

    #[test]
    fn missing_bind_addr_falls_back_to_default() {
        let _guard = ENV_LOCK.lock().unwrap();
        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::set_var(
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            );
            std::env::remove_var("BIND_ADDR");
        }

        let config = Config::from_env().unwrap();

        assert_eq!(config.bind_addr, DEFAULT_BIND_ADDR.parse().unwrap());

        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::remove_var("DATABASE_URL");
        }
    }

    #[test]
    fn invalid_bind_addr_fails_with_specific_message() {
        let _guard = ENV_LOCK.lock().unwrap();
        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::set_var(
                "DATABASE_URL",
                "postgres://keyflow:keyflow@localhost/keyflow_api",
            );
            std::env::set_var("BIND_ADDR", "not-an-addr");
        }

        let err = Config::from_env().unwrap_err();

        assert!(err.to_string().contains("BIND_ADDR"));
        assert!(err.to_string().contains("not-an-addr"));

        // SAFETY: guarda por ENV_LOCK.
        unsafe {
            std::env::remove_var("DATABASE_URL");
            std::env::remove_var("BIND_ADDR");
        }
    }
}

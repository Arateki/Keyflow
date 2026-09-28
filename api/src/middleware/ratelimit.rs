use std::net::IpAddr;
use std::num::NonZeroU32;

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use governor::clock::{Clock, DefaultClock};
use governor::{DefaultKeyedRateLimiter, Quota};
use uuid::Uuid;

/// Limitadores keyed do design (ID-04): por IP no `/v1/register` (rota sem auth — só o IP
/// identifica quem está batendo) e por conta nas rotas autenticadas.
pub struct RateLimiters {
    register_by_ip: DefaultKeyedRateLimiter<IpAddr>,
    account: DefaultKeyedRateLimiter<Uuid>,
    clock: DefaultClock,
}

pub struct RateLimitRejection {
    retry_after_secs: u64,
}

impl IntoResponse for RateLimitRejection {
    fn into_response(self) -> Response {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [("retry-after", self.retry_after_secs.to_string())],
        )
            .into_response()
    }
}

impl RateLimiters {
    pub fn new(register_per_minute: NonZeroU32, account_per_minute: NonZeroU32) -> Self {
        Self {
            register_by_ip: DefaultKeyedRateLimiter::keyed(Quota::per_minute(register_per_minute)),
            account: DefaultKeyedRateLimiter::keyed(Quota::per_minute(account_per_minute)),
            clock: DefaultClock::default(),
        }
    }

    pub fn check_register(&self, ip: IpAddr) -> Result<(), RateLimitRejection> {
        self.check(&self.register_by_ip, ip)
    }

    pub fn check_account(&self, account_id: Uuid) -> Result<(), RateLimitRejection> {
        self.check(&self.account, account_id)
    }

    fn check<K: std::hash::Hash + Eq + Clone>(
        &self,
        limiter: &DefaultKeyedRateLimiter<K>,
        key: K,
    ) -> Result<(), RateLimitRejection> {
        limiter
            .check_key(&key)
            .map_err(|not_until| RateLimitRejection {
                // Arredonda pra cima e nunca devolve 0 — um cliente que respeita o header não deve
                // martelar de novo no mesmo instante.
                retry_after_secs: not_until
                    .wait_time_from(self.clock.now())
                    .as_secs_f64()
                    .ceil()
                    .max(1.0) as u64,
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    fn one() -> NonZeroU32 {
        NonZeroU32::new(1).unwrap()
    }

    #[test]
    fn register_allows_up_to_quota_then_429_with_retry_after() {
        let limiters = RateLimiters::new(one(), one());
        let ip = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1));

        assert!(
            limiters.check_register(ip).is_ok(),
            "1ª request deve passar"
        );

        let rejection = limiters.check_register(ip).unwrap_err();
        assert!(rejection.retry_after_secs >= 1);
    }

    #[test]
    fn register_limit_is_per_ip() {
        let limiters = RateLimiters::new(one(), one());
        let ip_a = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
        let ip_b = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2));

        assert!(limiters.check_register(ip_a).is_ok());
        assert!(
            limiters.check_register(ip_a).is_err(),
            "ip_a estourou a quota"
        );
        assert!(
            limiters.check_register(ip_b).is_ok(),
            "ip_b não deveria ser afetado por ip_a"
        );
    }

    #[test]
    fn account_limit_is_independent_from_register_limit() {
        let limiters = RateLimiters::new(one(), one());
        let ip = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1));
        let account_id = Uuid::new_v4();

        assert!(limiters.check_register(ip).is_ok());
        assert!(limiters.check_register(ip).is_err());
        // A quota de register estourada não deveria afetar a quota (separada) por conta.
        assert!(limiters.check_account(account_id).is_ok());
    }

    #[test]
    fn account_limit_is_per_account() {
        let limiters = RateLimiters::new(one(), one());
        let account_a = Uuid::new_v4();
        let account_b = Uuid::new_v4();

        assert!(limiters.check_account(account_a).is_ok());
        assert!(limiters.check_account(account_a).is_err());
        assert!(limiters.check_account(account_b).is_ok());
    }
}

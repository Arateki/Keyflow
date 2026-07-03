//! Provedores de transcrição (design §Proxy): "lista ordenada `[{name, url, authHeaderName,
//! authValueEnv, model}]`" — a chave (segredo real) mora em OUTRA env var, referenciada por
//! nome, nunca inline no JSON da lista (OPS-03, nenhum secret no repo/config direta).

use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProviderConfigRaw {
    name: String,
    url: String,
    auth_header_name: String,
    auth_value_env: String,
    model: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedProvider {
    pub name: String,
    pub url: String,
    pub auth_header_name: String,
    pub auth_value: String,
    pub model: String,
}

/// Circuit breaker simples entre requests (design TRX-05, decisão registrada em
/// `.specs/features/keyflow-api/tasks.md` API-T11): dado que o corpo da request é
/// verdadeiramente streamado (TRX-04, sem buffer), **não é possível reenviar o mesmo corpo pra um
/// 2º provedor dentro da mesma request** sem materializá-lo — contradiria "nunca bufferizar".
/// Em vez de fallback intra-request, um provedor que falhar (conexão/5xx) fica marcado indisponível
/// por um cooldown; a PRÓXIMA request pula direto pro próximo da lista. Efeito prático equivalente
/// ao fallback do design em regime permanente (todo request após a 1ª falha já usa o saudável),
/// só a request que descobriu a falha não tem retry — ela erra e o cliente decide reenviar.
pub struct ProviderHealth {
    unhealthy_until: Vec<Mutex<Option<Instant>>>,
}

impl ProviderHealth {
    pub fn new(provider_count: usize) -> Self {
        Self {
            unhealthy_until: (0..provider_count).map(|_| Mutex::new(None)).collect(),
        }
    }

    pub fn is_healthy(&self, index: usize) -> bool {
        match self.unhealthy_until[index].lock().unwrap().as_ref() {
            Some(until) => Instant::now() >= *until,
            None => true,
        }
    }

    pub fn mark_unhealthy(&self, index: usize, cooldown: Duration) {
        *self.unhealthy_until[index].lock().unwrap() = Some(Instant::now() + cooldown);
    }

    /// Primeiro provedor saudável na ordem da config; se todos estiverem em cooldown, tenta o
    /// primeiro mesmo assim (melhor uma tentativa fadada a falhar rápido do que recusar de cara).
    pub fn pick(&self) -> usize {
        (0..self.unhealthy_until.len())
            .find(|&i| self.is_healthy(i))
            .unwrap_or(0)
    }
}

pub fn parse_and_resolve(
    json: &str,
    vars: &std::collections::HashMap<String, String>,
) -> Result<Vec<ResolvedProvider>, String> {
    let raw: Vec<ProviderConfigRaw> =
        serde_json::from_str(json).map_err(|e| format!("invalid JSON: {e}"))?;

    if raw.is_empty() {
        return Err("must list at least one provider".to_string());
    }

    raw.into_iter()
        .map(|p| {
            let auth_value = vars
                .get(&p.auth_value_env)
                .cloned()
                .ok_or_else(|| format!("authValueEnv {} is not set", p.auth_value_env))?;
            Ok(ResolvedProvider {
                name: p.name,
                url: p.url,
                auth_header_name: p.auth_header_name,
                auth_value,
                model: p.model,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(pairs: &[(&str, &str)]) -> std::collections::HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn parses_and_resolves_auth_value_from_env() {
        let json = r#"[{"name":"primary","url":"https://p1.example/transcribe","authHeaderName":"Authorization","authValueEnv":"P1_KEY","model":"whisper-1"}]"#;
        let providers = parse_and_resolve(json, &vars(&[("P1_KEY", "secret-123")])).unwrap();

        assert_eq!(providers.len(), 1);
        assert_eq!(providers[0].name, "primary");
        assert_eq!(providers[0].auth_value, "secret-123");
    }

    #[test]
    fn missing_auth_value_env_fails_with_specific_message() {
        let json = r#"[{"name":"primary","url":"https://p1.example","authHeaderName":"Authorization","authValueEnv":"P1_KEY","model":"whisper-1"}]"#;
        let err = parse_and_resolve(json, &vars(&[])).unwrap_err();

        assert!(err.contains("P1_KEY"));
    }

    #[test]
    fn empty_list_is_rejected() {
        let err = parse_and_resolve("[]", &vars(&[])).unwrap_err();
        assert!(err.contains("at least one"));
    }

    #[test]
    fn preserves_order() {
        let json = r#"[
            {"name":"primary","url":"https://p1.example","authHeaderName":"Authorization","authValueEnv":"P1_KEY","model":"m1"},
            {"name":"fallback","url":"https://p2.example","authHeaderName":"Authorization","authValueEnv":"P2_KEY","model":"m2"}
        ]"#;
        let providers =
            parse_and_resolve(json, &vars(&[("P1_KEY", "a"), ("P2_KEY", "b")])).unwrap();

        assert_eq!(providers[0].name, "primary");
        assert_eq!(providers[1].name, "fallback");
    }

    #[test]
    fn provider_health_starts_all_healthy_and_picks_index_0() {
        let health = ProviderHealth::new(2);
        assert!(health.is_healthy(0));
        assert!(health.is_healthy(1));
        assert_eq!(health.pick(), 0);
    }

    #[test]
    fn marking_unhealthy_makes_pick_skip_to_next() {
        let health = ProviderHealth::new(2);
        health.mark_unhealthy(0, Duration::from_secs(60));

        assert!(!health.is_healthy(0));
        assert!(health.is_healthy(1));
        assert_eq!(health.pick(), 1);
    }

    #[test]
    fn all_unhealthy_still_picks_the_first() {
        let health = ProviderHealth::new(2);
        health.mark_unhealthy(0, Duration::from_secs(60));
        health.mark_unhealthy(1, Duration::from_secs(60));

        assert_eq!(health.pick(), 0);
    }

    #[test]
    fn cooldown_expires_and_provider_becomes_healthy_again() {
        let health = ProviderHealth::new(1);
        health.mark_unhealthy(0, Duration::from_millis(1));
        std::thread::sleep(Duration::from_millis(20));

        assert!(health.is_healthy(0));
    }
}

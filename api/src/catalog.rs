//! Catálogo de produtos (design §Compras). Estático até a API-T10 servir `documents.config` de
//! verdade via Postgres — "shape mínimo pode ser embutido até T10" (tasks.md, API-T8).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Product {
    pub id: &'static str,
    /// Minutos calibrados pelo Yan sem release (design M4) — placeholders até a calibração real.
    pub credited_seconds: i64,
    /// SKUs ≥ R$20 ligam `ads_off`+`skins_unlocked` (design §Compras).
    pub grants_entitlements: bool,
}

pub const PRODUCTS: &[Product] = &[
    Product {
        id: "credits_10",
        credited_seconds: 10 * 60,
        grants_entitlements: false,
    },
    Product {
        id: "credits_20",
        credited_seconds: 25 * 60,
        grants_entitlements: true,
    },
    Product {
        id: "credits_50",
        credited_seconds: 70 * 60,
        grants_entitlements: true,
    },
    Product {
        id: "unlock_forever",
        credited_seconds: 0,
        grants_entitlements: true,
    },
];

pub fn find_product(product_id: &str) -> Option<&'static Product> {
    PRODUCTS.iter().find(|p| p.id == product_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credits_20_and_above_grant_entitlements_credits_10_does_not() {
        assert!(!find_product("credits_10").unwrap().grants_entitlements);
        assert!(find_product("credits_20").unwrap().grants_entitlements);
        assert!(find_product("credits_50").unwrap().grants_entitlements);
        assert!(find_product("unlock_forever").unwrap().grants_entitlements);
    }

    #[test]
    fn unknown_product_is_none() {
        assert!(find_product("does_not_exist").is_none());
    }
}

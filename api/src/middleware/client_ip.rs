use std::net::IpAddr;

use ipnet::IpNet;

/// IP usado no rate limit de `/v1/register`.
///
/// `CF-Connecting-IP` só vale quando o peer (o proxy que abriu o socket) está numa rede
/// confiável. Sem essa lista — o padrão — o header é ignorado, para um cliente na internet
/// não forjar o IP e furar a cota.
pub fn client_ip(peer: IpAddr, cf_connecting_ip: Option<&str>, trusted: &[IpNet]) -> IpAddr {
    if trusted.is_empty() || !trusted.iter().any(|net| net.contains(&peer)) {
        return peer;
    }
    // Um único IP, como a Cloudflare manda. Lista (vírgula) não é aceita: parse falha e
    // ficamos no IP do peer, em vez de confiar no primeiro elemento de um header forjado.
    cf_connecting_ip
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .and_then(|value| value.parse().ok())
        .unwrap_or(peer)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn peer() -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1))
    }

    fn net(cidr: &str) -> IpNet {
        cidr.parse().unwrap()
    }

    #[test]
    fn header_is_ignored_when_no_proxy_is_trusted() {
        let ip = client_ip(peer(), Some("203.0.113.8"), &[]);
        assert_eq!(ip, peer());
    }

    #[test]
    fn header_is_used_when_peer_is_a_trusted_proxy() {
        let ip = client_ip(peer(), Some("203.0.113.8"), &[net("127.0.0.1/32")]);
        assert_eq!(ip, "203.0.113.8".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn header_is_ignored_when_peer_is_not_in_the_trusted_list() {
        let outsider = "198.51.100.4".parse().unwrap();
        let ip = client_ip(outsider, Some("203.0.113.8"), &[net("127.0.0.1/32")]);
        assert_eq!(ip, outsider);
    }

    #[test]
    fn garbage_header_falls_back_to_the_peer() {
        let ip = client_ip(peer(), Some("not-an-ip"), &[net("127.0.0.1/32")]);
        assert_eq!(ip, peer());
    }
}

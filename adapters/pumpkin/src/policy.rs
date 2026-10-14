use serde::{Deserialize, Serialize};
use std::{net::IpAddr, path::Path};

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "UPPERCASE")]
pub enum Mode {
    Allowlist,
    Blocklist,
    Disabled,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Bedrock {
    pub enabled: bool,
    pub bypass_georestrict: bool,
    pub bypass_vpn_check: bool,
}
impl Default for Bedrock {
    fn default() -> Self {
        Self {
            enabled: true,
            bypass_georestrict: false,
            bypass_vpn_check: false,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    pub config_version: u32,
    pub cache_ttl_days: u64,
    pub connection_timeout_ms: u64,
    pub gateway_url: String,
    pub gateway_token: String,
    pub block_on_lookup_failure: bool,
    pub block_local_connections: bool,
    pub max_cache_entries: usize,
    pub country_mode: Mode,
    pub countries: Vec<String>,
    pub asn_mode: Mode,
    pub asns: Vec<String>,
    pub vpn_check_enabled: bool,
    pub vpn_keywords: Vec<String>,
    pub kick_message_country: String,
    pub kick_message_asn: String,
    pub kick_message_vpn: String,
    pub kick_message_lookup_failure: String,
    pub kick_message_local_connection: String,
    pub floodgate: Bedrock,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            config_version: 6,
            cache_ttl_days: 30,
            connection_timeout_ms: 3000,
            gateway_url: "https://geoprotect.demonzdevelopment-e64.workers.dev/".into(),
            gateway_token: String::new(),
            block_on_lookup_failure: true,
            block_local_connections: false,
            max_cache_entries: 100_000,
            country_mode: Mode::Blocklist,
            countries: ["CN", "RU", "KP", "IR"].map(String::from).to_vec(),
            asn_mode: Mode::Disabled,
            asns: vec![],
            vpn_check_enabled: true,
            vpn_keywords: serde_json::from_value(
                serde_json::from_str::<serde_json::Value>(include_str!("defaults.json"))
                    .expect("valid embedded defaults")["vpnKeywords"]
                    .clone(),
            )
            .expect("valid VPN keywords"),
            kick_message_country: "Your country is not allowed on this server.".into(),
            kick_message_asn: "Your ISP/ASN is not allowed on this server.".into(),
            kick_message_vpn: "VPN or proxy connections are not allowed.".into(),
            kick_message_lookup_failure:
                "Geo verification is temporarily unavailable. Please try again later.".into(),
            kick_message_local_connection:
                "Local network connections are not allowed on this server.".into(),
            floodgate: Bedrock::default(),
        }
    }
}
impl Config {
    pub fn load(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("Cannot read config: {e}"))?;
        let mut c: Self =
            serde_yaml_ng::from_str(&text).map_err(|e| format!("Invalid config: {e}"))?;
        if c.config_version > 6 {
            return Err("Config belongs to a newer GeoRestrict release".into());
        }
        c.config_version = 6;
        c.connection_timeout_ms = c.connection_timeout_ms.clamp(250, 30_000);
        c.cache_ttl_days = c.cache_ttl_days.min(3650);
        c.max_cache_entries = c.max_cache_entries.clamp(1, 100_000);
        for list in [&mut c.countries, &mut c.asns] {
            for code in list {
                *code = code.trim().to_uppercase();
            }
        }
        c.vpn_keywords.retain(|s| !s.trim().is_empty());
        for word in &mut c.vpn_keywords {
            *word = word.trim().to_lowercase();
        }
        let url = url::Url::parse(&c.gateway_url).map_err(|_| "Invalid gatewayUrl")?;
        if !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
        {
            return Err("gatewayUrl must be an HTTP(S) URL without credentials".into());
        }
        Ok(c)
    }
    pub fn failure(&self) -> Decision {
        Decision {
            allowed: !self.block_on_lookup_failure,
            reason: self
                .block_on_lookup_failure
                .then(|| self.kick_message_lookup_failure.clone()),
            info: None,
        }
    }
    pub fn preliminary(&self, ip: &str, bypass: bool, bedrock: bool) -> Option<Decision> {
        if bypass || (bedrock && self.floodgate.enabled && self.floodgate.bypass_georestrict) {
            return Some(Decision::allow(None));
        }
        if is_local(ip)
            && (self.block_local_connections
                || (self.country_mode == Mode::Blocklist
                    && self.countries.iter().any(|c| c == "LOCAL")))
        {
            let info = Geo {
                ip: ip.into(),
                country_code: "LOCAL".into(),
                country_name: "Local Network".into(),
                provider: "local".into(),
                ..Geo::default()
            };
            return Some(Decision::deny(
                if self.block_local_connections {
                    &self.kick_message_local_connection
                } else {
                    &self.kick_message_country
                },
                info,
            ));
        }
        if !is_public(ip) {
            return Some(Decision::allow(None));
        }
        None
    }
    pub fn evaluate(&self, info: Geo, bedrock: bool) -> Decision {
        let listed = self.countries.contains(&info.country_code);
        if (self.country_mode == Mode::Allowlist && !listed)
            || (self.country_mode == Mode::Blocklist && listed)
        {
            return Decision::deny(&self.kick_message_country, info);
        }
        let listed = self.asns.contains(&info.asn);
        if !self.asns.is_empty()
            && ((self.asn_mode == Mode::Allowlist && !listed)
                || (self.asn_mode == Mode::Blocklist && listed))
        {
            return Decision::deny(&self.kick_message_asn, info);
        }
        if self.vpn_check_enabled
            && !(bedrock && self.floodgate.enabled && self.floodgate.bypass_vpn_check)
            && (info.is_vpn
                || info.is_proxy
                || info.is_hosting
                || self
                    .vpn_keywords
                    .iter()
                    .any(|word| info.as_name.to_lowercase().contains(word)))
        {
            return Decision::deny(&self.kick_message_vpn, info);
        }
        Decision::allow(Some(info))
    }
}
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Geo {
    pub ip: String,
    pub country_code: String,
    pub country_name: String,
    pub asn: String,
    pub as_name: String,
    pub provider: String,
    pub is_vpn: bool,
    pub is_proxy: bool,
    pub is_hosting: bool,
    pub is_mobile: bool,
}
impl Geo {
    pub fn decode(bytes: &[u8], ip: &str) -> Result<Self, String> {
        let value: serde_json::Value =
            serde_json::from_slice(bytes).map_err(|_| "Invalid gateway JSON")?;
        if value.get("error").is_some_and(|v| !v.is_null()) {
            return Err("Gateway returned an error".into());
        }
        let mut geo: Self =
            serde_json::from_value(value).map_err(|_| "Invalid gateway response")?;
        geo.country_code = geo.country_code.trim().to_uppercase();
        geo.asn = geo.asn.trim().to_uppercase();
        if geo.country_code.is_empty() {
            return Err("Gateway response has no countryCode".into());
        }
        geo.ip = ip.into();
        Ok(geo)
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct Decision {
    pub allowed: bool,
    pub reason: Option<String>,
    pub info: Option<Geo>,
}
impl Decision {
    pub fn allow(info: Option<Geo>) -> Self {
        Self {
            allowed: true,
            reason: None,
            info,
        }
    }
    pub fn deny(reason: &str, info: Geo) -> Self {
        Self {
            allowed: false,
            reason: Some(reason.into()),
            info: Some(info),
        }
    }
}
pub fn literal(value: &str) -> Option<IpAddr> {
    let text = value.trim();
    let text = if let Some((ip, zone)) = text.split_once('%') {
        if !ip.contains(':')
            || zone.is_empty()
            || !zone
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || "_.-".contains(c))
        {
            return None;
        }
        ip
    } else {
        text
    };
    text.parse::<IpAddr>().ok().map(|ip| match ip {
        IpAddr::V6(v) => v.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(ip),
        _ => ip,
    })
}
pub fn transport_ip(value: &str) -> Option<String> {
    value
        .parse::<std::net::SocketAddr>()
        .ok()
        .map(|socket| socket.ip().to_string())
        .or_else(|| literal(value).map(|ip| ip.to_string()))
}
pub fn is_local(ip: &str) -> bool {
    match literal(ip) {
        Some(IpAddr::V4(v)) => {
            v.is_unspecified() || v.is_loopback() || v.is_private() || v.is_link_local()
        }
        Some(IpAddr::V6(v)) => {
            let b = v.octets();
            v.is_unspecified()
                || v.is_loopback()
                || b[0] & 0xfe == 0xfc
                || (b[0] == 0xfe && (b[1] & 0xc0 == 0x80 || b[1] & 0xc0 == 0xc0))
        }
        None => false,
    }
}
pub fn is_public(ip: &str) -> bool {
    if is_local(ip) {
        return false;
    }
    match literal(ip) {
        Some(IpAddr::V4(v)) => {
            let [a, b, c, _] = v.octets();
            !(a == 0
                || a >= 224
                || (a == 100 && (64..=127).contains(&b))
                || (a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113))
        }
        Some(IpAddr::V6(v)) => {
            let b = v.octets();
            !v.is_multicast() && !(b[0..4] == [0x20, 1, 0x0d, 0xb8] || b[0..4] == [0x20, 1, 0, 0])
        }
        None => false,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn transport_socket_addresses_preserve_real_ips() {
        assert_eq!(
            transport_ip("127.0.0.1:45678").as_deref(),
            Some("127.0.0.1")
        );
        assert_eq!(transport_ip("[::1]:45678").as_deref(), Some("::1"));
        assert_eq!(
            transport_ip("[2606:4700:4700::1111]:25565").as_deref(),
            Some("2606:4700:4700::1111")
        );
        assert_eq!(transport_ip("8.8.8.8").as_deref(), Some("8.8.8.8"));
        assert!(transport_ip("localhost:25565").is_none());
    }
    #[test]
    fn local_literals_and_reserved_ranges_are_distinct() {
        for ip in [
            "127.0.0.1",
            "127.9.2.3",
            "10.0.0.5",
            "172.31.0.1",
            "192.168.0.1",
            "169.254.1.2",
            "0.0.0.0",
            "::",
            "::1",
            "fc00::1",
            "fdff::1",
            "fe80::1%eth0",
            "fec0::1",
            "::ffff:127.0.0.1",
        ] {
            assert!(is_local(ip), "{ip}");
            assert!(!is_public(ip), "{ip}");
        }
        for ip in [
            "100.64.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "2001:db8::1",
            "224.0.0.1",
            "localhost",
            "127.1",
            "999.1.1.1",
        ] {
            assert!(!is_local(ip), "{ip}");
            assert!(!is_public(ip), "{ip}");
        }
        for ip in [
            "8.8.8.8",
            "172.32.0.1",
            "2606:4700:4700::1111",
            "::ffff:8.8.8.8",
        ] {
            assert!(!is_local(ip), "{ip}");
            assert!(is_public(ip), "{ip}");
        }
    }
    #[test]
    fn local_flag_local_country_and_bypasses() {
        let mut c = Config::default();
        assert!(c.preliminary("127.0.0.1", false, false).unwrap().allowed);
        c.block_local_connections = true;
        assert!(!c.preliminary("::1", false, false).unwrap().allowed);
        assert!(c.preliminary("::1", true, false).unwrap().allowed);
        c.floodgate.bypass_georestrict = true;
        assert!(c.preliminary("::1", false, true).unwrap().allowed);
        c.floodgate.enabled = false;
        assert!(!c.preliminary("::1", false, true).unwrap().allowed);
        c.block_local_connections = false;
        c.countries.push("LOCAL".into());
        assert!(!c.preliminary("10.0.0.1", false, false).unwrap().allowed);
        c.country_mode = Mode::Allowlist;
        assert!(c.preliminary("10.0.0.1", false, false).unwrap().allowed);
        c.block_local_connections = true;
        assert!(c.preliminary("100.64.0.1", false, false).unwrap().allowed);
        assert!(c.preliminary("8.8.8.8", false, false).is_none());
    }
    #[test]
    fn country_asn_vpn_and_bedrock_policy() {
        let mut c = Config::default();
        let mut g = Geo {
            country_code: "CN".into(),
            asn: "AS123".into(),
            ..Geo::default()
        };
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.country_mode = Mode::Allowlist;
        assert!(c.evaluate(g.clone(), false).allowed);
        g.country_code = "US".into();
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.country_mode = Mode::Disabled;
        c.asn_mode = Mode::Blocklist;
        c.asns.push("AS123".into());
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.asn_mode = Mode::Allowlist;
        assert!(c.evaluate(g.clone(), false).allowed);
        g.asn = "AS999".into();
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.asn_mode = Mode::Disabled;
        g.is_hosting = true;
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.floodgate.bypass_vpn_check = true;
        assert!(c.evaluate(g.clone(), true).allowed);
        assert!(!c.evaluate(g.clone(), false).allowed);
        g.is_hosting = false;
        g.as_name = "Example NordVPN network".into();
        assert!(!c.evaluate(g.clone(), false).allowed);
        c.vpn_check_enabled = false;
        assert!(c.evaluate(g, false).allowed);
    }
    #[test]
    fn config_defaults_normalization_and_invalid_input() {
        let dir = std::env::temp_dir().join(format!("georestrict-pumpkin-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.yml");
        std::fs::write(&path,"countries: [local, ' us ']\nasns: [as123]\nconnectionTimeoutMs: 1\nfloodgate: { bypassVpnCheck: true }\n").unwrap();
        let c = Config::load(&path).unwrap();
        assert_eq!(c.countries, ["LOCAL", "US"]);
        assert_eq!(c.asns, ["AS123"]);
        assert_eq!(c.connection_timeout_ms, 250);
        assert!(c.floodgate.enabled);
        assert!(c.floodgate.bypass_vpn_check);
        assert!(!c.preliminary("127.0.0.1", false, false).unwrap().allowed);
        for text in [
            "countryMode: typo",
            "gatewayUrl: file:///etc/passwd",
            "configVersion: 99",
            "countries: [",
        ] {
            std::fs::write(&path, text).unwrap();
            assert!(Config::load(&path).is_err(), "{text}");
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn failed_incomplete_and_untrusted_gateway_data() {
        assert!(Geo::decode(br#"{"error":"bad"}"#, "8.8.8.8").is_err());
        assert!(Geo::decode(br#"{}"#, "8.8.8.8").is_err());
        let g = Geo::decode(
            br#"{"ip":"127.0.0.1","countryCode":" us ","asn":" as123 "}"#,
            "8.8.8.8",
        )
        .unwrap();
        assert_eq!(g.ip, "8.8.8.8");
        assert_eq!(g.country_code, "US");
        assert_eq!(g.asn, "AS123");
        let mut c = Config::default();
        assert!(!c.failure().allowed);
        c.block_on_lookup_failure = false;
        assert!(c.failure().allowed);
    }
}

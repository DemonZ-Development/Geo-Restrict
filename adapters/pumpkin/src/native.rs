use crate::{
    http,
    policy::{Config, Decision, Geo},
};
use pumpkin_plugin_api::{
    Context, Plugin, PluginMetadata, Server,
    command::{
        Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs,
        StringType,
    },
    commands::CommandHandler,
    events::{AsyncPlayerPreLoginEvent, EventData, EventHandler, EventPriority, PlayerLoginEvent},
    permissions, register_plugin,
    text::TextComponent,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::{SystemTime, UNIX_EPOCH},
};
use waki::Method;

static DATA: OnceLock<PathBuf> = OnceLock::new();
static CACHE: Mutex<Option<BTreeMap<String, Entry>>> = Mutex::new(None);
#[derive(Clone, Deserialize, Serialize)]
struct Entry {
    stored: u64,
    geo: Geo,
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn path(name: &str) -> Result<PathBuf, String> {
    Ok(DATA
        .get()
        .ok_or("GeoRestrict has not initialized")?
        .join(name))
}
fn settings() -> Result<Config, String> {
    Config::load(&path("config.yml")?)
}
fn persist(entries: &BTreeMap<String, Entry>) -> Result<(), String> {
    let target = path("cache.json")?;
    let temporary = path("cache.json.tmp")?;
    std::fs::write(
        &temporary,
        serde_json::to_vec(entries).map_err(|_| "Cannot encode cache")?,
    )
    .map_err(|_| "Cannot save cache")?;
    std::fs::rename(temporary, target).map_err(|_| "Cannot replace cache".into())
}
fn lookup(config: &Config, ip: &str) -> Result<Geo, String> {
    {
        let mut cache = CACHE.lock().map_err(|_| "Cache lock unavailable")?;
        let entries = cache.get_or_insert_with(|| {
            std::fs::read(path("cache.json").unwrap_or_default())
                .ok()
                .and_then(|bytes| serde_json::from_slice(&bytes).ok())
                .unwrap_or_default()
        });
        let ttl = config.cache_ttl_days.saturating_mul(86400);
        if let Some(entry) = entries.get(ip) {
            if ttl > 0
                && entry.stored <= now()
                && now().saturating_sub(entry.stored) < ttl
                && !entry.geo.country_code.trim().is_empty()
            {
                return Ok(entry.geo.clone());
            }
        }
        entries.remove(ip);
    }
    let mut url = url::Url::parse(&config.gateway_url).map_err(|_| "Invalid gateway URL")?;
    url.query_pairs_mut().append_pair("ip", ip);
    let (status, bytes) = http::request(
        Method::Get,
        url.as_str(),
        &config.gateway_token,
        &[],
        config.connection_timeout_ms,
    )?;
    if status != 200 {
        return Err(format!("Gateway HTTP {status}"));
    }
    let geo = Geo::decode(&bytes, ip)?;
    if config.cache_ttl_days > 0 {
        let mut cache = CACHE.lock().map_err(|_| "Cache lock unavailable")?;
        let entries = cache.get_or_insert_with(BTreeMap::new);
        let timestamp = now();
        let ttl = config.cache_ttl_days.saturating_mul(86400);
        entries.retain(|_, e| timestamp.saturating_sub(e.stored) < ttl);
        entries.insert(
            ip.into(),
            Entry {
                stored: timestamp,
                geo: geo.clone(),
            },
        );
        while entries.len() > config.max_cache_entries {
            if let Some(oldest) = entries
                .iter()
                .min_by_key(|(_, e)| e.stored)
                .map(|(key, _)| key.clone())
            {
                entries.remove(&oldest);
            }
        }
        if let Err(error) = persist(entries) {
            eprintln!("GeoRestrict: {error}");
        }
    }
    Ok(geo)
}
fn decision(ip: &str, name: &str, bypass: bool, bedrock: bool) -> Decision {
    let config = match settings() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("GeoRestrict: {error}");
            return Decision {
                allowed: false,
                reason: Some(
                    "GeoRestrict configuration is invalid. Contact the server administrator."
                        .into(),
                ),
                info: None,
            };
        }
    };
    let result = if let Some(result) = config.preliminary(ip, bypass, bedrock) {
        result
    } else {
        match lookup(&config, ip) {
            Ok(geo) => config.evaluate(geo, bedrock),
            Err(error) => {
                eprintln!("GeoRestrict lookup failed: {error}");
                config.failure()
            }
        }
    };
    if result.info.is_some() {
        webhook(&config, &result, name, ip);
    }
    result
}
fn webhook(config: &Config, result: &Decision, name: &str, ip: &str) {
    let send = || -> Result<(), String> {
        let yaml = std::fs::read_to_string(path("config.yml")?)
            .map_err(|_| "Cannot read webhook config")?;
        let root: serde_yaml_ng::Value =
            serde_yaml_ng::from_str(&yaml).map_err(|_| "Invalid webhook config")?;
        let discord = &root["discord"];
        let address = discord["webhook"].as_str().unwrap_or("");
        if address.is_empty()
            || (result.allowed && !discord["logAllowed"].as_bool().unwrap_or(false))
            || (!result.allowed && !discord["logDenied"].as_bool().unwrap_or(true))
        {
            return Ok(());
        }
        let masked = if discord["maskIp"].as_bool().unwrap_or(true) {
            if let Ok(std::net::IpAddr::V4(v)) = ip.parse() {
                let b = v.octets();
                format!("{}.{}.x.x", b[0], b[1])
            } else if ip.contains(':') {
                format!("{}...", ip.chars().take(9).collect::<String>())
            } else {
                "Masked".into()
            }
        } else {
            ip.into()
        };
        let geo = result.info.as_ref().ok_or("No webhook data")?;
        let status = if result.allowed { "Allowed" } else { "Blocked" };
        let reason = result.reason.as_deref().unwrap_or("Connection allowed.");
        let replace = |text: &str| {
            text.replace("%player%", name)
                .replace("%status%", status)
                .replace("%ip%", &masked)
                .replace("%country%", &geo.country_code)
                .replace("%asn%", &geo.asn)
                .replace("%isp%", &geo.as_name)
                .replace("%reason%", reason)
        };
        let fields: Vec<serde_json::Value> = if let Some(fields) = discord["fields"].as_sequence() {
            fields.iter().take(25).map(|field|serde_json::json!({"name":replace(field["name"].as_str().unwrap_or("Info")),
                "value":replace(field["value"].as_str().unwrap_or("Unknown")),"inline":field["inline"].as_bool().unwrap_or(false)})).collect()
        } else {
            vec![
                serde_json::json!({"name":"Player","value":name}),
                serde_json::json!({"name":"IP","value":masked}),
                serde_json::json!({"name":"Status","value":status}),
                serde_json::json!({"name":"Reason","value":reason}),
            ]
        };
        let body=serde_json::to_vec(&serde_json::json!({"embeds":[{"title":discord["title"].as_str().unwrap_or("GeoRestrict"),
            "color":discord[if result.allowed {"colorAllowed"} else {"colorDenied"}].as_u64().unwrap_or(if result.allowed {65280} else {16711680}),"fields":fields}]})).map_err(|_| "Cannot encode webhook")?;
        let (status, _) = http::request(
            Method::Post,
            address,
            "",
            &body,
            config.connection_timeout_ms,
        )?;
        if !(200..300).contains(&status) {
            return Err(format!("Webhook HTTP {status}"));
        }
        Ok(())
    };
    if let Err(error) = send() {
        eprintln!("GeoRestrict: {error}");
    }
}
struct PreLogin;
impl EventHandler<AsyncPlayerPreLoginEvent> for PreLogin {
    fn handle(
        &self,
        server: Server,
        mut event: EventData<AsyncPlayerPreLoginEvent>,
    ) -> EventData<AsyncPlayerPreLoginEvent> {
        if event.cancelled {
            return event;
        }
        let bypass = pumpkin_plugin_api::uuid::parse(&event.player_uuid)
            .is_some_and(|uuid| server.get_op_manager().is_op(uuid));
        let ip = crate::policy::transport_ip(&event.ip_address);
        let result = ip
            .map(|ip| decision(&ip, &event.player_name, bypass, false))
            .unwrap_or_else(|| Decision {
                allowed: false,
                reason: Some("Cannot verify player connection address.".into()),
                info: None,
            });
        if !result.allowed {
            event.cancelled = true;
            event.kick_message =
                TextComponent::text(result.reason.as_deref().unwrap_or("Connection denied."));
        }
        event
    }
}
struct Login;
impl EventHandler<PlayerLoginEvent> for Login {
    fn handle(
        &self,
        server: Server,
        mut event: EventData<PlayerLoginEvent>,
    ) -> EventData<PlayerLoginEvent> {
        if event.cancelled || event.player.as_bedrock().is_none() {
            return event;
        }
        let uuid = event.player.get_id();
        let ip = crate::policy::transport_ip(&event.player.get_ip());
        let result = ip
            .map(|ip| {
                decision(
                    &ip,
                    &event.player.get_name(),
                    server.get_op_manager().is_op(uuid),
                    true,
                )
            })
            .unwrap_or_else(|| Decision {
                allowed: false,
                reason: Some("Cannot verify player connection address.".into()),
                info: None,
            });
        if !result.allowed {
            event.cancelled = true;
            event.kick_message =
                TextComponent::text(result.reason.as_deref().unwrap_or("Connection denied."));
        }
        event
    }
}
struct Admin(&'static str);
impl CommandHandler for Admin {
    fn handle(
        &self,
        sender: CommandSender,
        _server: Server,
        args: ConsumedArgs,
    ) -> Result<i32, CommandError> {
        let execute = || -> Result<String, String> {
            match self.0 {
                "reload" => {
                    settings()?;
                    Ok("GeoRestrict configuration reloaded. Changes also apply automatically at admission.".into())
                }
                "clear" => {
                    let mut cache = CACHE.lock().map_err(|_| "Cache lock unavailable")?;
                    *cache = Some(BTreeMap::new());
                    persist(cache.as_ref().unwrap())?;
                    Ok("GeoRestrict cache cleared.".into())
                }
                "check" => {
                    let ip = match args.get_value("ip") {
                        Arg::Simple(s) => s,
                        _ => return Err("Expected an IP literal".into()),
                    };
                    if crate::policy::literal(&ip).is_none() {
                        return Err("Expected an IP literal, not a hostname".into());
                    }
                    let result = decision(&ip, "ConsoleCheck", false, false);
                    Ok(format!(
                        "Result for {ip}: Allowed: {}{}",
                        if result.allowed { "Yes" } else { "No" },
                        result
                            .reason
                            .map(|reason| format!(" — {reason}"))
                            .unwrap_or_default()
                    ))
                }
                _ => Ok(format!(
                    "GeoRestrict {}: /gr reload, /gr check <ip>, /gr cache clear. Native Pumpkin Java and Bedrock admission.",
                    env!("CARGO_PKG_VERSION")
                )),
            }
        };
        match execute() {
            Ok(text) => {
                sender.send_message(TextComponent::text(&text));
                Ok(1)
            }
            Err(error) => Err(CommandError::CommandFailed(TextComponent::text(&error))),
        }
    }
}
struct GeoRestrict;
impl Plugin for GeoRestrict {
    fn new() -> Self {
        Self
    }
    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "georestrict".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Demonz Development".into()],
            description: "Native geographic admission for Pumpkin Java and Bedrock servers".into(),
            dependencies: vec![],
            permissions: vec![
                permissions::FS_READ_DATA.into(),
                permissions::FS_WRITE_DATA.into(),
                permissions::HTTP_OUTBOUND.into(),
            ],
        }
    }
    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        DATA.set(PathBuf::from(context.get_data_folder()))
            .map_err(|_| "GeoRestrict initialized twice")?;
        let config = path("config.yml")?;
        if !config.exists() {
            let yaml = serde_yaml_ng::to_string(&Config::default())
                .map_err(|_| "Cannot encode default config")?;
            std::fs::write(&config,format!("# GeoRestrict 2.1.0 — native Pumpkin plugin. No Java bridge required.\n# Local blocking is opt-in. Enforce it where real player addresses are visible.\n{yaml}{}", include_str!("discord-defaults.yml"))).map_err(|_| "Cannot create config.yml")?;
        }
        if let Err(error) = settings() {
            eprintln!("GeoRestrict configuration error: {error}");
        }
        context.register_event_handler(PreLogin, EventPriority::Highest, true)?;
        context.register_event_handler(Login, EventPriority::Highest, true)?;
        let command = Command::new(
            &["georestrict".into(), "gr".into()],
            "GeoRestrict admission controls",
        )
        .execute(Admin("info"))
        .then(CommandNode::literal("reload").execute(Admin("reload")))
        .then(CommandNode::literal("info").execute(Admin("info")))
        .then(
            CommandNode::literal("cache")
                .then(CommandNode::literal("clear").execute(Admin("clear"))),
        )
        .then(
            CommandNode::literal("check").then(
                CommandNode::argument("ip", &ArgumentType::String(StringType::Greedy))
                    .execute(Admin("check")),
            ),
        );
        context.register_permission(&pumpkin_plugin_api::permission::Permission {
            node: "georestrict:admin".into(),
            description: "Manage GeoRestrict configuration and cache".into(),
            default: pumpkin_plugin_api::permission::PermissionDefault::Op(
                pumpkin_plugin_api::permission::PermissionLevel::Three,
            ),
            children: vec![],
        })?;
        context.register_command(command, "georestrict:admin");
        eprintln!("GeoRestrict native admission enabled (2.1.0)");
        Ok(())
    }
}
register_plugin!(GeoRestrict);

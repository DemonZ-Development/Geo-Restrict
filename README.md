# 🌍 GeoRestrict

**Geographic restriction plugin for Minecraft servers. Supports Bukkit/Paper, BungeeCord/Waterfall, Velocity, and Folia.**

---

## ✨ Features

- **Country Blocking** — Block or allow players based on their country of origin
- **ASN Blocking** — Restrict access by Autonomous System Number (ISP/network level)
- **VPN/Proxy Detection** — Detect and block VPN, proxy, and hosting connections
- **Worker Gateway** — Route lookups through `https://geoprotect.demonzdev.workers.dev/`
- **Discord Webhooks** — Get real-time notifications when players are blocked
- **Local Caching** — High-performance local IP cache to minimize API lookups
- **Auto-Updates** — Automatic plugin update checks and notifications

## 📋 Supported Versions

- Minecraft **1.16 — 1.26.x**
- Platforms: Bukkit, Paper, BungeeCord, Waterfall, Velocity, Folia

## 📦 Installation

1. Download the latest `GeoRestrict.jar` from the releases page.
2. Drop the jar into your server's `plugins/` folder.
3. Restart or reload the server.
4. Edit the generated configuration in `plugins/GeoRestrict/config.yml`.

## 🔧 Commands

| Command | Description |
|---|---|
| `/georestrict check <ip\|player>` | Lookup IP or player geolocation info |
| `/georestrict purgecache` | Clear the local IP cache |
| `/georestrict cachestats` | Show cache statistics |
| `/georestrict reload` | Reload configuration from disk |
| `/georestrict` | Show plugin info and version |

## ⚙️ Gateway Configuration

The plugin defaults to:

```yaml
gatewayUrl: "https://geoprotect.demonzdev.workers.dev/"
gatewayToken: ""
directFallbackEnabled: false
blockOnLookupFailure: false
```

The Worker supports multiple provider accounts through environment variables or secrets:

```text
IPINFO_TOKENS       token-one,token-two
IPINFO_TOKEN_1      token-one
IPINFO_TOKEN_2      token-two
IP_API_KEYS         key-one,key-two
IP_API_KEY_1        key-one
GATEWAY_TOKENS      shared-plugin-token
```

Use Worker secrets for tokens. Do not commit provider keys or gateway tokens.

## 🔑 Permissions

| Permission | Description |
|---|---|
| `georestrict.admin` | Access to all GeoRestrict commands |

## 📜 License

This project is licensed under the [GNU GPL v3 License](LICENSE).

## 👥 Credits

GeoRestrict was originally written by **linuxaddict** and released on Modrinth. The first versions lived under his account for years before he passed the project along.

Maintenance and the v2.x rewrite are now handled by [Demonz Development](https://demonzdevelopment.online/). If you hit a bug, want a feature, or are just trying to figure out why your config isn't loading, the [issue tracker](https://github.com/DemonZ-Development/Geo-Restrict/issues) is the right place.

- Original author: [linuxaddict on Modrinth](https://modrinth.com/user/linuxaddict)
- Current maintainer: [Demonz Development](https://demonzdevelopment.online/)
- Repository: [DemonZ-Development/Geo-Restrict](https://github.com/DemonZ-Development/Geo-Restrict)

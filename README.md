# 🌍 GeoRestrict

**Geographic restriction plugin for Minecraft servers. Supports Bukkit/Paper, BungeeCord/Waterfall, Velocity, and Folia.**

---

## ✨ Features

- **Country Blocking** — Block or allow players based on their country of origin
- **ASN Blocking** — Restrict access by Autonomous System Number (ISP/network level)
- **VPN/Proxy Detection** — Detect and block VPN, proxy, and hosting connections
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

## 🔑 Permissions

| Permission | Description |
|---|---|
| `georestrict.admin` | Access to all GeoRestrict commands |

## 📜 License

This project is licensed under the [GNU GPL v3 License](LICENSE).


## 💜 Credits

Developed by [Demonz Development](https://demonzdevelopment.online/)

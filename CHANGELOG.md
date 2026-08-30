# Changelog

All notable changes to the GeoRestrict project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v2.0.2] - 2026-08-26

### Added
- **Broadened Developer API**: Ten new additive `GeoRestrictAPI` methods for third-party plugins (all existing methods unchanged).
  - Field getters: `getCountryName`, `getAsnName`, `isProxy`, `isHosting`, `isMobile`.
  - Rule helper: `isBlocked` (inverse of `isAllowed`).
  - Batch lookups: `lookupAll(ips)` resolves addresses in parallel with insertion order preserved; failed lookups keep their key mapped to null.
  - Offline cache access: `getCachedCountryCode`, `getCacheSize`, `purgeCache`.
- **Private Status Dashboard**: Token-gated `GET /status` endpoint on the Cloudflare Worker.
  - Renders an operator-only HTML dashboard (or JSON via `?format=json`) showing what is up, down or unconfigured.
  - Live-probes every configured geolocation provider with latency, the fallback server health route (including MMDB release), and all cache tiers (Cache API, memory, KV).
  - Hidden with 404 until a `STATUS_TOKENS` secret is set; wrong tokens get 401. Marked `noindex`, never cached, no CORS headers.

### Removed
- Removed legacy crawler-helper text files from the docs site; `robots.txt` now contains only standard crawler rules and the sitemap entry was dropped.

### Changed
- Aligned versions across the whole stack: plugin surfaces moved from v2.0.1 to v2.0.2, and the Worker gateway plus both Node packages moved from v2.0.0 to v2.0.2 (`GATEWAY_VERSION`, health payload and User-Agent included).

---

## [v2.0.1] - 2026-07-25

### Added
- **Developer API & Geo-Routing (`GeoRestrictAPI`)**:
  - `GeoRestrictAPI.lookup(ip)`: Asynchronous raw geolocation lookup without rule evaluation or kick decisions.
  - `GeoRestrictAPI.getCountryCode(ip)`: Asynchronous ISO 2-letter country code query for custom proxy routing.
  - `GeoRestrictAPI.isVpn(ip)`: Asynchronous VPN / proxy / hosting node detection.
  - `GeoRestrictAPI.getAsn(ip)` and `GeoRestrictAPI.getIsp(ip)`: Network AS number and provider queries.
  - `GeoRestrictAPI.checkIp(ip, playerName, uuid, bypass)` and `isAllowed(ip, playerName)`.
  - Automatic `ServicesManager` registration on Paper/Spigot backends.
- **Geyser & Floodgate (Bedrock) Integration**:
  - Reflection-based fail-safe wrapper for Floodgate API (`FloodgateHandler`).
  - Configurable `floodgate:` settings block (`enabled`, `bypassGeorestrict`, `bypassVpnCheck`).
  - Discord embed placeholders `%xuid%` (Bedrock Xbox User ID) and `%device_os%`.
- **Automatic Config Schema Migration (v5)**:
  - Bumped `configVersion` from 4 to 5.
  - Older v2.0.0 configs auto-migrate on load, adding new `floodgate:` defaults in-place without losing user settings.

### Fixed & Improved
- **Discord Webhook Threading**: Moved Discord webhook delivery to a dedicated single-thread executor (`GeoRestrict-Discord`), preventing slow webhooks from stalling player lookups.
- **Cache Debounce Scheduling**: Replaced `ForkJoinPool` sleeping in cache save debouncing with a `ScheduledExecutorService`.
- **Bukkit Pending-Check Pruning**: Replaced inline HashMap cleaning with a periodic 60s background task.
- **Expanded VPN Keywords**: Added `datacamp` (DataCamp Limited AS62005 / Proton VPN free servers), `tzulo`, `leaseweb`, `m247`, `proton`, and `datacenter` to default `vpnKeywords`.
- **Crawler Optimization**: Created `robots.txt` and `sitemap.xml` on the documentation site.

---

## [v2.0.0] - 2026-07-14

### Added
- **Multi-Platform Single JAR**: One shaded JAR runs on Paper, Spigot, Bukkit, Purpur, Folia, BungeeCord, Waterfall, and Velocity.
- **Rule Engine**: Country ALLOWLIST/BLOCKLIST modes, ASN filtering, and ISP keyword VPN detection.
- **Fail-Closed Architecture**: Cloudflare Worker Gateway with VPS MaxMind MMDB fallback.
- **Atomic Local Cache**: Bounded `geo_cache.json` with 5-second debounced atomic disk persistence and scheduled expiry cleanup.
- **Discord Webhooks**: Custom embeds with IP masking enabled by default.
- **Folia Support**: Native detection and regionized scheduler integration.

---

## [v1.0.0] - Initial Release

- Basic country filtering plugin for Bukkit.

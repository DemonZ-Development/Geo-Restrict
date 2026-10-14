# Changelog

All notable changes to the GeoRestrict project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v2.1.0] - 2026-10-14

### Added
- Self-contained native Pumpkin Rust/WASI plugin for Java and Bedrock admission, with local blocking, country/ASN/VPN rules, persistent caching and native commands.
- Optional Geyser integration resolves the trusted Bedrock socket address and reuses existing Bedrock bypass settings.
- Additional server adapter candidates for Sponge, Geyser standalone and generic Java gateway deployments; live compatibility is reported separately.
- Separate Fabric, Forge and NeoForge dedicated-server mod artifacts sharing the existing rule engine, cache and public API, with asynchronous native login hooks, native permission checks and Brigadier commands.
- A resumable Minecraft 1.20–26.3 release/software inventory and real dedicated-server matrix, recording exact loader builds and upstream gaps.
- Opt-in `blockLocalConnections` and `kickMessageLocalConnection` settings. Loopback, LAN, link-local and IPv6 unique-local player addresses are rejected before geolocation; no fixed IP list is needed.
- Config schema v6 migration adds the new settings with blocking disabled by default and preserves existing values.
- Regression coverage for public joins, local rejection, mapped/scoped IPv6, bypasses, reload and gateway/cache isolation.
- Repeatable live platform, forwarding, API, addon and HTTP service harnesses with a versioned coverage report.

### Fixed
- Local blocking now also covers older offline CraftBukkit releases that omit asynchronous pre-login events, without making HTTP requests on the server thread. Successful asynchronous decisions are retained to prevent rechecking a forwarded public player against its proxy's local address.
- Non-ASCII IPv4 digits are rejected before Java's address resolver, preventing malformed IP text from causing DNS requests.
- `LOCAL` in a country `BLOCKLIST` now rejects local player addresses, including `127.0.0.1`. Previously, all non-public IPs skipped country evaluation. This form uses `kickMessageCountry`; the explicit flag uses `kickMessageLocalConnection`.
- A null `floodgate` config section now receives defaults instead of throwing during player checks.
- Worker and fallback expanded/mapped IPv6 handling now classifies local addresses correctly and rejects malformed compression before querying providers.
- Modrinth update lookups now percent-encode their query instead of receiving HTTP 400.
- Docker now binds container interfaces and authenticates its health probe with the configured token formats; the base image uses Node 22 LTS.

### Compatibility
- Existing server and proxy login hooks, Bukkit operator/permission bypasses, and Floodgate full bypass remain intact. Public IPs keep their country, ASN and VPN rules.
- Install on the public-facing proxy or configure trusted player IP forwarding before enabling local blocking on a backend that sees only a local proxy/Geyser address. Other reserved ranges retain their existing behavior.
- Worker and fallback packages are now v2.1.0 and share strict IPv4/IPv6 literal parsing.

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

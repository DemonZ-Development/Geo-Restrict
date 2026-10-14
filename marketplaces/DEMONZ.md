![GeoRestrict Access Control Suite](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict

**High-performance geographic, ASN, VPN, and local connection access control engine for Minecraft servers, proxies, native Rust/WASI runtimes, and standalone network architectures.**

GeoRestrict provides deterministic, local-first access control across your entire infrastructure. Whether running a single Minecraft server, a multi-loader modded cluster, a native Rust Pumpkin node, or a standalone network reverse proxy, GeoRestrict enforces your rules using an atomic multi-tier cache with Cloudflare edge and offline MaxMind/DB-IP fallbacks.

---

## Deployment Modes

### 1. Minecraft Servers & Proxies (Single Universal JAR)
One shaded Java 17+ JAR with native integration across:
- **Paper, Purpur, Spigot, Bukkit**
- **Folia** (native regionized multithreaded scheduler)
- **Velocity** (native asynchronous pre-login events)
- **BungeeCord & Waterfall**
- **Geyser & Floodgate** (Bedrock Xbox XUID detection, device OS, and bypasses)

### 2. Multi-Loader Modded Servers (Dedicated Mod JARs)
Native server-side mod adapters sharing the core rule engine and developer API:
- **Fabric** (`georestrict-fabric-2.1.0.jar`)
- **Forge** (`georestrict-forge-2.1.0.jar`)
- **NeoForge** (`georestrict-neoforge-2.1.0.jar`)
- **Sponge** (`georestrict-sponge-2.1.0.jar`)
- **Geyser Standalone** (`georestrict-geyser-2.1.0.jar`)

### 3. Native Pumpkin WASI Plugin (Rust / WebAssembly)
- Self-contained WASI component (`georestrict-pumpkin-2.1.0.wasm`) compiled for the high-performance Rust server **Pumpkin**.
- Native Java and Bedrock connection admission.
- Zero Java bridge required; executes directly inside the Pumpkin WASI runtime with local disk caching and native commands.

### 4. Standalone Network Daemon & Reverse Proxy Gate
- Headless runner (`georestrict-standalone-2.1.0.jar`) for generic networks, TCP proxies, or sidecars.
- Run access evaluation and IP inspection outside of any game server process.
- Integrates with reverse proxies and auth filters.

### 5. Edge & Fallback Infrastructure
- **Cloudflare Worker Gateway**: Multi-provider edge router with 24-hour edge cache, latency-based provider failover (IPinfo, IP-API Pro), and token authentication.
- **Self-Hosted VPS Gateway (Docker / Node 22)**: Completely offline local MMDB fallback server using monthly DB-IP Lite Country and ASN databases under CC BY 4.0.

---

## Core Capabilities

- **Country Rules**: ISO 3166-1 two-letter code `ALLOWLIST` or `BLOCKLIST`.
- **ASN & ISP Control**: Target specific autonomous systems and hosting networks.
- **VPN / Proxy / Datacenter Filtering**: Keyword heuristics and hosting detection.
- **Local Connection Access Control**: Opt-in `blockLocalConnections` rejects loopback, LAN, link-local, and unique-local IPv6 addresses before remote queries occur.
- **Atomic Persistence**: Bounded JSON cache with atomic disk persistence, scheduled expiry pruning, and zero server-thread stalls.
- **Developer API (`GeoRestrictAPI`)**: Direct asynchronous queries for country code, ASN, ISP, VPN flags, and batch lookups without kicking players.
- **Discord Alerts**: Real-time webhook notifications with configurable IP masking.

---

## Quick Start

### Minecraft / Proxy
1. Download `georestrict-2.1.0.jar` (or your loader's dedicated mod jar).
2. Drop it into `plugins/` or `mods/` and restart the server.
3. Configure `plugins/GeoRestrict/config.yml` and reload with `/georestrict reload`.

### Pumpkin (Rust)
1. Download `georestrict-pumpkin-2.1.0.wasm`.
2. Place it in `plugins/` and start Pumpkin.

### Standalone Daemon
```bash
java -jar georestrict-standalone-2.1.0.jar --config config.yml
```

---

## Resources & Documentation

- [Official Documentation & Field Guide](https://georestrict-docs.pages.dev/)
- [GitHub Repository](https://github.com/DemonZ-Development/Geo-Restrict)
- [DemonZ Development Community](https://demonz.org/)
- [Discord Support](https://discord.com/invite/GYsTt96ypf)
- Released under [GPL-3.0](https://github.com/DemonZ-Development/Geo-Restrict/blob/main/LICENSE).

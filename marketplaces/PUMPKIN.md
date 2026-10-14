![GeoRestrict for Pumpkin](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict for Pumpkin

**High-performance native Rust/WASI geographic access control, ASN filtering, and VPN detection for Pumpkin servers.**

GeoRestrict for Pumpkin is a self-contained WebAssembly (WASI) component built directly for the [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) high-performance Minecraft server implementation. It executes inside Pumpkin's WASI runtime without requiring Java, a JVM bridge, a Bukkit shim, or an external sidecar daemon.

---

## Capabilities

- **Native Rust WASI Component**: Compiled to `wasm32-wasip2` using Pumpkin's native plugin API (`pumpkin-plugin-api`).
- **Dual Java & Bedrock Admission**:
  - Intercepts Java players via `AsyncPlayerPreLoginEvent`.
  - Intercepts native Bedrock players via `PlayerLoginEvent`.
  - Socket IP addresses are resolved directly from Pumpkin's connection events (never from client-manipulated handshake hostnames).
- **Local Connection Filtering**:
  - Opt-in `blockLocalConnections: true` rejects loopback (`127.0.0.1`), RFC1918 private LAN, link-local, and unique-local IPv6 before any gateway request occurs.
  - Adding `LOCAL` to a country `BLOCKLIST` evaluates and rejects local connections with your custom `kickMessageCountry`.
- **Geographic & Network Rules**:
  - ISO 3166-1 alpha-2 country `ALLOWLIST` or `BLOCKLIST`.
  - Autonomous System Number (ASN) allowlists and blocklists.
  - ISP keyword heuristics and datacenter / proxy flags.
- **Local Persistence & Caching**:
  - Bounded local JSON cache stored in `plugins/data/georestrict/cache.json`.
  - Configurable `cacheTtlDays` and `maxCacheEntries`.
  - Local decisions and cached hits never stall player admission.
- **Native Commands & Permissions**:
  - Commands: `/gr info`, `/gr reload`, `/gr check <ip>`, `/gr cache clear` (with `/georestrict` alias).
  - Permission: `georestrict:admin` (granted to server operators by default).
- **Discord Notifications**:
  - Webhook delivery for blocked connection attempts with IP masking enabled by default.
  - Fail-safe non-blocking HTTP dispatch.

---

## Installation

1. Download **`georestrict-pumpkin-2.1.0.wasm`** from the [GitHub Releases](https://github.com/DemonZ-Development/Geo-Restrict/releases/tag/v2.1.0) or [DemonZ Development](https://demonz.org/projects/georestrict).
2. Place `georestrict-pumpkin-2.1.0.wasm` into your Pumpkin server's `plugins/` directory:
   ```text
   pumpkin/
   ├── plugins/
   │   └── georestrict-pumpkin-2.1.0.wasm
   ```
3. Start Pumpkin. The server will request permission grants:
   - **Own-folder filesystem access** (for `plugins/data/georestrict/` configuration and cache).
   - **Outbound HTTP access** (for querying the lookup gateway and optional Discord webhooks).
4. Edit the generated configuration file at `plugins/data/georestrict/config.yml`.
5. Configuration changes take effect on the next player join, or immediately with `/gr reload`.

---

## Minimal Configuration Example

```yaml
mode: "BLOCKLIST"
countryList:
  - "CN"
  - "RU"
  - "LOCAL"

blockVpn: true
blockLocalConnections: false

kickMessageCountry: "Connections from your region are not permitted."
kickMessageVpn: "VPN and proxy connections are not permitted on this server."
kickMessageLocalConnection: "Local and loopback connections are not permitted."

gatewayUrl: "https://geoprotect.demonzdevelopment-e64.workers.dev/"
cacheTtlDays: 7
maxCacheEntries: 100000
```

---

## Building from Source

To compile the WASI component from source:

```bash
# Add the WebAssembly WASI target
rustup target add wasm32-wasip2

# Build the release component
cargo build --locked --release --target wasm32-wasip2 --manifest-path adapters/pumpkin/Cargo.toml
```

The compiled WASI component is output to:
`adapters/pumpkin/target/wasm32-wasip2/release/georestrict_pumpkin.wasm`

---

## Project Links & Support

- **DemonZ Development**: [demonz.org](https://demonz.org/)
- **Documentation**: [georestrict-docs.pages.dev](https://georestrict-docs.pages.dev/)
- **GitHub Repository**: [DemonZ-Development/Geo-Restrict](https://github.com/DemonZ-Development/Geo-Restrict)
- **Discord Support**: [discord.gg/GYsTt96ypf](https://discord.com/invite/GYsTt96ypf)
- **License**: [GPL-3.0-or-later](https://github.com/DemonZ-Development/Geo-Restrict/blob/main/LICENSE)

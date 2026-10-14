# GeoRestrict dedicated server mods (v2.1.0 candidate)

Fabric, Forge and NeoForge builds use the same configuration, rule engine,
HTTP gateway, disk cache and public `GeoRestrictAPI` as the plugin. Clients do
not need GeoRestrict. Integrated single-player servers are excluded.

These are unreleased artifacts in this checkout. The requested compatibility
range is Minecraft **1.20 through 26.3**, covering 24 stable game releases.
Loader releases do not exist for every combination. Consult the exact live
matrix in [`version-results-2026-10-10.json`](../test/live/version-results-2026-10-10.json), including loader
build numbers, beta releases, upstream gaps and individual checks.

## Build and install

Run from the repository root with Maven and JDK 17 or newer:

```sh
mvn -f mods/pom.xml clean package
```

Select one artifact for the dedicated server's loader:

| Loader | Artifact |
| --- | --- |
| Fabric | `mods/fabric/target/georestrict-fabric-2.1.0.jar` |
| Forge | `mods/forge/target/georestrict-forge-2.1.0.jar` |
| NeoForge | `mods/neoforge/target/georestrict-neoforge-2.1.0.jar` |

Put it in `mods/` and restart. Fabric API is not required. Minecraft 1.20–1.20.4
needs Java 17, 1.20.5–1.21.11 needs Java 21, and 26.x needs Java 25. Use the
loader/runtime requirements for the selected build.

Configuration and cache are created at `config/georestrict/config.yml` and
`config/georestrict/geo_cache.json`, under the loader's configured config root.
Existing plugin YAML settings can be copied to this location; schema migration
adds missing defaults. The ordinary server/proxy plugin JAR goes in `plugins/`,
while these separate loader JARs go in `mods/`.

## Local players and proxies

Local-player blocking is opt-in and requires no fixed player IP list:

```yaml
blockLocalConnections: true
kickMessageLocalConnection: "Local network connections are not allowed on this server."
```

`LOCAL` in a country `BLOCKLIST` also rejects local players. Local ranges are
identified automatically and never sent to geolocation providers. Status pings
continue to work; public players keep their country, ASN and VPN rules.
Native operators retain their join bypass. Vanilla bans, whitelist and capacity
checks keep their original decisions and disconnect messages.

A backend seeing only the local address of a proxy cannot infer the original
player's IP. Enable blocking at the public-facing GeoRestrict proxy, or configure
the loader's trusted player-IP forwarding integration before enabling it on a
backend. Leave blocking disabled on an unforwarded backend. The production mods
do not trust client-supplied hostname forwarding data.

## Administration and API

Use `/georestrict` or `/gr`, with the same `check`, `cachestats`, `purgecache` and
`reload` subcommands. Commands require native Minecraft permission level two
(or its newer permission-set equivalent). Replies return to the server thread.
Native commands show plain text. Configuration changes are also watched and
reloaded automatically; commands are restored after datapack reloads.

Other server mods can consume `zip.linuxaddict.georestrict.api.GeoRestrictAPI`.
Compile against `georestrict-core` and declare a loader dependency on
`georestrict`; do not bundle another copy of the core/API. The API's explicit
bypass remains available. Bukkit permission nodes and bStats registration are
plugin platform features; mod administration uses Minecraft's permissions.

## Architecture and validation

`core` compiles shared project sources without server APIs. `common` contains
lifecycle, asynchronous admission and Brigadier adapters. The loader modules
provide their own entrypoints and metadata. SnakeYAML is relocated; Minecraft's
Gson and SLF4J are used without bundling duplicate copies.

A small reflection boundary selects explicit named, Forge SRG and Fabric
intermediary roles. Required Mixins enforce the presence of login/lifecycle
hooks. Geolocation runs on the shared service workers; the server tick polls a
bounded future rather than blocking on HTTP. Cache shutdown has both a server
lifecycle hook and a JVM shutdown fallback.

The isolated companion fixture is built only with `-P live-fixture`. Its synthetic
addresses are accepted only over loopback. **Never distribute or install
`GeoRestrictLiveFixture.jar` on a production server.** The fixture is excluded
from the normal build and all three release artifacts.

See [the live harness](../test/live/README.md) for repeatable tests. The matrix
covers actual dedicated servers and TCP admission; offline protocol clients do
not substitute for authenticated account or visual gameplay testing.

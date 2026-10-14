# GeoRestrict 2.1.0 for Pumpkin

This is a native Pumpkin plugin written in Rust and compiled to a WASI component.
Its admission rules run inside Pumpkin. It does not need Java, a Bukkit plugin,
a standalone admission bridge, or a second GeoRestrict process.

The plugin uses Pumpkin's `AsyncPlayerPreLoginEvent` for Java and
`PlayerLoginEvent` for native Bedrock. Existing event denials are preserved.
Server-list status is outside the admission handlers. The IP comes from
Pumpkin's connection event/player API, never a client-supplied join hostname.

## Build and installation

Install Rust and the `wasm32-wasip2` target, then run:

```sh
rustup target add wasm32-wasip2
cargo build --locked --release --target wasm32-wasip2 --manifest-path adapters/pumpkin/Cargo.toml
```

Copy `target/wasm32-wasip2/release/georestrict_pumpkin.wasm` into Pumpkin's
`plugins` directory and start the server. Pumpkin asks to approve the plugin's
own-folder read/write permissions and outbound HTTP permission. Outbound HTTP
is used for the existing GeoRestrict lookup gateway and an optional Discord
webhook. Socket/listener/environment permissions are not requested.

The packaged 2.1.0 candidate contains `georestrict-pumpkin-2.1.0.wasm`;
copy that file directly into `plugins/`. Keep one GeoRestrict component there.
Its `source/` folder contains the native Rust sources and lockfile. To rebuild
the packaged sources, change into `source/` and use the build command above.
The lockfile pins the exact Pumpkin SDK release; Cargo ignores the build-metadata
suffix in version constraints, so keep `--locked` when rebuilding.

Pumpkin generates `plugins/data/georestrict/config.yml`. Configure the same
country, ASN, VPN, gateway and kick-message keys used by the Java distribution.
Local admission remains allowed by default. To deny LAN/loopback players, set
`blockLocalConnections: true` or put `LOCAL` in a country `BLOCKLIST`. This does
not require a fixed player IP list. On a backend, keep these settings off until
the server supplies each player's real address through trusted forwarding.

Configuration is read at each admission, so file edits apply to the next join.
Malformed configuration denies new admissions with an administrator message.
The lookup cache persists in `cache.json`, honors `cacheTtlDays` and is capped by
`maxCacheEntries`. Local decisions do not make gateway requests.

Commands: `/gr info`, `/gr reload`, `/gr check <ip>`, `/gr cache clear`.
`/georestrict` is an alias. Commands require `georestrict:admin`, which defaults
to administrator operators. Operator identities bypass admission rules.
`/gr check` deliberately evaluates the address without an operator bypass.

The `floodgate` configuration section is retained for configuration portability:
its bypass switches apply to Pumpkin's native Bedrock players when `enabled`
is true. Pumpkin does not require Geyser or Floodgate to admit Bedrock players.
Microsoft/Xbox authentication belongs to Pumpkin; an authenticated connection
still has an IP and follows the same geographic admission rules.

The Rust adapter does not implement the Java update checker or a JVM lookup
thread pool; Pumpkin owns component scheduling. Supported runtime versions and
actual live-test results must be read from the release evidence, not inferred
from a successful build or from Java platform results.

The [native live evidence](../../test/live/pumpkin-results-2026-10-10.json) records 73 live
assertions and six rule-engine tests for Pumpkin `0.2.0+26.3-26.51` (Java 26.3
and Bedrock 1.26.51). Signed Velocity-format forwarding passes through wire
fixtures. Actual Velocity 4.0.0-6 cannot connect to this backend because of its
protocol version limit; an end-to-end pass through that build is not certified.

## Marketplace

The release component is suitable for a Pumpkin marketplace listing. Uploading,
signing and publishing require the project's marketplace owner account and are
separate from building this artifact. No listing has been published by this task.

License: GPL-3.0-or-later, matching GeoRestrict.

## Live test tools

The live harnesses below require the full project checkout and separately
prepared official runtimes/client dependencies. The candidate bundle includes
the component's corresponding Rust source and evidence; it excludes runtime
binaries, test dependencies and private server configuration.

`test/live/pumpkin.py` boots the official Pumpkin binary with the built component
and tests native Java and Bedrock admission. Bedrock uses `bedrock-protocol`
3.60.1, NetherNet and the native `@roamhq/wrtc` 0.10.0 backend in the isolated
test workspace. The pure JavaScript Werift backend failed its WebRTC handshake
on both stock and plugin-enabled Pumpkin; that failure occurred before admission.
It is retained as a client interoperability diagnostic.

`test/live/pumpkin_components.py` exercises the native gateway transport, rules,
cache, webhook, invalid configuration and signed forwarding wire fixtures.
`test/live/pumpkin_lifecycle.py` covers generated configuration and invalid
configuration at startup. `test/live/report_pumpkin.py` refuses mixed component
hashes, and `test/live/package_pumpkin.py` creates the native candidate bundle
with its corresponding source, locked dependency notices and evidence. Test
companions and their dependencies are excluded from the release component.
The clients are offline test identities; these runs do not certify signed-in
Microsoft/Xbox sessions or game rendering.

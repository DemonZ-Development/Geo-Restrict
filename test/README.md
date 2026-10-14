# GeoRestrict — Integration Test Harness

This folder contains a **standalone Maven module** that proves GeoRestrict's
geo-decision and player-kick behaviour without any external geo provider or a
live server.

It uses the plugin artifact installed in your local Maven repository and a small
**in-process mock gateway** (`MockGateway.java`) that returns
canned geo JSON for `?ip=<ip>`, mirroring the JSON contract the plugin expects
(`countryCode`, `asn`, `asName`, `isVpn`, `isHosting`, `isProxy`).

## What it proves

| Test class | What it verifies |
|---|---|
| `DecisionEngineIT` | The real rule engine: country/ASN/VPN rules, the reported `LOCAL` blocklist case, local IPv4/IPv6 blocking without gateway/cache use, unchanged public lookups, Floodgate/full bypasses, and failure policy. |
| `BukkitKickIT` | Real Bukkit pre-login and login handlers through MockBukkit: public and local decisions, permission/operator bypasses, and preservation of another login denial. |
| `ProxyLoginIT` | Real BungeeCord and Velocity login handlers with mocked platform events: local rejection, public joins, disabled-policy compatibility, and completed intents/continuations. |

## Run it

```bash
# from the plugin root (where pom.xml lives)
mvn -B install -DskipTests        # install the plugin artifact locally
mvn -B -f test/pom.xml test       # run the integration tests
```

or, in one step:

```bash
./test/run-tests.sh               # PowerShell: .\test\run-tests.ps1
```

The module is intentionally **not** part of the plugin build, so
`mvn verify` on the plugin stays green and the shaded jar is unaffected.

## Real platform startup smoke

`platform-smoke.ps1` starts the release jar on real Paper, Purpur, Folia,
BungeeCord, Waterfall and Velocity runtimes. It downloads verified platform
jars into the ignored `.smoke/` directory and checks the generated config,
startup message and clean shutdown logs.

```powershell
mvn -B clean verify
.\test\platform-smoke.ps1
```

Pass `-JavaCommand` when a platform needs a newer Java executable than the one
on `PATH`.

## Manual live testing

`dummy-gateway.js` is a standalone Node gateway for pointing a **real** server's
`gatewayUrl` at, so you can watch the plugin make real lookup + kick decisions
against canned data (no Cloudflare worker required). Edit the `DB` map to add
your own public IP mapped to a blocked country, then:

```bash
node dummy-gateway.js
# set gatewayUrl: "http://<this-host>:8799/" in the server's config.yml
```

## Live admission and service suites

[`live/README.md`](live/README.md) documents real TCP Minecraft clients, the separately loaded API consumer, direct/legacy/modern forwarding, Geyser/Floodgate/ViaVersion, real workerd and MMDB HTTP checks. These complement the mocked handler suites above.

Forwarded public addresses in the harness are synthetic trusted metadata on loopback; authenticated account joins and direct Internet-source connections need an appropriate staging environment.

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
| `DecisionEngineIT` | The real `GeoRestrictService` decision engine: country block/allow lists, ASN block list, VPN/hosting flag + keyword detection, private-IP short-circuit, `bypass`, cache reuse, and fail-closed vs fail-open on lookup failure. |
| `BukkitKickIT` | The real Bukkit pre-login lookup and login handler, loaded through MockBukkit. It verifies blocked, allowed, private-IP, and `georestrict.bypass` outcomes. |

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

## Known limitation

A *true socket kick* (a real player's connection being dropped mid-handshake)
cannot be demonstrated in a local sandbox: a locally-run server only ever sees
a **private** source IP, which the plugin intentionally bypasses before any
lookup (`NetworkUtils.isValidPublicIp` short-circuit), and this box's public IP
is not routable back to a locally-run server without firewall/port-forwarding
admin. `BukkitKickIT` exercises the login decision that produces the kick on an
internet-facing deployment.

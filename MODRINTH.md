<!--
  ~ GeoRestrict - High-performance geographic access control.
  ~ Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
  ~
  ~ This program is free software: you can redistribute it and/or modify
  ~ it under the terms of the GNU General Public License as published by
  ~ the Free Software Foundation, either version 3 of the License, or
  ~ (at your option) any later version.
  -->
# Modrinth listing — GeoRestrict

This file is the description for the [Modrinth project page](https://modrinth.com/plugin/georestrict).
Paste the markdown below into the Modrinth editor. Upload screenshots through
the Modrinth UI (the `![](...)` lines below are placeholders — replace the
URLs with your uploaded image URLs).

The tone is intentionally short and direct. Modrinth listings that read like
press releases get ignored. The body is what it is.

---

## What it does

GeoRestrict blocks or allows Minecraft players based on where they're
connecting from. Country-level filtering, ASN-level filtering, VPN
detection, Discord notifications, all in one plugin.

Works on Bukkit, Paper, Spigot, BungeeCord, Waterfall, Velocity, and
Folia. Same jar, same config, different entry point.

## Features

- Country allowlist or blocklist
- ASN allowlist or blocklist (filters by ISP / network)
- VPN / proxy / hosting detection via ISP keyword + provider boolean
- Local IP cache, capped at 100k entries, periodic purge, atomic writes
- Cloudflare Worker gateway with provider failover (ip-api.com → ipinfo.io)
- Direct fallback to ip-api.com if the Worker is down (opt-in)
- Discord webhook notifications on block events
- Modrinth update checker on a 6h timer
- Per-platform bStats metrics (no player data, no IPs)
- `/georestrict` subcommands: `check`, `cachestats`, `purgecache`, `reload`
- `georestrict.bypass` permission for trusted players

## Requirements

- Java 17 or newer on the server
- Network egress to `https://geoprotect.demonzdev.workers.dev` (or your
  own deployed Worker)
- Discord webhook URL if you want notifications

## Compatibility

- Minecraft **1.16 through 1.26.x**
- Paper, Spigot, Purpur, Bukkit (1.16+)
- BungeeCord, Waterfall
- Velocity 3.2.0+
- Folia (any recent build)

## Quick start

1. Drop the jar into your `plugins/` folder
2. Start the server once to generate `plugins/GeoRestrict/config.yml`
3. Edit the config — set `mode` to `BLOCKLIST` or `ALLOWLIST` and pick your
   countries
4. `/georestrict reload`
5. Done. New connections get checked from this point on.

## Configuration example

```yaml
# plugins/GeoRestrict/config.yml
mode: BLOCKLIST
countries:
  - RU
  - CN
asnMode: DISABLED
vpnCheckEnabled: true
gatewayUrl: "https://geoprotect.demonzdev.workers.dev/"
gatewayToken: ""
directFallbackEnabled: false
blockOnLookupFailure: true
maxCacheEntries: 100000
discord:
  enabled: false
  webhookUrl: ""
```

Fail-closed is the default in 2.1.0. If the Worker is unreachable, players
get blocked with `kickMessageLookupFailure`. Flip to `false` if you'd
rather take the risk of letting traffic through during an outage.

## Why a Cloudflare Worker

The plugin doesn't talk to ip-api.com or ipinfo.io directly. It talks to a
Worker, which talks to the providers. Reasons:

- You can put your own auth in front (the Worker enforces a token if you
  set `GATEWAY_TOKENS` in its env)
- Provider failover — if ip-api.com is rate-limiting you, the Worker tries
  ipinfo.io with a different token
- The plugin stays simple. It only knows how to talk to one URL.

If you don't trust the public Worker, deploy your own — `worker/src/index.js`
is in the repo, deploy with `npx wrangler deploy`. Free tier is more than
enough for a Minecraft server.

## Performance

- ~10k lookups/sec on a single core with cache warm (no Worker calls)
- Cache writes are debounced to every 5s
- A burst of 100 joins coalesces into a single disk write
- No reflection at runtime on the hot path (Folia uses reflection only at
  startup to detect the scheduler)

## What we don't do

- We don't log your players' IPs anywhere we control
- We don't run a central panel / dashboard
- We don't have a paid tier. It's all free, all GPLv3

## License

[GPL v3](https://github.com/DemonZ-Development/Geo-Restrict/blob/main/LICENSE).
You can fork it, modify it, ship it. Just keep the source open.

## Credits

Original author: **linuxaddict** — wrote GeoRestrict and ran with it for
years. Find him on [Modrinth](https://modrinth.com/user/linuxaddict).

Current maintainer: [Demonz Development](https://demonzdevelopment.online/).

## Links

- Source: https://github.com/DemonZ-Development/Geo-Restrict
- Issues: https://github.com/DemonZ-Development/Geo-Restrict/issues
- Docs: https://github.com/DemonZ-Development/Geo-Restrict/tree/main/docs
- Maintainer site: https://demonzdevelopment.online/

---

## Modrinth upload checklist

When you paste the above, you also need to set these in the Modrinth UI
(not in the markdown):

- **Project type**: Plugin
- **Categories**: Admin tools, Anti-griefing tools
- **Game versions**: 1.16, 1.17, 1.18, 1.19, 1.20, 1.21, 1.22, 1.23, 1.24, 1.25, 1.26
- **Loaders**: Spigot, Paper, Purpur, BungeeCord, Waterfall, Velocity, Folia
- **License**: GPL-3.0
- **Client / server side**: Server side only
- **Environment**: Server

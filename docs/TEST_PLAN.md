<!--
  ~ GeoRestrict - High-performance geographic access control.
  ~ Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
  ~
  ~ This program is free software: you can redistribute it and/or modify
  ~ it under the terms of the GNU General Public License as published by
  ~ the Free Software Foundation, either version 3 of the License, or
  ~ (at your option) any later version.
  -->
# GeoRestrict v2.0.0 — Manual Test Plan

This is the test plan for the v2.0.0 release. Everything here is a test you
run on a real server, not against a mock. Some tests are quick (a couple of
minutes), some need a real network to talk to. Plan a couple of hours per
platform.

The log strings and config keys below are the **actual** ones the code emits —
they were reconciled against the source for v2.0.0. If you see something
different in the log than what's written here, that's a bug worth reporting.

---

## 0. Build sanity

| # | Step | Expected |
|---|------|----------|
| 0.1 | `mvn -B clean verify` from the project root | BUILD SUCCESS, tests pass |
| 0.2 | Inspect `target/georestrict-2.0.0.jar` | Shaded jar with bStats classes bundled, ~700 KB |
| 0.3 | `jar tf target/georestrict-2.0.0.jar | grep -c bstats` | ≥ 4 (base + bukkit + bungee + velocity) |

The shade warnings about `META-INF/MANIFEST.MF` and `META-INF/versions/9.module-info` are
fine. They show up on every shaded jar that pulls in snakeyaml and gson.

---

## 1. First install (Bukkit / Paper)

| # | Step | Expected |
|---|------|----------|
| 1.1 | Drop the jar into `plugins/` of a fresh Paper 1.21 test server, start it | Console shows `GeoRestrict v2.0.0 starting...` then `GeoRestrict enabled.` |
| 1.2 | `plugins/GeoRestrict/config.yml` exists with all keys populated | Yes — defaults written on first run |
| 1.3 | `plugins/GeoRestrict/geo_cache.json` does **not** exist yet | Correct — cache is created lazily on first miss |
| 1.4 | Connect with a vanilla client (your real IP) | Join succeeds, no kick. First miss goes to the gateway |
| 1.5 | Stop the server cleanly (`stop` command, not kill) | Shutdown completes; cache flushed from the debounced write queue |
| 1.6 | Reconnect with the same client | Cache hit — no gateway request on this connect |

If you `kill -9` instead of `stop` (1.5), the last 5s of cache writes may be
lost. That's the documented debounce tradeoff, not a bug.

---

## 2. Country blocklist

| # | Step | Expected |
|---|------|----------|
| 2.1 | Set `countryMode: BLOCKLIST`, `countries: ["RU"]` in config, `/georestrict reload` | Console shows `Config reloaded.` |
| 2.2 | Connect from a Russian VPN (or borrow a RU friend's account) | Kicked with `kickMessageCountry` |
| 2.3 | Connect from your usual IP | Joined |
| 2.4 | Run `/georestrict check <your IP>` | Shows `Country:` matching the blocklist |

If 2.2 lets the player through, country-code normalization is broken. The
config loader uppercases the list; the evaluator uppercases the response.
Check the log for the `Blocked ... (country ... on blocklist)` line.

## 3. Country allowlist

| # | Step | Expected |
|---|------|----------|
| 3.1 | Set `countryMode: ALLOWLIST`, `countries: ["US","CA","GB"]`, reload | `Config reloaded.` |
| 3.2 | Connect from a US/CA/GB IP | Joined |
| 3.3 | Connect from any other country | Kicked with `kickMessageCountry` |

An allowlist bug usually shows up as "everyone gets kicked" or "no one gets
kicked." Check `/georestrict check <ip>` for the player in question.

## 4. ASN filtering

| # | Step | Expected |
|---|------|----------|
| 4.1 | Set `asnMode: BLOCKLIST`, `asns: [AS14061]` (DigitalOcean), reload | `Config reloaded.` |
| 4.2 | Connect from a DigitalOcean server running a test Minecraft client | Kicked with `kickMessageAsn` |
| 4.3 | Try with the ASN formatted as `14061` (no `AS` prefix) | Should still work — both forms are accepted |
| 4.4 | Switch to `asnMode: ALLOWLIST`, only allow your home ASN, reload | Other players get kicked with the ASN message |

ASN data comes from the gateway. If `/georestrict check <ip>` returns an
empty ASN, the gateway isn't returning ASN data — check the gateway's logs
and its provider configuration.

## 5. VPN detection

| # | Step | Expected |
|---|------|----------|
| 5.1 | Connect via a commercial VPN | ISP / org name in `/georestrict check` contains a keyword from `vpnKeywords`, or the provider flags it |
| 5.2 | Set `vpnCheckEnabled: false`, reconnect | Joined — keyword check is bypassed |
| 5.3 | Re-enable, then add a non-VPN player whose ISP happens to be a cloud provider | They'll get blocked. Fix: remove the keyword or grant bypass |

A common false positive is a player whose home ISP is "Hetzner" or similar.
The fix is keyword removal, not disabling the check.

## 6. Bypass permission

| # | Step | Expected |
|---|------|----------|
| 6.1 | On the Bukkit backend, OP a player who is otherwise blocked | Player can join |
| 6.2 | Grant `georestrict.bypass` (not OP) to a blocked player, reconnect | Player joins; the final Bukkit decision reads live permissions during login |
| 6.3 | On a proxy install (Bungee/Velocity) | Bypass is **not** enforceable at proxy login (no backend perm lookup at that stage). Documented limitation — install on the backend too if you need it there |

## 7. Cache behavior

| # | Step | Expected |
|---|------|----------|
| 7.1 | Connect 5 players from 5 different IPs, all new | 5 cache misses, 5 gateway requests |
| 7.2 | Restart the server, reconnect the same 5 | 5 cache hits, 0 gateway requests (until TTL expires) |
| 7.3 | Run `/georestrict cachestats` | Shows `Entries: 5`, file size, oldest entry age |
| 7.4 | Run `/georestrict purgecache`, reconnect | 5 new cache misses |
| 7.5 | Set `maxCacheEntries: 100` in config, restart, connect 150 unique IPs | Cache caps at 100, oldest are evicted, `cachestats` shows 100 entries |

The size cap is a hard cap, not a soft warning. For most servers this never
fires; for a busy proxy it can.

## 8. Worker outage policy

| # | Step | Expected |
|---|------|----------|
| 8.1 | Block the gateway's hostname in `/etc/hosts` (or set `gatewayUrl` to a known bad host) | Worker request fails within `connectionTimeoutMs` |
| 8.2 | Reconnect with `blockOnLookupFailure: true` | Player gets `kickMessageLookupFailure` |
| 8.3 | Reconnect with `blockOnLookupFailure: false` | Player is admitted after the failed lookup |

The plugin never contacts a provider directly. The Worker owns provider
selection and the protected local database fallback. This keeps one documented
network boundary in the plugin and avoids provider URLs embedded in the jar.

## 9. Discord webhook

| # | Step | Expected |
|---|------|----------|
| 9.1 | Set `discord.webhook` to a real webhook URL, reload | `Config reloaded.` |
| 9.2 | Block a player | Discord channel shows an embed with player name, IP, country, reason |
| 9.3 | Set a deliberately bad webhook URL, reload, block another | Plugin logs `Discord webhook returned HTTP ... after retry`, does not spam |
| 9.4 | Revert the URL, reload | Works again |

The webhook now retries once on transient (5xx/network) failures before
giving up.

## 10. Update checker

| # | Step | Expected |
|---|------|----------|
| 10.1 | Leave `updateCheck: true` (default) | OPs with `georestrict.admin` see the update notice on join |
| 10.2 | Temporarily set `PluginInfo.VERSION` to `2.3.0`, rebuild and start | No notice because the local build is newer |
| 10.3 | Set `PluginInfo.VERSION` to `1.9.0`, rebuild and start | Notice shows because the local build is older than `2.0.0` |
| 10.4 | Set it to `2.10.0` | No notice because numeric comparison treats it as newer |
| 10.5 | Set it to `2.0.0` (same version) | No notice (current == latest) |

Test 10.4 is the one that would have failed on a lexicographic comparator.
`parseVersion` is numeric, so `2.10.0` is correctly newer than `2.0.0`.

## 11. Config migration

| # | Step | Expected |
|---|------|----------|
| 11.1 | Take an older `config.yml` (configVersion < 4), drop it into the plugin folder | On load, console shows `Migrated config to v4` |
| 11.2 | Inspect the rewritten config | Has all current keys, **your values preserved**, merged into the existing structure (new keys aren't appended at the bottom) |
| 11.3 | Run `/georestrict reload` | No errors, schema version is now 4 |

## 12. BungeeCord / Waterfall

| # | Step | Expected |
|---|------|----------|
| 12.1 | Drop the jar into a fresh BungeeCord `plugins/` folder, start | Console shows `GeoRestrict v2.0.0 starting...` then `GeoRestrict enabled.` |
| 12.2 | Connect through the proxy | Goes through, the connection IP is the player's real IP |
| 12.3 | Block a country, reconnect | Kicked at the proxy — never reaches the backend |
| 12.4 | `proxy_protocol` or `ip_forward: true` on the backend | Backend plugin sees the real IP too |

If 12.2 shows the connection as `127.0.0.1`, the proxy isn't forwarding the
real IP. The plugin correctly **does not** geo-locate loopback — it bypasses.

## 13. Velocity

| # | Step | Expected |
|---|------|----------|
| 13.1 | Drop the jar into Velocity `plugins/`, start | `GeoRestrict v2.0.0 starting...` then `GeoRestrict enabled.` |
| 13.2 | Connect, block a country, reconnect | Kicked with the country message — the kick happens during the login continuation |
| 13.3 | `velocity-modern-forwarding` or `velocity-legacy-forwarding` on the backend | Backend sees the real IP |

If the kick never arrives, you may be on Velocity < 3.2.0. The async login
continuation API was added in 3.2.0.

## 14. Folia

| # | Step | Expected |
|---|------|----------|
| 14.1 | Drop the jar into a Folia `plugins/` folder, start | `GeoRestrict v2.0.0 starting...` then `GeoRestrict enabled.` |
| 14.2 | Connect, disconnect, reconnect 20 times in a row | No `IllegalStateException` in the log, no scheduler warnings |
| 14.3 | `/georestrict reload` from console | Returns OK |
| 14.4 | Watch the log while scheduled maintenance runs | No scheduler failure appears; cache maintenance continues on Folia's async scheduler |

The Folia adapter uses reflection against `io.papermc.paper.threadedregions`.
If a future Folia release changes that scheduler contract, GeoRestrict reports
the scheduling failure instead of pretending maintenance was registered.

## 15. Edge cases (private / CGNAT / IPv6)

| # | Step | Expected |
|---|------|----------|
| 15.1 | Connect a server admin from a CGNAT range (`100.64.0.0/10`) | Joined — CGNAT is in the private list, no lookup happens |
| 15.2 | Try `/georestrict check 100.64.0.1` | Allowed (private), no gateway call |
| 15.3 | Try `/georestrict check 192.0.2.1` (TEST-NET-1) | Allowed (private) |
| 15.4 | Try `/georestrict check 2001:db8::1` (documentation prefix) | Allowed (private) |
| 15.5 | Try `/georestrict check ::ffff:8.8.8.8` (IPv4-mapped IPv6 of a public IP) | Recurses to `8.8.8.8`, returns the geo result for that |

If 15.5 returns "allowed/private", the IPv4-mapped recursion is broken.

---

## 16. Cloudflare Worker gateway

| # | Step | Expected |
|---|------|----------|
| 16.1 | `cd worker && npm install` | No errors |
| 16.2 | `npx wrangler dev` | Worker running locally on `http://localhost:8787` |
| 16.3 | `curl "http://localhost:8787/?ip=8.8.8.8"` | Returns JSON with country, ASN, ISP, etc. |
| 16.4 | `curl "http://localhost:8787/?ip=not-an-ip"` | Returns 400, not 200 |
| 16.5 | With `GATEWAY_TOKENS=test` set in `.dev.vars`, `curl "http://localhost:8787/?ip=8.8.8.8"` without the token | Returns 401 |
| 16.6 | With the token: `curl -H "X-GeoRestrict-Token: test" "http://localhost:8787/?ip=8.8.8.8"` | Returns 200 |
| 16.7 | `npx wrangler deploy` | Deploys to Cloudflare, URL printed |
| 16.8 | Query one uncached public IP twice | First request reaches a provider; second is served without another provider call |
| 16.9 | Inspect the Worker configuration | `CACHE_TTL_SECONDS=86400`; no `GEO_CACHE` KV binding is required |
| 16.10 | Bind a test KV namespace as `GEO_CACHE` and rerun Worker tests | Optional KV path passes; removing the binding leaves Cache API and memory caching functional |

If 16.5 returns 200, the fail-closed auth is broken — critical, do not ship.
Note the header is **`X-GeoRestrict-Token`**, not `X-Gateway-Token`.

---

## 17. Local-MMDB fallback server

| # | Step | Expected |
|---|------|----------|
| 17.1 | `cd vps-gateway && npm ci --omit=dev` | No errors |
| 17.2 | `npm run update-db` | Current DB-IP Lite Country and ASN files download, validate, and install |
| 17.3 | `cp .env.example .env`, set a token, then `npm start` | Server listens on loopback `127.0.0.1:8787` |
| 17.4 | Query `/health` without the token | Returns 401 |
| 17.5 | Query `/health` with the token | Returns 200 with `database.ready=true` and the release month |
| 17.6 | Query `/?ip=8.8.8.8` with the token | Returns `US`, `AS15169`, and `provider=dbip-local` |
| 17.7 | Block outbound provider domains and repeat 17.6 | Still returns the local result; player lookup performs no provider request |
| 17.8 | Run `npm run update-db` again | Existing current release validates and no download occurs |
| 17.9 | Supply a corrupt partial MMDB and run the updater | Validation fails; the working database files remain intact |
| 17.10 | Force Worker providers to return errors, then query the Worker | Calls the fallback server last and returns `provider=dbip-local` |

The server is an independent final fallback, not another provider proxy. It reads
Country and ASN data from local files. The Lite files do not provide definitive
VPN/proxy/hosting/mobile flags, so the fallback still supports country, ASN,
and AS-name keyword rules but not provider-only network signals.

---

## 18. Stress / smoke

| # | Step | Expected |
|---|------|----------|
| 18.1 | Connect 50 bots in a 30-second window from unique IPs | First batch: cache misses, gateway requests. Within 60s: cache fills, no more requests |
| 18.2 | Watch the console for errors | No `OutOfMemoryError`, no `RejectedExecutionException` |
| 18.3 | Check the cache file size | Proportional to distinct IPs, capped at `maxCacheEntries` |
| 18.4 | `kill -9` the server during peak | On next start, the cache reloads what's on disk (last 5s may be lost — documented tradeoff) |

The kill -9 case (18.4) is a known limitation. Writes are debounced to every
5s for performance.

---

## 19. What "good" looks like

If everything above passes:

- `mvn verify` is green
- Unit tests pass
- Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity load without plugin warnings
- A new country blocklist works in under 60 seconds
- A VPN gets blocked, a residential IP doesn't
- The cache survives a clean restart
- The Worker returns 401 for unauthenticated requests if you configured tokens
- The Worker tries providers before the fallback server, and that server returns a local MMDB result when they fail
- Discord notifications arrive

If you found something that should work but doesn't, the issue tracker is
the place. Include the config, the log line, and the version.

---

## 20. What we don't test here

- A real-world bot-net attack. The plugin is just a filter; DDoS is upstream.
- A misbehaving CDN / proxy that strips `X-Forwarded-For` and replaces it with
  the proxy's IP. The plugin sees whatever IP the platform handed it.
- Provider outages are covered for Country and ASN when the local fallback server
  is configured and its MMDB files are current. Without that server, the plugin
  blocks after every provider fails unless you change `blockOnLookupFailure`.

These are not bugs. They are the world the plugin lives in.

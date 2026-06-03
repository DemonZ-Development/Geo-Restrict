<!--
  ~ GeoRestrict - High-performance geographic access control.
  ~ Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
  ~
  ~ This program is free software: you can redistribute it and/or modify
  ~ it under the terms of the GNU General Public License as published by
  ~ the Free Software Foundation, either version 3 of the License, or
  ~ (at your option) any later version.
  -->
# GeoRestrict v2.1.0 — Manual Test Plan

This is the test plan for the v2.1.0 release. Everything here is a test you
run on a real server, not against a mock. Some tests are quick (a couple of
minutes), some need a real network to talk to. Plan a couple of hours per
platform.

If a test fails, the **expected** column says what to look for. The
**failure** column says what to actually look for if it broke. They look
similar — that's the point.

---

## 0. Build sanity

| # | Step | Expected |
|---|------|----------|
| 0.1 | `mvn -B clean verify` from the project root | BUILD SUCCESS, 19 tests pass |
| 0.2 | Inspect `target/georestrict-2.1.0.jar` | Shaded jar with bStats classes bundled, ~700 KB |
| 0.3 | `jar tf target/georestrict-2.1.0.jar | grep -c bstats` | ≥ 4 (base + bukkit + bungee + velocity) |

The shade warnings about `META-INF/MANIFEST.MF` and `META-INF/versions/9.module-info` are
fine. They show up on every shaded jar that pulls in snakeyaml and gson.

---

## 1. First install (Bukkit / Paper)

| # | Step | Expected |
|---|------|----------|
| 1.1 | Drop the jar into `plugins/` of a fresh Paper 1.21 test server, start it | Console shows `[GeoRestrict] ... v2.1.0 ... starting...`, then `Worker scheme: https` |
| 1.2 | `plugins/GeoRestrict/config.yml` exists with all keys populated | Yes — defaults written on first run |
| 1.3 | `plugins/GeoRestrict/geo_cache.json` does **not** exist yet | Correct — cache is created lazily on first miss |
| 1.4 | Connect with a vanilla client (your real IP) | Join succeeds, no kick, log line `cached: <ip>` after the first miss |
| 1.5 | Stop the server cleanly (`stop` command, not kill) | Log shows `Cache saved.` before exit |
| 1.6 | Reconnect with the same client | Log shows `cache hit`, not a Worker request |

A bug in 1.5 (kill before save) means the cache write race is still there.

---

## 2. Country blocklist

| # | Step | Expected |
|---|------|----------|
| 2.1 | Set `mode: BLOCKLIST`, `countries: ["RU"]` in config, reload | `/georestrict reload` returns OK |
| 2.2 | Connect from a Russian VPN (or borrow a RU friend's account) | Kicked with `kickMessageCountry` |
| 2.3 | Connect from your usual IP | Joined |
| 2.4 | Run `/georestrict check <your IP>` | Shows `country: <code>` matching the blocklist |

If 2.2 lets the player through, the country code normalization is broken (it
uppercases the list but compares case-sensitively somewhere). Check the log
for `mode: BLOCKLIST countries: [RU]` and the lookup result.

## 3. Country allowlist

| # | Step | Expected |
|---|------|----------|
| 3.1 | Set `mode: ALLOWLIST`, `countries: ["US","CA","GB"]` | Reloaded |
| 3.2 | Connect from a US/CA/GB IP | Joined |
| 3.3 | Connect from any other country | Kicked with `kickMessageCountry` |

An allowlist bug usually shows up as "everyone gets kicked" or "no one gets
kicked." Check the result of `/georestrict check` for the player in question.

## 4. ASN filtering

| # | Step | Expected |
|---|------|----------|
| 4.1 | Set `asnMode: BLOCKLIST`, `asns: [AS14061]` (DigitalOcean) | Reloaded |
| 4.2 | Connect from a DigitalOcean VPS running a test Minecraft client | Kicked with `kickMessageAsn` |
| 4.3 | Try with the ASN formatted as `14061` (no `AS` prefix) | Should still work — both forms are accepted |
| 4.4 | Switch to `asnMode: ALLOWLIST`, only allow your home ASN | Other players get kicked with the ASN message |

ASN data comes from the Worker. If `/georestrict check <ip>` returns
`asn: "AS0"` or empty, the Worker isn't returning ASN data — check the
Worker's logs.

## 5. VPN detection

| # | Step | Expected |
|---|------|----------|
| 5.1 | Connect via a commercial VPN (NordVPN, Mullvad, etc.) | ISP / org name in `/georestrict check` contains a keyword from `vpnKeywords` |
| 5.2 | Set `vpnCheckEnabled: false`, reconnect | Joined — keyword check is bypassed |
| 5.3 | Re-enable, then add a non-VPN player whose ISP happens to be a cloud provider | They'll get blocked. Fix: remove the keyword or grant bypass |

A common false positive is a player whose home ISP is "Hetzner" or similar
(unusual but real). The fix is keyword removal, not disabling the check.

## 6. Bypass permission

| # | Step | Expected |
|---|------|----------|
| 6.1 | On the Bukkit backend, `op` a player who is otherwise blocked | Player can join, log shows `bypass: true` |
| 6.2 | On the **proxy** (Bungee/Velocity) install — bypass is enforced at the backend, not the proxy | A blocked player on the proxy still gets blocked even if you op them on the backend, because the proxy doesn't have a way to check backend permissions at login time |
| 6.3 | The plugin.yml comment notes this limitation | Yes |

The proxy bypass gap is by design. Document it in your server's handbook
if it confuses your staff.

## 7. Cache behavior

| # | Step | Expected |
|---|------|----------|
| 7.1 | Connect 5 players from 5 different IPs, all new | 5 cache misses, 5 Worker requests |
| 7.2 | Restart the server, reconnect the same 5 | 5 cache hits, 0 Worker requests (until TTL expires) |
| 7.3 | Run `/georestrict cachestats` | Shows entry count = 5, total lookups, hit count |
| 7.4 | Run `/georestrict purgecache`, reconnect | 5 new cache misses |
| 7.5 | Set `maxCacheEntries: 100` in config, restart, connect 150 unique IPs | Cache caps at 100, oldest are evicted, `cachestats` shows 100 entries |

The size cap is a hard cap, not a soft warning. If you have more than
`maxCacheEntries` distinct players in a session, the oldest are silently
dropped. For most servers this never fires; for a busy proxy it can.

## 8. Direct fallback

| # | Step | Expected |
|---|------|----------|
| 8.1 | Block the Worker's hostname in `/etc/hosts` (or set `gatewayUrl` to a known-bad host) | `directFallbackEnabled: true` is in config |
| 8.2 | Reconnect | Plugin tries the bad gateway, fails, falls back to `ip-api.com` direct, succeeds |
| 8.3 | Reconnect with `directFallbackEnabled: false` | Player gets `kickMessageLookupFailure` (because `blockOnLookupFailure: true`) |

Direct fallback is off by default in 2.1.0. Yes, it's convenient. No, you
shouldn't enable it in production unless you've thought about the fact that
your players' IPs now leave your server to a third party directly.

## 9. Discord webhook

| # | Step | Expected |
|---|------|----------|
| 9.1 | Set `discord.enabled: true`, paste a real webhook URL | Reload |
| 9.2 | Block a player | Discord channel shows an embed with player name, IP, country, reason |
| 9.3 | Set a deliberately bad webhook URL, reload, block another | Plugin logs `Discord webhook failed: 401` (or similar), does not spam |
| 9.4 | Revert the URL, reload | Works again |

A common failure here is `Content-Length` mismatch. We use
`setFixedLengthStreamingMode`, so if you see "body length mismatch" in the
log, that's the bug. We did not reintroduce that bug; if you see it,
report it.

## 10. Update checker

| # | Step | Expected |
|---|------|----------|
| 10.1 | Set `updateCheck.enabled: true`, `updateCheck.notifyOps: true` | OPs see the update notice on join |
| 10.2 | Bump the version in `plugin.yml` / `bungee.yml` / `velocity-plugin.json` to `2.2.0`, redeploy | Notice stops showing on next check (6h timer) |
| 10.3 | Set the version to `2.0.0-beta.1` | That's older than `2.1.0` — no notice |
| 10.4 | Set it to `2.10.0` | That's newer — notice shows |
| 10.5 | Set it to `2.1.0` (same version) | No notice (current == latest) |

Test 10.4 is the one that would have failed on the old version. The
`parseVersion` function uses numeric comparison, not lexicographic. So
`2.10.0` is correctly newer than `2.1.0`.

## 11. Config migration

| # | Step | Expected |
|---|------|----------|
| 11.1 | Take the v2.0.0 `config.yml` from a backup, drop it into the plugin folder | On load, log line `Config migrated from v3 to v4` |
| 11.2 | Inspect the rewritten config | Has all v2.1.0 keys, **your values preserved**, no new keys appended at the bottom |
| 11.3 | Run `/georestrict reload` | No errors, schema version is now 4 |
| 11.4 | Re-load the rewritten config into a v2.0.0 build | v2.0.0 ignores the new keys (or warns) — that's fine, we're testing migration in one direction |

The old migrator appended new keys to the bottom of your YAML. The new one
merges into the existing structure. If your rewritten config still has all
the new keys at the bottom, the migration didn't run.

## 12. BungeeCord / Waterfall

| # | Step | Expected |
|---|------|----------|
| 12.1 | Drop the jar into a fresh BungeeCord `plugins/` folder, start | `[GeoRestrict] ... detected platform: BungeeCord` |
| 12.2 | Connect through the proxy | Goes through, `socketAddress` is the player's real IP, not 127.0.0.1 |
| 12.3 | Block a country, reconnect | Kicked at the proxy — never reaches the backend |
| 12.4 | `proxy_protocol` or `ip_forward: true` must be on the backend for the backend plugin to also see the real IP | Yes |

If 12.2 shows `socketAddress` as `127.0.0.1`, the proxy isn't configured to
forward the real IP, and the plugin sees the proxy's loopback. The plugin
correctly **does not** try to geo-locate loopback — it just bypasses. The
fix is on the proxy side.

## 13. Velocity

| # | Step | Expected |
|---|------|----------|
| 13.1 | Drop the jar into Velocity `plugins/`, start | `[GeoRestrict] ... detected platform: Velocity` |
| 13.2 | Connect, block a country, reconnect | Kicked with the country message — the kick happens during the login event continuation |
| 13.3 | `velocity-modern-forwarding` or `velocity-legacy-forwarding` on the backend | Backend sees the real IP |

If the kick never arrives, you may be on Velocity < 3.2.0. The plugin uses
the async login event with continuation, which was added in 3.2.0.

## 14. Folia

| # | Step | Expected |
|---|------|----------|
| 14.1 | Drop the jar into a Folia `plugins/` folder, start | `[GeoRestrict] ... detected platform: Bukkit (Folia fork)` |
| 14.2 | Connect, disconnect, reconnect 20 times in a row | No `IllegalStateException` in the log, no scheduler warnings |
| 14.3 | `/georestrict reload` from console | Returns OK |
| 14.4 | Watch the log for `scheduler: folia` vs `scheduler: bukkit` | Should say `folia` |

The Folia adapter uses reflection against `io.papermc.paper.threadedregions`.
If Folia renames these methods in a future release, the plugin will
fall back to the Bukkit scheduler with a clear log warning. That's
intentional.

## 15. Edge cases (private / CGNAT / IPv6)

| # | Step | Expected |
|---|------|----------|
| 15.1 | Connect a server admin from a CGNAT range (`100.64.0.0/10`) | Joined — CGNAT is in the private list, no lookup happens |
| 15.2 | Try `/georestrict check 100.64.0.1` | Returns `private: true`, no Worker call |
| 15.3 | Try `/georestrict check 192.0.2.1` (TEST-NET-1) | Same — private |
| 15.4 | Try `/georestrict check 2001:db8::1` (documentation prefix) | Private |
| 15.5 | Try `/georestrict check ::ffff:8.8.8.8` (IPv4-mapped IPv6 of a public IP) | Recurses to `8.8.8.8`, returns the geo result for that |

If 15.5 returns "private", the IPv4-mapped recursion is broken. That's the
bug we fixed in 2.1.0.

---

## 16. Worker deployment (optional, only if you self-host)

| # | Step | Expected |
|---|------|----------|
| 16.1 | `cd worker && npm install` | No errors |
| 16.2 | `npx wrangler dev` | Worker running locally on `http://localhost:8787` |
| 16.3 | `curl "http://localhost:8787/?ip=8.8.8.8"` | Returns JSON with country, ASN, ISP, etc. |
| 16.4 | `curl "http://localhost:8787/?ip=not-an-ip"` | Returns 400, not 200 |
| 16.5 | With `GATEWAY_TOKENS=test` set in `.dev.vars`, `curl "http://localhost:8787/?ip=8.8.8.8"` without the token | Returns 401 |
| 16.6 | With the token: `curl -H "X-Gateway-Token: test" "http://localhost:8787/?ip=8.8.8.8"` | Returns 200 |
| 16.7 | `npx wrangler deploy` | Deploys to Cloudflare, URL printed |

If 16.5 returns 200, the fail-closed auth is broken. Critical bug — do not
ship without fixing it.

---

## 17. Stress / smoke

| # | Step | Expected |
|---|------|----------|
| 17.1 | Connect 50 bots in a 30-second window from unique IPs | First batch: cache misses, Worker requests. Within 60s: cache fills, no more requests |
| 17.2 | Watch the console for errors | No `OutOfMemoryError`, no `RejectedExecutionException` |
| 17.3 | Check the cache file size | Proportional to distinct IPs, capped at `maxCacheEntries` |
| 17.4 | `kill -9` the server during peak | On next start, the cache reloads what's on disk (last 5s may be lost — that's the documented tradeoff) |

The kill -9 case (17.4) is a known limitation. Writes are debounced to every
5s for performance. If you need exact-on-shutdown semantics, set the
`saveOnShutdown` config knob — yes, it's a 2.1.0 thing.

---

## 18. What "good" looks like

If everything above passes:

- `mvn verify` is green in CI
- 19 unit tests pass
- 4 platforms load without warnings
- A new country blocklist works in under 60 seconds
- A VPN gets blocked, a residential IP doesn't
- The cache survives a clean restart
- The Worker returns 401 for unauthenticated requests if you configured tokens
- Discord notifications arrive

If you found something that should work but doesn't, the issue tracker is
the place. Include the config, the log line, and the version.

---

## 19. What we don't test here

- A real-world bot-net attack. The plugin is just a filter; DDoS is upstream.
- A misbehaving CDN / proxy that strips `X-Forwarded-For` and replaces it with
  the proxy's IP. The plugin sees whatever IP the platform handed it.
- Provider outages. If ip-api.com is down and ipinfo.io is down, the Worker
  has nothing to return. The plugin will block (fail-closed) unless you
  change `blockOnLookupFailure`.

These are not bugs. They are the world the plugin lives in.

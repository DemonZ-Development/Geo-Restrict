![GeoRestrict geographic access rules for Minecraft](https://raw.githubusercontent.com/DemonZ-Development/Geo-Restrict/main/georestrict-banner.png)

# GeoRestrict

GeoRestrict is a small, auditable geographic access layer for Paper, Folia and Velocity networks. It checks country, ASN, VPN and proxy signals before admission, then keeps the normalized result in a bounded local cache.

## A good fit when…

- your community serves a known region;
- most automated connection attempts come from one hosting ASN;
- you need a temporary VPN rule during an attack; or
- you want the same policy at the proxy and backend without maintaining separate builds.

## v2.0.0 highlights

- One jar targeting Java 17 bytecode for Paper, Folia, Velocity and the wider Bukkit and Bungee ecosystem
- Country and ASN allowlist and blocklist modes
- Provider proxy flags plus editable ISP keyword matching
- Atomic cache persistence, expiry cleanup and a configurable 100,000 entry default cap
- Predictable behavior when lookup services are unavailable
- Bukkit bypass enforcement that respects permissions
- Discord notifications, update checks and practical admin commands
- Tests around config migration, cache behavior, network classification, rule evaluation and gateway routing

## Sensible failure choices

GeoRestrict blocks by default when there is no cached answer and the lookup service is unavailable. Set `blockOnLookupFailure: false` if you prefer to allow the connection during an outage.

## Quick start

Drop `georestrict-2.0.0.jar` into `plugins/`, start once, edit `plugins/GeoRestrict/config.yml`, and run `/georestrict reload`. Use `/georestrict check <ip|player>` to see the normalized data the rule engine will use.

## Before you put it at the edge

The [configuration guide](https://georestrict-docs.pages.dev/configuration) explains the safe defaults and why most networks should leave the generated lookup settings alone. The [Privacy Policy](https://georestrict-docs.pages.dev/privacy) documents the IP lookup path; the [Terms and Conditions](https://georestrict-docs.pages.dev/terms) cover use of the public services.

[Discord support and feedback](https://discord.com/invite/GYsTt96ypf) · [Source](https://github.com/DemonZ-Development/Geo-Restrict) · [Report an issue](https://github.com/DemonZ-Development/Geo-Restrict/issues) · GPL-3.0

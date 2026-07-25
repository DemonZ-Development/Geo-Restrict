![GeoRestrict geographic access rules for Minecraft](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict v2.0.1: geographic rules that stay in your config

GeoRestrict is for server teams that want country, ASN, VPN and proxy rules without renting another dashboard. The policy lives in readable YAML, and the same jar runs across backend and proxy platforms.

## Sponsored by Nexeu Hosting

[![nexeu-sponsor](https://whodoesntloveavatars.s3.fra.databucket.eu/assets/promo.png)](https://nexeu.zip/)

High-performance, affordable hosting for your Minecraft server. Premium hardware, instant setup, 24/7 support.

## Operational highlights

- Install once at the proxy edge or on a standalone backend
- Country and ASN allowlist and blocklist modes
- Provider proxy signals plus an ISP keyword list controlled by the operator
- Geyser / Floodgate Bedrock integration with custom Bedrock bypass modes
- Geo-Routing API (`GeoRestrictAPI`) for country routing without kicking players
- Bounded, expiring local cache with debounced atomic persistence
- Reliable lookup routing with cached results and clear outage behavior
- Configurable connection and read timeouts with a clear choice during lookup failures
- Discord delivery with dedicated async thread and `%xuid%` support
- IP masking enabled by default in notifications
- Manual lookup, cache statistics, purge and reload commands

## The normal setup

Install the jar, let it create the config, choose your rules and test a known address. The generated lookup settings work for most servers. Advanced infrastructure details are kept in the GitHub README for teams that actually need them.

## Live Statistics

[![bStats Bukkit](https://bstats.org/signatures/bukkit/32871.svg)](https://bstats.org/plugin/bukkit/georestrict/32871)
[![bStats BungeeCord](https://bstats.org/signatures/bungeecord/32872.svg)](https://bstats.org/plugin/bungeecord/georestrict/32872)
[![bStats Velocity](https://bstats.org/signatures/velocity/32873.svg)](https://bstats.org/plugin/velocity/georestrict/32873)

## Compatibility

GeoRestrict targets Java 17 bytecode and supports Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity. Use a newer Java runtime whenever the platform requires one.

## Honest boundaries

IP geolocation is approximate, and an uncached address must be sent to the configured lookup service. GeoRestrict explains that path, keeps recent answers locally and does not operate a player dashboard.

## Read before installing

The [setup guide](https://georestrict-docs.pages.dev/installation) explains the safe default path. Most operators should leave the generated network settings unchanged. Please also read the [Privacy Policy](https://georestrict-docs.pages.dev/privacy) and [Terms and Conditions](https://georestrict-docs.pages.dev/terms), which cover player IP processing and use of the public services.

[Discord support and feedback](https://discord.com/invite/GYsTt96ypf) · [Repository](https://github.com/DemonZ-Development/Geo-Restrict) · [Modrinth](https://modrinth.com/plugin/georestrict) · [Issue tracker](https://github.com/DemonZ-Development/Geo-Restrict/issues) · GPL-3.0

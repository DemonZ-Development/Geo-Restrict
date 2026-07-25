![GeoRestrict geographic access rules for Minecraft](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict

GeoRestrict helps Minecraft communities manage connections by country, internet network and VPN or proxy signals. It is useful for regional servers, private communities, and operators dealing with abuse from a specific data centre network.

## Sponsored by Nexeu Hosting

[![nexeu-sponsor](https://whodoesntloveavatars.s3.fra.databucket.eu/assets/promo.png)](https://nexeu.zip/)

High-performance, affordable hosting for your Minecraft server. Premium hardware, instant setup, 24/7 support.

## What it includes

- Country allowlists and blocklists
- ASN allowlists and blocklists
- Editable VPN, proxy and hosting detection rules
- Geyser / Floodgate Bedrock integration with custom bypass settings
- Developer API (`GeoRestrictAPI`) for Geo-Routing without kicking players
- Local lookup cache with automatic expiry
- Discord block notifications
- Clear behavior when the lookup service is temporarily unavailable
- Commands for lookup checks, cache management and reloads

One v2.0.1 jar supports Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity. GeoRestrict targets Java 17 bytecode, while the server itself may require a newer Java runtime.

## First setup

Place the jar in `plugins/`, restart, and edit `plugins/GeoRestrict/config.yml`. Start with a short list of countries and test it with `/georestrict check <ip>`. Add ASN or VPN rules later if you have a clear reason for them.

By default, GeoRestrict blocks a new connection when the cache is empty and every lookup path is unavailable. You can change that with `blockOnLookupFailure: false`.

## Live Statistics

[![bStats Bukkit](https://bstats.org/signatures/bukkit/32871.svg)](https://bstats.org/plugin/bukkit/georestrict/32871)
[![bStats BungeeCord](https://bstats.org/signatures/bungeecord/32872.svg)](https://bstats.org/plugin/bungeecord/georestrict/32872)
[![bStats Velocity](https://bstats.org/signatures/velocity/32873.svg)](https://bstats.org/plugin/velocity/georestrict/32873)

## Where lookup data goes

On a cache miss, the connecting IP is sent to the configured lookup service for country, ASN and proxy information. GeoRestrict does not operate a central player dashboard. The full data path is explained in the Privacy Policy.

[Source code and documentation](https://github.com/DemonZ-Development/Geo-Restrict) · [Issues](https://github.com/DemonZ-Development/Geo-Restrict/issues) · GPL-3.0

## Help, privacy and terms

Start with the [wiki](https://georestrict-docs.pages.dev/) and leave the generated network settings unchanged unless you understand the lookup path. Read the [Privacy Policy](https://georestrict-docs.pages.dev/privacy) and [Terms and Conditions](https://georestrict-docs.pages.dev/terms) before using the public services. Found a problem or have a suggestion? Join the [Demonz Development Discord](https://discord.com/invite/GYsTt96ypf). We genuinely want to hear it.

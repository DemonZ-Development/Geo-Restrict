![GeoRestrict geographic access rules for Minecraft](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict

**Friendly geographic access control for Minecraft servers and proxies.**

GeoRestrict lets you make practical rules around where connections come from. Block a short list of countries, keep a private server open to one region, stop traffic from a troublesome hosting network, or add a VPN rule while an attack is active. The policy stays in your own `config.yml`, where you can read it and change it.

## The useful bits

- Country allowlists and blocklists
- ASN rules for individual ISPs and hosting networks
- Configurable VPN, proxy and hosting detection
- Geyser / Floodgate (Bedrock) integration with custom bypass options
- Developer API (`GeoRestrictAPI`) for Paper, BungeeCord, and Velocity
- One v2.0.1 jar for Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity
- Local, bounded cache with atomic saves and automatic expiry cleanup
- Clear behavior when the lookup service is temporarily unavailable
- Discord block notifications with IP masking enabled by default
- `/georestrict check`, `cachestats`, `purgecache` and `reload`
- `georestrict.bypass` on servers compatible with Bukkit

## Start small

1. Put `georestrict-2.0.1.jar` in the server or proxy `plugins/` folder.
2. Restart once to create `plugins/GeoRestrict/config.yml`.
3. Pick `BLOCKLIST` or `ALLOWLIST` and add two letter country codes.
4. Run `/georestrict reload`.
5. Use `/georestrict check <ip>` to confirm the result before inviting everyone back.

You do not need to enable every feature on day one. Country rules are enough for many communities; ASN and VPN controls can wait until you have a specific problem to solve.

## When a lookup fails

v2.0.1 blocks by default when the cache is empty and the lookup service is unavailable. The player receives your `kickMessageLookupFailure`. Set `blockOnLookupFailure: false` if keeping the server reachable during an outage matters more than strict filtering.

## Privacy, without vague promises

On a cache miss, the connecting IP goes to the configured lookup service for country, ASN and proxy data. GeoRestrict does not run a central player panel or player analytics service. Standard aggregate bStats metrics are available per platform; bStats does not receive player names or IP addresses from this plugin. Most communities can keep the generated network settings unchanged.

## Requirements

- GeoRestrict targets Java 17 bytecode; use the newer Java version required by your platform
- Check the tested version table in the wiki for your Minecraft release
- A supported Bukkit server or proxy platform
- Outbound HTTPS access to the configured lookup service

## Help and project links

- [Source code](https://github.com/DemonZ-Development/Geo-Restrict)
- [Download on Modrinth](https://modrinth.com/plugin/georestrict)
- [Documentation](https://georestrict-docs.pages.dev/)
- [Privacy Policy](https://georestrict-docs.pages.dev/privacy)
- [Terms and Conditions](https://georestrict-docs.pages.dev/terms)
- [Discord support and feedback](https://discord.com/invite/GYsTt96ypf)
- [Bug reports](https://github.com/DemonZ-Development/Geo-Restrict/issues)
- [Demonz Development](https://demonzdevelopment.online/)

GeoRestrict was originally created by [linuxaddict](https://modrinth.com/user/linuxaddict) and is now maintained by the Demonz Development open source community. Licensed under GPL-3.0.

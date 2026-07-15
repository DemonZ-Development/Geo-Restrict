![GeoRestrict geographic access rules for Minecraft](https://georestrict-docs.pages.dev/assets/georestrict-banner.png?rev=20260714g)

# GeoRestrict v2.0.0

You should not need a security dashboard, a paid subscription, and an afternoon of tutorials just to respond to repeated connections from one network.

GeoRestrict puts country, ASN, VPN and proxy rules in a normal Minecraft config. It works on the backend or at the proxy edge, remembers recent lookups locally, and gives blocked players a message you control.

## Works with your stack

One jar supports Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity. Install it on the point where you want the rule enforced; you do not need copies on every backend when your proxy already sees the real player address.

## What you can control

- Allow or block countries with two letter ISO codes
- Allow or block individual networks by ASN
- Detect VPN, proxy and hosting traffic with provider flags and an editable keyword list
- Give trusted Bukkit players `georestrict.bypass`
- Send useful block events to Discord
- Inspect an IP or online player before changing a rule
- Choose whether joins are blocked or allowed during a lookup outage

## Install in four steps

1. Put `georestrict-2.0.0.jar` in `plugins/`.
2. Restart once.
3. Edit `plugins/GeoRestrict/config.yml`.
4. Run `/georestrict reload` and verify with `/georestrict check <ip>`.

GeoRestrict targets Java 17 bytecode. Use a newer Java runtime whenever your server platform requires one.

## Designed for real outages

Fresh results are served from a bounded local cache. Cache writes are atomic and coalesced, so a busy join period does not rewrite the file for every player. On a miss, the plugin uses the configured lookup service.

The default policy blocks when the cache is empty and every lookup route is unavailable. The player receives your lookup failure message. Set `blockOnLookupFailure: false` if availability is the higher priority for your community.

## Privacy in one paragraph

A cache miss sends the connecting IP to the configured lookup service for country, ASN and proxy data. GeoRestrict does not run a central player panel. Standard aggregate bStats metrics are supported, without sending player names or IP addresses from this plugin.

## Help and the important links

Most server owners should keep the generated network settings unchanged. The [wiki](https://georestrict-docs.pages.dev/) explains installation, rules and troubleshooting. Please read the [Privacy Policy](https://georestrict-docs.pages.dev/privacy) and [Terms and Conditions](https://georestrict-docs.pages.dev/terms) before using the public services.

[Discord support and feedback](https://discord.com/invite/GYsTt96ypf) · [Source](https://github.com/DemonZ-Development/Geo-Restrict) · [Releases](https://github.com/DemonZ-Development/Geo-Restrict/releases) · [Issues](https://github.com/DemonZ-Development/Geo-Restrict/issues) · GPL-3.0

Originally created by linuxaddict; now maintained by the Demonz Development open source community.

![GeoRestrict geographic access rules for Minecraft](https://raw.githubusercontent.com/DemonZ-Development/Geo-Restrict/main/georestrict-banner.png)

# Practical access rules, without another control panel

GeoRestrict gives your Minecraft server a geographic front door. You decide which countries and networks are welcome, whether VPN and proxy traffic needs extra scrutiny, and what players see when a rule stops their connection.

There is no remote policy panel to learn. Your rules stay in `config.yml`, recent answers stay in a local cache, and one v2.0.0 jar covers Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity.

## Features that earn their place

- Country blocklists for targeted abuse, or allowlists for regional/private servers
- ASN rules when the problem is one ISP or data centre network
- Configurable VPN and proxy detection instead of a fixed black box
- Discord notifications with player addresses masked by default
- `/georestrict check` for confirming a result before enforcing it
- Local cache with atomic writes, expiry and size limits
- A clear choice between blocking or allowing joins during lookup outages

## Set it up

Install the jar, restart once, and open `plugins/GeoRestrict/config.yml`. Begin with a short country list. Reload, test a known address, and only then add ASN or VPN rules if your traffic calls for them.

GeoRestrict targets Java 17 bytecode and supports the Bukkit, Spigot, Paper, Purpur, Folia, BungeeCord, Waterfall and Velocity families. Your platform may require a newer Java runtime.

## Honest privacy note

Geolocation needs an IP address. On a cache miss, the address goes to the configured lookup service. GeoRestrict does not run a central player log or dashboard. The Privacy Policy explains the full data path in plain language.

## Please read this part too

The [wiki](https://georestrict-docs.pages.dev/) covers installation and safe configuration. The [Privacy Policy](https://georestrict-docs.pages.dev/privacy) explains where an IP goes on a cache miss, and the [Terms and Conditions](https://georestrict-docs.pages.dev/terms) cover use of the public services. If something feels wrong or confusing, tell us in the [Demonz Development Discord](https://discord.com/invite/GYsTt96ypf). Listening to users is the point of maintaining this project.

[Download and source](https://github.com/DemonZ-Development/Geo-Restrict) · [Report a bug](https://github.com/DemonZ-Development/Geo-Restrict/issues) · GPL-3.0

# GeoRestrict Standalone & Network Gateway

**Independent geographic access control, VPN detection, and IP rule engine for custom TCP proxies, sidecars, and standalone servers.**

`georestrict-standalone` provides a headless, zero-dependency Java runtime wrapping GeoRestrict's core policy engine, atomic cache, and gateway connectors. It operates completely outside Minecraft, making it suitable for reverse proxies, microservice auth gates, custom game daemons, or container sidecars.

---

## Capabilities

- **Lightweight Standalone Daemon**: Operates with Java 17+ without Bukkit, Bungee, or Fabric APIs on classpath.
- **Unified Policy Engine**: Evaluates ISO 3166-1 country codes, autonomous system numbers (ASNs), hosting/datacenter ranges, and VPN keywords.
- **Local Network Gate**: Pre-filters loopback (`127.0.0.1`), private RFC1918 LAN addresses, link-local, and unique-local IPv6 before network lookup.
- **High-Performance Multi-Tier Cache**: Memory cache backed by atomic debounced JSON disk storage.
- **Configurable Gateway Connections**: Outbound HTTPS resolution against Cloudflare Worker edge or self-hosted DB-IP/MaxMind MMDB fallback servers.

---

## Running Standalone

### CLI Execution
```bash
java -jar georestrict-standalone-2.1.0.jar
```

### Programmatic Integration
Embed `georestrict-core` directly inside any Java application:

```java
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoResponse;

GeoRestrictService service = new GeoRestrictService(dataFolder, logger, taskScheduler);
service.enable();

service.lookup("8.8.8.8").thenAccept(response -> {
    System.out.println("Country: " + response.countryCode);
    System.out.println("ASN: " + response.asn);
    System.out.println("Hosting: " + response.isHosting);
});
```

---

## Documentation & Source

- [Documentation](https://georestrict-docs.pages.dev/)
- [GitHub](https://github.com/DemonZ-Development/Geo-Restrict)
- [DemonZ Development](https://demonz.org/)

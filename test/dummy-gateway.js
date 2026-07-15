/*
 * Standalone dummy GeoRestrict gateway for MANUAL end-to-end testing.
 *
 * It returns canned geo JSON (mirroring the plugin's expected contract) so you
 * can point a real server's `gatewayUrl` at it and watch the plugin make real
 * lookup + kick decisions without any Cloudflare worker or geo provider.
 *
 * Usage:
 *   node dummy-gateway.js
 *   # then set gatewayUrl: "http://<this-host>:8799/" in the server's config.yml
 *
 * Known IPs (edit DB below to add more):
 *   5.6.7.8       -> RU (blocked by a RU blocklist)
 *   1.2.3.4       -> US (allowed)
 *   <this box's public IP> -> RU (so a player from this IP gets kicked)
 *   anything else -> 400 invalid ip (simulates a lookup failure)
 */
const http = require("http");

const PORT = 8799;

// Map of source IP -> canned geo record. Add your own public IP here to
// simulate a player connecting from a blocked region.
const DB = {
  "5.6.7.8":       { countryCode: "RU", asn: "12389", asName: "Rostelecom",    isHosting: false, isVpn: false, isProxy: false },
  "1.2.3.4":       { countryCode: "US", asn: "15169", asName: "Google LLC",     isHosting: true,  isVpn: false, isProxy: false },
  "152.58.135.249":{ countryCode: "RU", asn: "12389", asName: "Rostelecom",    isHosting: false, isVpn: false, isProxy: false },
};

const server = http.createServer((req, res) => {
  const ip = (req.url.split("?")[1] || "").replace("ip=", "");
  const rec = DB[ip];
  if (!rec) {
    res.writeHead(400, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "invalid ip" }));
    return;
  }
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({
    ip,
    countryCode: rec.countryCode,
    countryName: rec.countryCode,
    asn: rec.asn,
    asName: rec.asName,
    isVpn: rec.isVpn,
    isHosting: rec.isHosting,
    isProxy: rec.isProxy,
    isMobile: false,
    version: "2.0.0",
  }));
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("dummy GeoRestrict gateway listening on http://0.0.0.0:" + PORT + "/");
});

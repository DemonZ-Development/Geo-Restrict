# GeoRestrict local fallback

This service is the last lookup path in GeoRestrict v2.0.2. The Cloudflare
Worker tries its external providers first. Only when every provider fails does
it call this protected fallback server, which reads Country and ASN data from local MMDB files. A
lookup does not make another geolocation API request.

```text
plugin cache
  -> Worker memory / Cache API / optional KV
  -> IPinfo Lite
  -> IP-API Pro when configured
  -> protected fallback server with local Country + ASN MMDB files
```

The service binds to `127.0.0.1:8787` by default. Put Caddy or nginx in front,
keep the shared token enabled, and allow the Worker to be the only normal
caller.

## What is stored locally

The updater downloads the current DB-IP Country Lite and ASN Lite MMDB files.
They cover IPv4 and IPv6, need no SQL server, and are updated monthly. Files
are downloaded to temporary paths, opened and tested, then moved into place.
An incomplete or corrupt download never replaces the working copy.

The free data provides country and ASN information. It does not provide a
definitive VPN, proxy, hosting, or mobile flag. During a total provider outage,
GeoRestrict can still enforce country and ASN rules and can still apply its AS
name keyword checks, but provider-only network flags are unavailable.

## Install

```bash
cd vps-gateway
cp .env.example .env
npm ci --omit=dev
npm run update-db
npm start
```

The startup is fail-closed: both MMDB files must exist and open successfully.

Smoke tests:

```bash
# Authorization header (Bearer or X-GeoRestrict-Token):
curl -H "Authorization: Bearer <token>" "http://127.0.0.1:8787/health"
curl -H "Authorization: Bearer <token>" "http://127.0.0.1:8787/?ip=8.8.8.8"
```

A successful lookup reports `provider: "dbip-local"`.

## Automatic database updates

The included systemd timer checks on boot and on the third day of every month.
If the latest release is already installed, the updater validates it and exits
without downloading it again.

```bash
sudo install -m 0644 deploy/georestrict-db-update.service /etc/systemd/system/
sudo install -m 0644 deploy/georestrict-db-update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now georestrict-db-update.timer
```

Run an update immediately with:

```bash
sudo systemctl start georestrict-db-update.service
sudo systemctl status georestrict-db-update.service
```

The repository also includes `deploy/georestrict-vps.service`. Its historical
file name is retained for compatibility, while the service description uses
“fallback server.” The unit verifies that `current/src/server.js` exists before
launch and rate limits failed restarts if a release link is wrong.

## Worker setup

Store the fallback server URL and shared token as Worker secrets. The secret
names retain their original `VPS_` prefix for compatibility:

```bash
wrangler secret put VPS_GATEWAY_URL
wrangler secret put VPS_GATEWAY_TOKEN
```

The URL should be the HTTPS address exposed by Caddy or nginx. The token must
match one value in `GATEWAY_TOKENS` on the fallback server.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `HOST` | `127.0.0.1` | Local listen address |
| `PORT` | `8787` | Local TCP port |
| `GATEWAY_TOKENS` | empty | One or more tokens accepted from the Worker |
| `DB_DIR` | `data` | Directory containing both MMDB files and release metadata |
| `DB_COUNTRY_PATH` | `<DB_DIR>/dbip-country-lite.mmdb` | Override the Country MMDB path |
| `DB_ASN_PATH` | `<DB_DIR>/dbip-asn-lite.mmdb` | Override the ASN MMDB path |
| `DBIP_RELEASE` | latest available month | Pin the updater to a `YYYY-MM` release |
| `DBIP_BASE_URL` | DB-IP free downloads | Override the download mirror |

`GATEWAY_TOKENS` accepts comma-separated, newline-separated, or JSON-array
values. Numbered variables such as `GATEWAY_TOKEN_1` are also supported.

## Security

- Keep port `8787` bound to loopback; do not open it in the server firewall.
- Expose only HTTPS through Caddy or nginx.
- Always configure a long random shared token.
- The service performs no outbound request during a player lookup.
- The service does not log the player IP.
- The database updater is the only component that contacts DB-IP.

## Data attribution

Country and ASN fallback data is provided by
[DB-IP Lite](https://db-ip.com/db/lite.php) under CC BY 4.0.

The gateway code is GPL-3.0, like the rest of GeoRestrict.

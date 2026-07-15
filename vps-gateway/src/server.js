/* GeoRestrict local-MMDB fallback. Route: GET /?ip=<address>. */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import express from 'express';

import { openGeoDatabase } from './geo-database.js';

const IPV4_PATTERN = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_PATTERN = /^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1?$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,6}(?::[0-9a-fA-F]{1,4}){1,6}$/;
const IPV4_MAPPED_PATTERN = /^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i;
const MAX_NUMBERED_VALUES = 20;

export function createApp(database, env = process.env) {
  if (!database?.lookup || !database?.status) {
    throw new TypeError('A ready local geolocation database is required');
  }

  const app = express();
  app.disable('x-powered-by');

  app.get('/health', (req, res) => {
    if (!isAuthorized(req, env)) {
      return res.status(401).json({ error: 'unauthorized' });
    }
    return res.json({ ok: true, uptime: process.uptime(), database: database.status() });
  });

  app.get('/', (req, res) => {
    if (!isAuthorized(req, env)) {
      return res.status(401).json({ error: 'unauthorized' });
    }

    const ip = String(req.query.ip || '').trim();
    if (!ip) return res.status(400).json({ error: 'missing_ip_parameter' });
    if (!isValidIp(ip)) return res.status(400).json({ error: 'invalid_ip_parameter' });
    if (isPrivateIp(ip)) return res.status(200).json(buildLocalResponse(ip));

    const result = database.lookup(ip);
    if (!result) {
      return res.status(502).json({
        ip,
        countryCode: '',
        countryName: '',
        asn: '',
        asName: '',
        isVpn: false,
        isHosting: false,
        isProxy: false,
        isMobile: false,
        networkSignalsAvailable: false,
        error: 'local_database_miss',
      });
    }

    return res.status(200).json(result);
  });

  return app;
}

export async function startServer(env = process.env) {
  const database = await openGeoDatabase(env);
  const app = createApp(database, env);
  const port = getNumber(env.PORT, 8787, 1, 65535);
  const host = String(env.HOST || '127.0.0.1').trim() || '127.0.0.1';
  const server = app.listen(port, host, () => {
    log(`listening on ${host}:${port} with local MMDB release ${database.status().release}`);
  });

  const shutdown = (signal) => {
    log(`${signal} received, shutting down`);
    server.close((error) => {
      process.exitCode = error ? 1 : 0;
    });
  };
  process.once('SIGTERM', () => shutdown('SIGTERM'));
  process.once('SIGINT', () => shutdown('SIGINT'));
  return server;
}

function isAuthorized(req, env) {
  const tokens = collectValues(env, ['GATEWAY_TOKENS', 'GATEWAY_TOKEN'], 'GATEWAY_TOKEN', []);
  if (!tokens.length) return true;
  const auth = req.headers.authorization || '';
  const bearer = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : '';
  const headerToken = req.headers['x-georestrict-token'] || '';
  const queryToken = req.query.token || '';
  return [bearer, headerToken, queryToken].some(candidate => tokens.includes(candidate));
}

function collectValues(env, multiNames, numberedPrefixes, defaults) {
  const values = [];
  for (const name of multiNames) values.push(...parseList(env[name]));
  const prefixes = Array.isArray(numberedPrefixes) ? numberedPrefixes : [numberedPrefixes];
  for (const prefix of prefixes) {
    for (let i = 1; i <= MAX_NUMBERED_VALUES; i++) {
      values.push(...parseList(env[`${prefix}_${i}`]));
    }
  }
  const cleaned = [...new Set(values.map(value => value.trim()).filter(Boolean))];
  return cleaned.length ? cleaned : defaults;
}

function parseList(value) {
  if (!value) return [];
  const text = String(value).trim();
  if (!text) return [];
  if (text.startsWith('[')) {
    try {
      const parsed = JSON.parse(text);
      return Array.isArray(parsed) ? parsed.map(String) : [];
    } catch {
      return [];
    }
  }
  return text.split(/[\n,;]+/).map(item => item.trim()).filter(Boolean);
}

function getNumber(value, fallback, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, Math.floor(number)));
}

function isValidIp(ip) {
  return typeof ip === 'string' && ip.length > 0 && ip.length <= 45
    && (IPV4_PATTERN.test(ip) || IPV6_PATTERN.test(ip));
}

function isPrivateIp(ip) {
  if (IPV4_PATTERN.test(ip)) {
    const [a, b, c] = ip.split('.').map(Number);
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 169 && b === 254) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a === 192 && b === 0 && (c === 0 || c === 2)) return true;
    if (a === 198 && b === 18 && (c === 0 || c === 1)) return true;
    if (a === 198 && b === 51 && c === 100) return true;
    if (a === 203 && b === 0 && c === 113) return true;
    return a >= 224;
  }
  const lower = ip.toLowerCase();
  if (lower === '::' || lower === '::1') return true;
  if (lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) return true;
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true;
  if (lower.startsWith('2001:db8')) return true;
  const mapped = IPV4_MAPPED_PATTERN.exec(ip);
  return Boolean(mapped && isPrivateIp(mapped[1]));
}

function buildLocalResponse(ip) {
  return {
    ip,
    countryCode: 'LOCAL',
    countryName: 'Local Network',
    asn: '',
    asName: '',
    isVpn: false,
    isHosting: false,
    isProxy: false,
    isMobile: false,
    networkSignalsAvailable: false,
    provider: 'local',
  };
}

function log(message) {
  console.log(`[georestrict-vps] ${new Date().toISOString()} ${message}`);
}

const isMain = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  startServer().catch((error) => {
    console.error(`[georestrict-vps] startup failed: ${error.message}`);
    process.exitCode = 1;
  });
}

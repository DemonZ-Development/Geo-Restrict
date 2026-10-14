import { isValidIp, isPrivateIp } from './ip-address.js';

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import express from 'express';

import { openGeoDatabase } from './geo-database.js';

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
  const tokens = getGatewayTokens(env);
  if (!tokens.length) return true;
  const auth = req.headers.authorization || '';
  const bearer = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : '';
  const headerToken = req.headers['x-georestrict-token'] || '';
  const queryToken = req.query.token || '';
  return [bearer, headerToken, queryToken].some(candidate => tokens.includes(candidate));
}

export function getGatewayTokens(env = process.env) {
  return collectValues(env, ['GATEWAY_TOKENS', 'GATEWAY_TOKEN'], 'GATEWAY_TOKEN', []);
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

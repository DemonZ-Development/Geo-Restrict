/* GeoRestrict Worker gateway. Route: GET /?ip=<address>. */

const IPV4_PATTERN = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_PATTERN = /^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1?$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,6}(?::[0-9a-fA-F]{1,4}){1,6}$/;
const IPV4_MAPPED_PATTERN = /^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i;
const IP_API_FIELDS = 'status,message,query,country,countryCode,isp,org,as,proxy,hosting,mobile';
const DEFAULT_PROVIDER_ORDER = ['ipinfo', 'ip-api'];
const MAX_NUMBERED_VALUES = 20;
const CACHE_SCHEMA_VERSION = 'v2';
const memoryCache = new Map();

export default {
  async fetch(request, env, ctx) {
    const headers = buildHeaders(request, env);
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers });
    }
    if (request.method !== 'GET') {
      return json({ error: 'method_not_allowed' }, 405, headers);
    }

    const url = new URL(request.url);
    if (url.pathname === '/health') {
      const cacheTtlSeconds = getNumber(env.CACHE_TTL_SECONDS, 86400, 0, 604800);
      return json({
        ok: true,
        version: env.GATEWAY_VERSION || '2.0.0',
        vpsConfigured: Boolean(String(env.VPS_GATEWAY_URL || '').trim()),
        providers: getProviderOrder(env),
        fallback: 'vps-local-mmdb',
        cache: {
          ttlSeconds: cacheTtlSeconds,
          cacheApi: true,
          memory: getNumber(env.MEMORY_CACHE_MAX_ENTRIES, 5000, 0, 50000) > 0,
          kv: Boolean(env.GEO_CACHE),
        },
      }, 200, headers);
    }

    if (!isAuthorized(request, env)) {
      return json({ error: 'unauthorized' }, 401, headers);
    }

    const ip = (url.searchParams.get('ip') || '').trim();
    if (!ip) return json({ error: 'missing_ip_parameter' }, 400, headers);
    if (!isValidIp(ip)) return json({ error: 'invalid_ip_parameter' }, 400, headers);
    if (isPrivateIp(ip)) {
      return json(buildLocalResponse(ip), 200, headers);
    }

    const cacheTtlSeconds = getNumber(env.CACHE_TTL_SECONDS, 86400, 0, 604800);
    const memoryMaxEntries = getNumber(env.MEMORY_CACHE_MAX_ENTRIES, 5000, 0, 50000);
    const cacheKey = new Request(`https://georestrict-cache.local/${CACHE_SCHEMA_VERSION}?ip=${encodeURIComponent(ip)}`);
    const kvKey = `geo:${CACHE_SCHEMA_VERSION}:${ip}`;
    const cached = await readLookupCaches(cacheKey, kvKey, env, cacheTtlSeconds, memoryMaxEntries);
    if (cached) return json(cached, 200, headers);

    const providersTried = [];
    let result = null;

    for (const provider of getProviderOrder(env)) {
      providersTried.push(provider);
      if (provider === 'ipinfo') result = await fetchIpInfo(ip, env);
      else if (provider === 'ip-api') result = await fetchIpApi(ip, env);
      if (result) break;
    }

    const vpsUrl = String(env.VPS_GATEWAY_URL || '').trim();
    if (!result && vpsUrl) {
      providersTried.push('vps-local');
      result = await fetchFromVps(ip, vpsUrl, env);
    }

    if (!result) {
      return json({
        ip,
        countryCode: '',
        countryName: '',
        asn: '',
        asName: '',
        isVpn: false, isHosting: false, isProxy: false, isMobile: false,
        error: 'all_providers_failed',
        providersTried,
      }, 502, headers);
    }

    const response = json(result, 200, headers);
    if (cacheTtlSeconds > 0) {
      writeMemoryCache(kvKey, result, cacheTtlSeconds, memoryMaxEntries);
      const cacheWrite = writeLookupCaches(cacheKey, kvKey, result, env, cacheTtlSeconds);
      if (ctx?.waitUntil) ctx.waitUntil(cacheWrite);
      else await cacheWrite;
    }
    return response;
  },
};

async function fetchIpApi(ip, env) {
  const endpoints = collectValues(env, ['IP_API_ENDPOINTS'], 'IP_API_ENDPOINT',
    []);
  const keys = collectValues(env, ['IP_API_KEYS', 'IP_API_KEY', 'PROVIDER_KEYS', 'PROVIDER_KEY'],
    ['IP_API_KEY', 'PROVIDER_KEY'], []);
  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  if (!endpoint) return null;
  const key = keys.length ? keys[Math.floor(Math.random() * keys.length)] : '';
  if (/^https:\/\/pro\.ip-api\.com(?:\/|$)/i.test(endpoint) && !key) return null;
  const url = buildIpApiUrl(endpoint, ip, key);
  try {
    const res = await fetchWithTimeout(url, env);
    if (!res.ok) return null;
    const data = await res.json();
    if (!data || data.status !== 'success' || !data.countryCode) return null;
    const asnMatch = String(data.as || '').match(/^(AS\d+)\s*(.*)$/i);
    return {
      ip,
      countryCode: String(data.countryCode || '').toUpperCase(),
      countryName: data.country || '',
      asn: asnMatch ? asnMatch[1].toUpperCase() : '',
      asName: asnMatch ? asnMatch[2] : (data.isp || data.org || ''),
      isVpn: Boolean(data.proxy),
      isHosting: Boolean(data.hosting),
      isProxy: Boolean(data.proxy),
      isMobile: Boolean(data.mobile),
      provider: 'ip-api',
    };
  } catch {
    return null;
  }
}

async function fetchIpInfo(ip, env) {
  const tokens = collectValues(env,
    ['IPINFO_TOKENS', 'IPINFO_TOKEN', 'PROVIDER_TOKENS', 'PROVIDER_TOKEN'],
    ['IPINFO_TOKEN', 'PROVIDER_TOKEN'], []);
  const endpoints = collectValues(env, ['IPINFO_ENDPOINTS'], 'IPINFO_ENDPOINT',
    []);
  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  if (!endpoint) return null;
  const token = tokens.length ? tokens[Math.floor(Math.random() * tokens.length)] : '';
  const url = addQueryParam(buildProviderUrl(endpoint, ip), 'token', token);
  try {
    const res = await fetchWithTimeout(url, env);
    if (!res.ok) return null;
    const data = await res.json();
    const legacyCountry = String(data?.country || '');
    const countryCode = String(data?.country_code || (legacyCountry.length === 2 ? legacyCountry : '')).toUpperCase();
    if (!data || data.bogon || data.error || !countryCode) return null;
    const asnText = data.org || [data.asn, data.as_name].filter(Boolean).join(' ') || '';
    const asn = parseAsn(asnText);
    return {
      ip: data.ip || ip,
      countryCode,
      countryName: data.country_name || legacyCountry || countryCode,
      asn: asn.code,
      asName: asn.name,
      isVpn: false, isHosting: false, isProxy: false, isMobile: false,
      provider: 'ipinfo',
    };
  } catch {
    return null;
  }
}

function buildIpApiUrl(endpoint, ip, key) {
  let url = buildProviderUrl(endpoint, ip);
  url = addQueryParam(url, 'fields', IP_API_FIELDS, true);
  if (key) url = addQueryParam(url, 'key', key, true);
  return url;
}

async function fetchFromVps(ip, vpsBaseUrl, env) {
  const timeoutMs = getNumber(env.VPS_GATEWAY_TIMEOUT_MS, 2500, 250, 10000);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const url = addQueryParam(vpsBaseUrl, 'ip', ip);
    const headers = { 'Accept': 'application/json' };
    const token = env.VPS_GATEWAY_TOKEN || '';
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
      headers['X-GeoRestrict-Token'] = token;
    }
    const res = await fetch(url, {
      signal: controller.signal,
      headers,
    });
    if (!res.ok) return null;
    const data = await res.json();
    if (!data || data.error || !data.countryCode) return null;
    return {
      ip: data.ip || ip,
      countryCode: String(data.countryCode || '').toUpperCase(),
      countryName: data.countryName || '',
      asn: data.asn || '',
      asName: data.asName || '',
      isVpn: Boolean(data.isVpn),
      isHosting: Boolean(data.isHosting),
      isProxy: Boolean(data.isProxy),
      isMobile: Boolean(data.isMobile),
      provider: data.provider || 'vps',
    };
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

function buildProviderUrl(endpoint, ip) {
  const encoded = encodeURIComponent(ip);
  if (endpoint.includes('{ip}')) return endpoint.replaceAll('{ip}', encoded);
  const sep = endpoint.endsWith('/') ? '' : '/';
  return `${endpoint}${sep}${encoded}`;
}

function addQueryParam(urlString, key, value, skipIfExists = false) {
  const url = new URL(urlString);
  if (value === '' || value == null) return url.toString();
  if (skipIfExists && url.searchParams.has(key)) return url.toString();
  url.searchParams.set(key, value);
  return url.toString();
}

async function fetchWithTimeout(url, env) {
  const timeoutMs = getNumber(env.REQUEST_TIMEOUT_MS, 3000, 250, 30000);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, {
      signal: controller.signal,
      headers: { 'User-Agent': 'GeoRestrict-Gateway/' + (env.GATEWAY_VERSION || '2.0.0') },
    });
  } finally {
    clearTimeout(timer);
  }
}

async function readLookupCaches(cacheKey, kvKey, env, ttlSeconds, memoryMaxEntries) {
  if (ttlSeconds <= 0) return null;

  const inMemory = readMemoryCache(kvKey, memoryMaxEntries);
  if (inMemory) return inMemory;

  try {
    const response = await caches.default.match(cacheKey);
    if (response?.ok) {
      const payload = await response.json();
      if (isLookupResult(payload)) {
        writeMemoryCache(kvKey, payload, ttlSeconds, memoryMaxEntries);
        return payload;
      }
    }
  } catch {}

  if (env.GEO_CACHE?.get) {
    try {
      const payload = await env.GEO_CACHE.get(kvKey, 'json');
      if (isLookupResult(payload)) {
        writeMemoryCache(kvKey, payload, ttlSeconds, memoryMaxEntries);
        return payload;
      }
    } catch {}
  }

  return null;
}

async function writeLookupCaches(cacheKey, kvKey, payload, env, ttlSeconds) {
  const writes = [];

  try {
    const response = json(payload, 200, new Headers({
      'Content-Type': 'application/json',
      'Cache-Control': `public, max-age=${ttlSeconds}`,
    }));
    writes.push(caches.default.put(cacheKey, response));
  } catch {}

  if (env.GEO_CACHE?.put) {
    try {
      writes.push(env.GEO_CACHE.put(kvKey, JSON.stringify(payload), { expirationTtl: ttlSeconds }));
    } catch {}
  }

  await Promise.allSettled(writes);
}

function readMemoryCache(key, maxEntries) {
  if (maxEntries <= 0) return null;
  const entry = memoryCache.get(key);
  if (!entry) return null;
  if (Date.now() >= entry.expiresAt) {
    memoryCache.delete(key);
    return null;
  }
  memoryCache.delete(key);
  memoryCache.set(key, entry);
  return entry.payload;
}

function writeMemoryCache(key, payload, ttlSeconds, maxEntries) {
  if (ttlSeconds <= 0 || maxEntries <= 0 || !isLookupResult(payload)) return;
  memoryCache.delete(key);
  memoryCache.set(key, { payload, expiresAt: Date.now() + ttlSeconds * 1000 });
  while (memoryCache.size > maxEntries) {
    memoryCache.delete(memoryCache.keys().next().value);
  }
}

function isLookupResult(payload) {
  return Boolean(payload && typeof payload === 'object' && String(payload.countryCode || '').trim());
}

function json(payload, status, headers) {
  return new Response(JSON.stringify(payload), { status, headers });
}

function buildHeaders(request, env) {
  const origin = request.headers.get('Origin');
  const allowed = collectValues(env, ['ALLOWED_ORIGINS'], 'ALLOWED_ORIGIN', ['*']);
  const allowOrigin = allowed.includes('*') || !origin
    ? '*' : allowed.includes(origin) ? origin : allowed[0];
  return new Headers({
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': allowOrigin,
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-GeoRestrict-Token',
    'Cache-Control': 'private, no-store',
  });
}

function isAuthorized(request, env) {
  const tokens = collectValues(env, ['GATEWAY_TOKENS', 'GATEWAY_TOKEN'], 'GATEWAY_TOKEN', []);
  if (!tokens.length) return true;
  const url = new URL(request.url);
  const auth = request.headers.get('Authorization') || '';
  const bearer = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : '';
  const headerToken = request.headers.get('X-GeoRestrict-Token') || '';
  const queryToken = url.searchParams.get('token') || '';
  return [bearer, headerToken, queryToken].some(c => tokens.includes(c));
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
  const cleaned = [...new Set(values.map(v => v.trim()).filter(Boolean))];
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
    } catch { return []; }
  }
  return text.split(/[\n,;]+/).map(s => s.trim()).filter(Boolean);
}

function getProviderOrder(env) {
  const configured = parseList(env.PROVIDER_ORDER).map(v => v.toLowerCase());
  const allowed = configured.filter(v => v === 'ip-api' || v === 'ipinfo');
  return allowed.length ? allowed : DEFAULT_PROVIDER_ORDER;
}

function getNumber(value, fallback, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, Math.floor(n)));
}

function isValidIp(ip) {
  if (typeof ip !== 'string' || ip.length === 0 || ip.length > 45) return false;
  if (IPV4_PATTERN.test(ip)) return true;
  if (IPV6_PATTERN.test(ip)) return true;
  return false;
}

function isPrivateIp(ip) {
  if (IPV4_PATTERN.test(ip)) {
    const parts = ip.split('.').map(Number);
    const [a, b, c] = parts;
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 169 && b === 254) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a === 192 && b === 0 && c === 0) return true;
    if (a === 192 && b === 0 && c === 2) return true;
    if (a === 198 && b === 18 && (c === 0 || c === 1)) return true;
    if (a === 198 && b === 51 && c === 100) return true;
    if (a === 203 && b === 0 && c === 113) return true;
    if (a >= 224) return true;
    return false;
  }
  const lower = ip.toLowerCase();
  if (lower === '::' || lower === '::1') return true;
  if (lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) return true;
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true;
  if (lower.startsWith('2001:db8')) return true;
  const mapped = IPV4_MAPPED_PATTERN.exec(ip);
  if (mapped) return isPrivateIp(mapped[1]);
  return false;
}

function buildLocalResponse(ip) {
  return {
    ip, countryCode: 'LOCAL', countryName: 'Local Network',
    asn: '', asName: '',
    isVpn: false, isHosting: false, isProxy: false, isMobile: false,
    provider: 'local',
  };
}

function parseAsn(input) {
  const text = String(input || '').trim();
  const match = text.match(/^(AS\d+)\s+(.+)$/i);
  if (match) return { code: match[1].toUpperCase(), name: match[2] };
  const fallback = text.match(/^(AS\d+)$/i);
  if (fallback) return { code: fallback[1].toUpperCase(), name: '' };
  return { code: '', name: text };
}

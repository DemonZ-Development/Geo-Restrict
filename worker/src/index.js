/*
 * GeoRestrict gateway
 *
 * Route: GET /?ip=<address>
 *
 * Environment variables and secrets:
 *   GATEWAY_TOKENS      Optional comma/newline/JSON-list of shared auth tokens.
 *                       When ANY tokens are defined, requests MUST authenticate.
 *   IP_API_ENDPOINTS    ip-api endpoint pool. Supports {ip} templates.
 *   IP_API_KEYS         Optional ip-api Pro key pool.
 *   IPINFO_TOKENS       ipinfo token pool. Used as primary when present,
 *                       otherwise ipinfo is tried as a no-token fallback.
 *   IPINFO_ENDPOINTS    Optional ipinfo endpoint templates.
 *   PROVIDER_ORDER      Comma list, e.g. "ipinfo,ip-api". Defaults to "ipinfo,ip-api".
 *   REQUEST_TIMEOUT_MS  Defaults to 3000.
 *   CACHE_TTL_SECONDS   Defaults to 86400 (1 day). 0 disables caching.
 *   ALLOWED_ORIGINS     Defaults to "*".
 */

const IPV4_PATTERN = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_PATTERN = /^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1?$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,6}(?::[0-9a-fA-F]{1,4}){1,6}$/;
const IPV4_MAPPED_PATTERN = /^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i;
const IP_API_FIELDS = 'status,message,query,country,countryCode,isp,org,as,proxy,hosting,mobile';
const DEFAULT_IPINFO_ENDPOINT = 'https://ipinfo.io/{ip}/json';
const DEFAULT_IP_API_ENDPOINT = 'https://ip-api.com/json/{ip}';
const DEFAULT_PROVIDER_ORDER = ['ipinfo', 'ip-api'];
const MAX_NUMBERED_VALUES = 20;

export default {
  async fetch(request, env, ctx) {
    const headers = buildHeaders(request, env);
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers });
    }
    if (request.method !== 'GET') {
      return json({ error: 'method_not_allowed' }, 405, headers);
    }
    if (!isAuthorized(request, env)) {
      return json({ error: 'unauthorized' }, 401, headers);
    }

    const url = new URL(request.url);
    const ip = (url.searchParams.get('ip') || '').trim();
    if (!ip) return json({ error: 'missing_ip_parameter' }, 400, headers);
    if (!isValidIp(ip)) return json({ error: 'invalid_ip_parameter' }, 400, headers);
    if (isPrivateIp(ip)) {
      return json(buildLocalResponse(ip), 200, headers);
    }

    const cacheTtlSeconds = getNumber(env.CACHE_TTL_SECONDS, 86400, 0, 604800);
    const cacheKey = new Request(`https://georestrict-cache.local/?ip=${encodeURIComponent(ip)}`);
    const cached = await readCache(cacheKey);
    if (cached) return withHeaders(cached, headers);

    const providersTried = [];
    let result = null;
    for (const provider of getProviderOrder(env)) {
      providersTried.push(provider);
      if (provider === 'ipinfo') result = await fetchIpInfo(ip, env);
      else if (provider === 'ip-api') result = await fetchIpApi(ip, env);
      if (result) break;
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
      ctx.waitUntil(writeCache(cacheKey, response.clone(), cacheTtlSeconds));
    }
    return response;
  },
};

async function fetchIpApi(ip, env) {
  const endpoints = collectValues(env, ['IP_API_ENDPOINTS'], 'IP_API_ENDPOINT',
    [DEFAULT_IP_API_ENDPOINT]);
  const keys = collectValues(env, ['IP_API_KEYS'], 'IP_API_KEY', []);
  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  const key = keys.length ? keys[Math.floor(Math.random() * keys.length)] : '';
  const url = buildIpApiUrl(endpoint, ip, key);
  try {
    const res = await fetchWithTimeout(url, env);
    if (!res.ok) return null;
    const data = await res.json();
    if (data.status && data.status !== 'success') return null;
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
  const tokens = collectValues(env, ['IPINFO_TOKENS', 'IPINFO_TOKEN'], 'IPINFO_TOKEN', []);
  const endpoints = collectValues(env, ['IPINFO_ENDPOINTS'], 'IPINFO_ENDPOINT',
    [DEFAULT_IPINFO_ENDPOINT]);
  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  // No-token calls hit ipinfo's free tier; if tokens are present, use one
  // for the higher quota. Both paths are supported.
  const token = tokens.length ? tokens[Math.floor(Math.random() * tokens.length)] : '';
  const url = addQueryParam(buildProviderUrl(endpoint, ip), 'token', token);
  try {
    const res = await fetchWithTimeout(url, env);
    if (!res.ok) return null;
    const data = await res.json();
    if (!data || data.bogon) return null;
    const asn = parseAsn(data.org || data.asn || '');
    return {
      ip: data.ip || ip,
      countryCode: String(data.country || '').toUpperCase(),
      countryName: data.country_name || data.country || '',
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
      headers: { 'User-Agent': 'GeoRestrict-Gateway/' + (env.GATEWAY_VERSION || '2.1.0') },
    });
  } finally {
    clearTimeout(timer);
  }
}

async function readCache(cacheKey) {
  try { return await caches.default.match(cacheKey); } catch { return null; }
}

async function writeCache(cacheKey, response, ttlSeconds) {
  try {
    const cloned = new Response(response.body, response);
    cloned.headers.set('Cache-Control', `public, max-age=${ttlSeconds}`);
    await caches.default.put(cacheKey, cloned);
  } catch {}
}

function withHeaders(response, headers) {
  const next = new Response(response.body, response);
  for (const [k, v] of headers.entries()) next.headers.set(k, v);
  return next;
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
    'Cache-Control': `public, max-age=${getNumber(env.CACHE_TTL_SECONDS, 86400, 0, 604800)}`,
  });
}

function isAuthorized(request, env) {
  const tokens = collectValues(env, ['GATEWAY_TOKENS', 'GATEWAY_TOKEN'], 'GATEWAY_TOKEN', []);
  // Fail-closed: if ANY tokens are defined, requests MUST authenticate.
  if (!tokens.length) return true;
  const url = new URL(request.url);
  const auth = request.headers.get('Authorization') || '';
  const bearer = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : '';
  const headerToken = request.headers.get('X-GeoRestrict-Token') || '';
  const queryToken = url.searchParams.get('token') || '';
  return [bearer, headerToken, queryToken].some(c => tokens.includes(c));
}

function collectValues(env, multiNames, numberedPrefix, defaults) {
  const values = [];
  for (const name of multiNames) values.push(...parseList(env[name]));
  for (let i = 1; i <= MAX_NUMBERED_VALUES; i++) {
    values.push(...parseList(env[`${numberedPrefix}_${i}`]));
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

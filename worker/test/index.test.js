import assert from 'node:assert/strict';
import test from 'node:test';

import worker from '../src/index.js';

async function runWorker(env, fetchImpl, url = 'https://worker.test/?ip=8.8.8.8', options = {}) {
  const previousFetch = globalThis.fetch;
  const previousCaches = globalThis.caches;
  const pending = [];
  globalThis.fetch = fetchImpl;
  globalThis.caches = options.caches || { default: { match: async () => null, put: async () => {} } };

  try {
    const response = await worker.fetch(
      new Request(url),
      { CACHE_TTL_SECONDS: '0', ...env },
      { waitUntil(promise) { pending.push(Promise.resolve(promise)); } },
    );
    await Promise.allSettled(pending);
    return response;
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.caches = previousCaches;
  }
}

test('health endpoint is public and does not perform an upstream lookup', async () => {
  let fetched = false;
  const response = await runWorker(
    { GATEWAY_TOKENS: 'private-token', VPS_GATEWAY_URL: 'https://vps.example.test' },
    async () => { fetched = true; return Response.json({}); },
    'https://worker.test/health',
  );

  assert.equal(response.status, 200);
  assert.equal(fetched, false);
  assert.deepEqual(await response.json(), {
    ok: true,
    version: '2.0.0',
    vpsConfigured: true,
    providers: ['ipinfo', 'ip-api'],
    fallback: 'vps-local-mmdb',
    cache: {
      ttlSeconds: 0,
      cacheApi: true,
      memory: true,
      kv: false,
    },
  });
});

test('IPinfo Lite response fields normalize to the gateway contract', async () => {
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ipinfo',
      IPINFO_ENDPOINTS: 'https://provider.example.test/lite/{ip}',
      IPINFO_TOKEN: 'lite-token',
    },
    async () => Response.json({
      ip: '8.8.8.8',
      country_code: 'US',
      country: 'United States',
      asn: 'AS15169',
      as_name: 'Google LLC',
    }),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ip: '8.8.8.8',
    countryCode: 'US',
    countryName: 'United States',
    asn: 'AS15169',
    asName: 'Google LLC',
    isVpn: false,
    isHosting: false,
    isProxy: false,
    isMobile: false,
    provider: 'ipinfo',
  });
});

test('keyless IP-API Pro is skipped before the IPinfo fallback', async () => {
  let fetchCount = 0;
  let requestedHost;
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ip-api,ipinfo',
      IP_API_ENDPOINTS: 'https://pro.ip-api.com/json/{ip}',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
    },
    async (url) => {
      fetchCount++;
      requestedHost = new URL(url).hostname;
      return Response.json({ ip: '8.8.8.8', country: 'US', org: 'AS15169 Google LLC' });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(fetchCount, 1);
  assert.equal(requestedHost, 'provider.example.test');
});

test('singular IP_API_KEY enables the HTTPS Pro provider', async () => {
  let requestedUrl;
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ip-api,ipinfo',
      IP_API_ENDPOINTS: 'https://pro.ip-api.com/json/{ip}',
      IP_API_KEY: 'pro-key',
    },
    async (url) => {
      requestedUrl = new URL(url);
      return Response.json({
        status: 'success',
        country: 'United States',
        countryCode: 'US',
        as: 'AS15169 Google LLC',
        proxy: true,
        hosting: true,
        mobile: false,
      });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(requestedUrl.hostname, 'pro.ip-api.com');
  assert.equal(requestedUrl.searchParams.get('key'), 'pro-key');
  assert.equal((await response.json()).isProxy, true);
});

test('VPS local lookup runs only after the external providers fail', async () => {
  let requestedUrl;
  let requestedHeaders;
  const hosts = [];
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ipinfo',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
      VPS_GATEWAY_URL: 'https://vps.example.test/gateway?source=worker',
      VPS_GATEWAY_TOKEN: 'shared-secret',
    },
    async (url, options) => {
      requestedUrl = new URL(url);
      hosts.push(requestedUrl.hostname);
      if (requestedUrl.hostname === 'provider.example.test') {
        return new Response(null, { status: 503 });
      }
      requestedHeaders = options.headers;
      return Response.json({
        ip: '8.8.8.8',
        countryCode: 'US',
        countryName: 'United States',
        asn: 'AS15169',
        asName: 'Google LLC',
        provider: 'dbip-local',
      });
    },
  );

  assert.equal(response.status, 200);
  assert.deepEqual(hosts, ['provider.example.test', 'vps.example.test']);
  assert.equal(requestedUrl.pathname, '/gateway');
  assert.equal(requestedUrl.searchParams.get('source'), 'worker');
  assert.equal(requestedUrl.searchParams.get('ip'), '8.8.8.8');
  assert.equal(requestedHeaders.Authorization, 'Bearer shared-secret');
  assert.equal(requestedHeaders['X-GeoRestrict-Token'], 'shared-secret');
  assert.equal((await response.json()).provider, 'dbip-local');
});

test('a successful external provider prevents a VPS request', async () => {
  const hosts = [];
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ipinfo',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
      VPS_GATEWAY_URL: 'https://vps.example.test',
    },
    async (url) => {
      hosts.push(new URL(url).hostname);
      return Response.json({ ip: '8.8.8.8', country: 'US', org: 'AS15169 Google LLC' });
    },
  );

  assert.equal(response.status, 200);
  assert.deepEqual(hosts, ['provider.example.test']);
});

test('generic provider token aliases are accepted', async () => {
  let requestedUrl;
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ipinfo',
      PROVIDER_TOKENS: 'provider-secret',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
    },
    async (url) => {
      requestedUrl = new URL(url);
      return Response.json({ ip: '8.8.8.8', country: 'US', org: 'AS15169 Google LLC' });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(requestedUrl.pathname, '/8.8.8.8');
  assert.equal(requestedUrl.searchParams.get('token'), 'provider-secret');
});

test('invalid VPS configuration falls back to a direct provider', async () => {
  let requestedUrl;
  const response = await runWorker(
    {
      VPS_GATEWAY_URL: 'not a url',
      PROVIDER_ORDER: 'ipinfo',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
    },
    async (url) => {
      requestedUrl = new URL(url);
      return Response.json({ ip: '8.8.8.8', country: 'US', org: 'AS15169 Google LLC' });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(requestedUrl.hostname, 'provider.example.test');
});

test('incomplete provider responses fail closed at the gateway', async () => {
  const response = await runWorker(
    {
      PROVIDER_ORDER: 'ipinfo',
      IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
    },
    async () => Response.json({ error: 'quota_exceeded' }),
  );

  assert.equal(response.status, 502);
  assert.equal((await response.json()).error, 'all_providers_failed');
});

test('provider names without configured endpoints fail closed without a hidden URL', async () => {
  let fetched = false;
  const response = await runWorker(
    { PROVIDER_ORDER: 'ipinfo,ip-api' },
    async () => { fetched = true; return Response.json({}); },
  );

  assert.equal(response.status, 502);
  assert.equal(fetched, false);
  assert.equal((await response.json()).error, 'all_providers_failed');
});

test('the Cache API stores successful lookups for 24 hours without public response caching', async () => {
  let storedResponse = null;
  let fetchCount = 0;
  const cache = {
    default: {
      match: async () => storedResponse?.clone() || null,
      put: async (_key, response) => { storedResponse = response.clone(); },
    },
  };
  const env = {
    CACHE_TTL_SECONDS: '86400',
    MEMORY_CACHE_MAX_ENTRIES: '0',
    PROVIDER_ORDER: 'ipinfo',
    IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
  };
  const url = 'https://worker.test/?ip=8.8.4.4';

  const first = await runWorker(env, async () => {
    fetchCount++;
    return Response.json({ ip: '8.8.4.4', country: 'US', org: 'AS15169 Google LLC' });
  }, url, { caches: cache });
  const second = await runWorker(env, async () => {
    fetchCount++;
    throw new Error('provider should not run on a cache hit');
  }, url, { caches: cache });

  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  assert.equal(fetchCount, 1);
  assert.equal(first.headers.get('Cache-Control'), 'private, no-store');
  assert.equal(storedResponse.headers.get('Cache-Control'), 'public, max-age=86400');
});

test('optional KV caching works when GEO_CACHE is bound', async () => {
  const values = new Map();
  let putOptions;
  let fetchCount = 0;
  const kv = {
    async get(key, type) {
      const value = values.get(key);
      return type === 'json' && value ? JSON.parse(value) : value || null;
    },
    async put(key, value, options) {
      values.set(key, value);
      putOptions = options;
    },
  };
  const unavailableCacheApi = {
    default: {
      match: async () => { throw new Error('cache unavailable'); },
      put: async () => { throw new Error('cache unavailable'); },
    },
  };
  const env = {
    CACHE_TTL_SECONDS: '86400',
    MEMORY_CACHE_MAX_ENTRIES: '0',
    GEO_CACHE: kv,
    PROVIDER_ORDER: 'ipinfo',
    IPINFO_ENDPOINTS: 'https://provider.example.test/{ip}',
  };
  const url = 'https://worker.test/?ip=9.9.9.9';

  await runWorker(env, async () => {
    fetchCount++;
    return Response.json({ ip: '9.9.9.9', country: 'US', org: 'AS19281 Quad9' });
  }, url, { caches: unavailableCacheApi });
  const cached = await runWorker(env, async () => {
    fetchCount++;
    throw new Error('provider should not run on a KV hit');
  }, url, { caches: unavailableCacheApi });

  assert.equal(cached.status, 200);
  assert.equal(fetchCount, 1);
  assert.equal(putOptions.expirationTtl, 86400);
});

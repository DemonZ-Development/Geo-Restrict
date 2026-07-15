import assert from 'node:assert/strict';
import test from 'node:test';

import { lookupWithReaders } from '../src/geo-database.js';
import { createApp } from '../src/server.js';

function fakeDatabase(result = null) {
  return {
    lookup(ip) {
      return typeof result === 'function' ? result(ip) : result;
    },
    status() {
      return {
        ready: true,
        source: 'DB-IP Lite',
        release: '2026-07',
        networkSignals: false,
      };
    },
  };
}

async function withServer(database, env, callback) {
  const app = createApp(database, env);
  const server = app.listen(0, '127.0.0.1');
  await new Promise((resolve, reject) => {
    server.once('listening', resolve);
    server.once('error', reject);
  });
  try {
    const { port } = server.address();
    return await callback(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
}

test('local Country and ASN records normalize to the gateway contract', () => {
  const country = {
    get: () => ({ country: { iso_code: 'US', names: { en: 'United States' } } }),
  };
  const asn = {
    get: () => ({ autonomous_system_number: 15169, autonomous_system_organization: 'Google LLC' }),
  };

  assert.deepEqual(lookupWithReaders(country, asn, '8.8.8.8'), {
    ip: '8.8.8.8',
    countryCode: 'US',
    countryName: 'United States',
    asn: 'AS15169',
    asName: 'Google LLC',
    isVpn: false,
    isHosting: false,
    isProxy: false,
    isMobile: false,
    networkSignalsAvailable: false,
    provider: 'dbip-local',
  });
});

test('missing Country MMDB records fail instead of inventing a country', () => {
  const country = { get: () => null };
  const asn = { get: () => ({ autonomous_system_number: 15169 }) };
  assert.equal(lookupWithReaders(country, asn, '8.8.8.8'), null);
});

test('health and lookup routes require the configured shared token', async () => {
  const result = {
    ip: '8.8.8.8', countryCode: 'US', countryName: 'United States',
    asn: 'AS15169', asName: 'Google LLC',
    isVpn: false, isHosting: false, isProxy: false, isMobile: false,
    networkSignalsAvailable: false, provider: 'dbip-local',
  };

  await withServer(fakeDatabase(result), { GATEWAY_TOKENS: 'shared-secret' }, async baseUrl => {
    const unauthorized = await fetch(`${baseUrl}/?ip=8.8.8.8`);
    const health = await fetch(`${baseUrl}/health`, {
      headers: { Authorization: 'Bearer shared-secret' },
    });
    const lookup = await fetch(`${baseUrl}/?ip=8.8.8.8`, {
      headers: { 'X-GeoRestrict-Token': 'shared-secret' },
    });

    assert.equal(unauthorized.status, 401);
    assert.equal(health.status, 200);
    assert.equal((await health.json()).database.release, '2026-07');
    assert.equal(lookup.status, 200);
    assert.equal((await lookup.json()).provider, 'dbip-local');
  });
});

test('a local database miss returns a clear 502 response', async () => {
  await withServer(fakeDatabase(null), {}, async baseUrl => {
    const response = await fetch(`${baseUrl}/?ip=8.8.8.8`);
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error, 'local_database_miss');
  });
});

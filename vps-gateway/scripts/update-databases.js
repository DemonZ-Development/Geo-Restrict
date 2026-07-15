import { createWriteStream } from 'node:fs';
import { access, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { createGunzip } from 'node:zlib';

import maxmind from 'maxmind';

const BASE_URL = String(process.env.DBIP_BASE_URL || 'https://download.db-ip.com/free').replace(/\/$/, '');
const DATA_DIR = path.resolve(process.env.DB_DIR || 'data');
const COUNTRY_PATH = path.resolve(process.env.DB_COUNTRY_PATH || path.join(DATA_DIR, 'dbip-country-lite.mmdb'));
const ASN_PATH = path.resolve(process.env.DB_ASN_PATH || path.join(DATA_DIR, 'dbip-asn-lite.mmdb'));
const METADATA_PATH = path.resolve(process.env.DB_METADATA_PATH || path.join(DATA_DIR, 'dbip-release.json'));
const FORCE = process.argv.includes('--force');

await main();

async function main() {
  await mkdir(DATA_DIR, { recursive: true });
  const release = await findRelease();

  if (!FORCE && await currentReleaseIsHealthy(release)) {
    console.log(`[georestrict-db] ${release} is already installed and valid`);
    return;
  }

  const suffix = `${process.pid}-${Date.now()}.partial`;
  const countryTemp = `${COUNTRY_PATH}.${suffix}`;
  const asnTemp = `${ASN_PATH}.${suffix}`;

  try {
    await downloadAndUnzip(databaseUrl('country', release), countryTemp);
    await downloadAndUnzip(databaseUrl('asn', release), asnTemp);
    await validateDatabases(countryTemp, asnTemp);
    await replaceFile(countryTemp, COUNTRY_PATH);
    await replaceFile(asnTemp, ASN_PATH);
    await writeMetadata(release);
    console.log(`[georestrict-db] installed DB-IP Lite Country and ASN release ${release}`);
  } finally {
    await Promise.allSettled([
      rm(countryTemp, { force: true }),
      rm(asnTemp, { force: true }),
    ]);
  }
}

async function findRelease() {
  const configured = String(process.env.DBIP_RELEASE || '').trim();
  const candidates = configured ? [configured] : releaseCandidates(new Date(), 3);

  for (const release of candidates) {
    const [country, asn] = await Promise.all([
      remoteExists(databaseUrl('country', release)),
      remoteExists(databaseUrl('asn', release)),
    ]);
    if (country && asn) return release;
  }

  throw new Error(`no complete DB-IP Lite release found in: ${candidates.join(', ')}`);
}

function releaseCandidates(now, count) {
  const values = [];
  for (let offset = 0; offset < count; offset++) {
    const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - offset, 1));
    values.push(`${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`);
  }
  return values;
}

function databaseUrl(kind, release) {
  return `${BASE_URL}/dbip-${kind}-lite-${release}.mmdb.gz`;
}

async function remoteExists(url) {
  try {
    const response = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(15000) });
    return response.ok;
  } catch {
    return false;
  }
}

async function downloadAndUnzip(url, destination) {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'GeoRestrict-MMDB-Updater/2.0.0' },
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok || !response.body) {
    throw new Error(`download failed (${response.status}) for ${url}`);
  }
  await pipeline(
    Readable.fromWeb(response.body),
    createGunzip(),
    createWriteStream(destination, { flags: 'wx', mode: 0o644 }),
  );
}

async function validateDatabases(countryPath, asnPath) {
  const [country, asn] = await Promise.all([
    maxmind.open(countryPath),
    maxmind.open(asnPath),
  ]);
  const countryRecord = country.get('8.8.8.8');
  const asnRecord = asn.get('8.8.8.8');
  if (!countryRecord?.country?.iso_code) {
    throw new Error('downloaded Country MMDB failed its lookup check');
  }
  if (!asnRecord?.autonomous_system_number) {
    throw new Error('downloaded ASN MMDB failed its lookup check');
  }
}

async function currentReleaseIsHealthy(release) {
  try {
    await Promise.all([access(COUNTRY_PATH), access(ASN_PATH)]);
    const metadata = JSON.parse(await readFile(METADATA_PATH, 'utf8'));
    if (metadata.release !== release) return false;
    await validateDatabases(COUNTRY_PATH, ASN_PATH);
    return true;
  } catch {
    return false;
  }
}

async function replaceFile(source, destination) {
  const backup = `${destination}.previous`;
  await rm(backup, { force: true });
  try {
    await rename(destination, backup);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }

  try {
    await rename(source, destination);
    await rm(backup, { force: true });
  } catch (error) {
    try { await rename(backup, destination); } catch {}
    throw error;
  }
}

async function writeMetadata(release) {
  const metadata = {
    source: 'DB-IP Lite',
    release,
    updatedAt: new Date().toISOString(),
    license: 'CC BY 4.0',
    attribution: 'IP Geolocation by DB-IP.com',
  };
  const temp = `${METADATA_PATH}.${process.pid}.partial`;
  await writeFile(temp, `${JSON.stringify(metadata, null, 2)}\n`, { mode: 0o644 });
  await replaceFile(temp, METADATA_PATH);
}

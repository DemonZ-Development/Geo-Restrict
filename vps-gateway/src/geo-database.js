import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';

import maxmind from 'maxmind';

const COUNTRY_FILE = 'dbip-country-lite.mmdb';
const ASN_FILE = 'dbip-asn-lite.mmdb';
const METADATA_FILE = 'dbip-release.json';

export async function openGeoDatabase(env = process.env) {
  const dataDir = path.resolve(env.DB_DIR || 'data');
  const countryPath = path.resolve(env.DB_COUNTRY_PATH || path.join(dataDir, COUNTRY_FILE));
  const asnPath = path.resolve(env.DB_ASN_PATH || path.join(dataDir, ASN_FILE));
  const metadataPath = path.resolve(env.DB_METADATA_PATH || path.join(dataDir, METADATA_FILE));

  const [countryReader, asnReader, countryStat, asnStat, metadata] = await Promise.all([
    maxmind.open(countryPath),
    maxmind.open(asnPath),
    stat(countryPath),
    stat(asnPath),
    readMetadata(metadataPath),
  ]);

  return {
    lookup(ip) {
      return lookupWithReaders(countryReader, asnReader, ip);
    },
    status() {
      return {
        ready: true,
        source: metadata.source || 'DB-IP Lite',
        release: metadata.release || 'unknown',
        updatedAt: metadata.updatedAt || null,
        countryBytes: countryStat.size,
        asnBytes: asnStat.size,
        networkSignals: false,
      };
    },
  };
}

export function lookupWithReaders(countryReader, asnReader, ip) {
  const countryRecord = countryReader?.get(ip);
  const asnRecord = asnReader?.get(ip);
  const countryCode = String(countryRecord?.country?.iso_code || '').trim().toUpperCase();
  if (!countryCode) return null;

  const asnNumber = Number(asnRecord?.autonomous_system_number);
  const asn = Number.isInteger(asnNumber) && asnNumber > 0 ? `AS${asnNumber}` : '';
  const asName = String(asnRecord?.autonomous_system_organization || '').trim();

  return {
    ip,
    countryCode,
    countryName: String(countryRecord?.country?.names?.en || countryCode),
    asn,
    asName,
    isVpn: false,
    isHosting: false,
    isProxy: false,
    isMobile: false,
    networkSignalsAvailable: false,
    provider: 'dbip-local',
  };
}

async function readMetadata(metadataPath) {
  try {
    return JSON.parse(await readFile(metadataPath, 'utf8'));
  } catch {
    return {};
  }
}

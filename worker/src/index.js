/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Route:  GET /?ip=<address>
 *
 * Providers (in priority order):
 *   1. ip-api.com   (free tier, no key required)
 *   2. ipinfo.io    (lite, token-based)
 *
 * If every provider fails the response still conforms to the unified schema
 * but includes an `error` field.
 */


// ---------------------------------------------------------------------------
// Provider account pools — add more entries for higher throughput
// ---------------------------------------------------------------------------

const IP_API_ENDPOINTS = [
  'http://ip-api.com/json/',
];

const IPINFO_TOKENS = [
  'PLACEHOLDER_TOKEN',
];

// Atomic round-robin counters (module-level, reset per isolate lifetime)
let ipApiIndex = 0;
let ipInfoIndex = 0;

// ---------------------------------------------------------------------------
// Private / reserved IP detection
// ---------------------------------------------------------------------------

/**
 * Returns `true` when the supplied address belongs to a private, loopback,
 * or link-local range and should NOT be sent to an external API.
 *
 * Covers:
 *   IPv4 — 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8,
 *           169.254.0.0/16, 0.0.0.0/8
 *   IPv6 — ::1, fe80::/10, fc00::/7
 */
function isPrivateIp(ip) {
  if (!ip) return false;

  // --- IPv4 ---
  const v4Match = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4Match) {
    const [, a, b] = v4Match.map(Number);
    if (a === 10) return true;                       // 10.0.0.0/8
    if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
    if (a === 192 && b === 168) return true;           // 192.168.0.0/16
    if (a === 127) return true;                        // 127.0.0.0/8
    if (a === 169 && b === 254) return true;           // 169.254.0.0/16
    if (a === 0) return true;                          // 0.0.0.0/8
    return false;
  }

  // --- IPv6 ---
  const lower = ip.toLowerCase();
  if (lower === '::1') return true;                                   // loopback
  if (lower.startsWith('fe80')) return true;                          // link-local
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true;  // unique-local (fc00::/7)

  return false;
}

// ---------------------------------------------------------------------------
// Build a LOCAL response for private IPs
// ---------------------------------------------------------------------------

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
  };
}

// ---------------------------------------------------------------------------
// Provider 1 — ip-api.com
// ---------------------------------------------------------------------------

async function fetchIpApi(ip) {
  const endpoint = IP_API_ENDPOINTS[ipApiIndex % IP_API_ENDPOINTS.length];
  ipApiIndex++;

  const url = `${endpoint}${encodeURIComponent(ip)}?fields=status,countryCode,country,isp,org,as,proxy,hosting,mobile`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);

  try {
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timeout);

    if (!res.ok) return null;

    const data = await res.json();
    if (data.status !== 'success') return null;

    // Extract ASN number from the "as" field (e.g. "AS15169 Google LLC")
    const asnMatch = (data.as || '').match(/^(AS\d+)/);

    return {
      ip,
      countryCode: data.countryCode || '',
      countryName: data.country || '',
      asn: asnMatch ? asnMatch[1] : '',
      asName: data.isp || data.org || '',
      isVpn: Boolean(data.proxy),
      isHosting: Boolean(data.hosting),
      isProxy: Boolean(data.proxy),
      isMobile: Boolean(data.mobile),
    };
  } catch {
    clearTimeout(timeout);
    return null;
  }
}

// ---------------------------------------------------------------------------
// Provider 2 — ipinfo.io (lite)
// ---------------------------------------------------------------------------

async function fetchIpInfo(ip) {
  const token = IPINFO_TOKENS[ipInfoIndex % IPINFO_TOKENS.length];
  ipInfoIndex++;

  const url = `https://ipinfo.io/${encodeURIComponent(ip)}?token=${token}`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);

  try {
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timeout);

    if (!res.ok) return null;

    const data = await res.json();
    if (!data.country) return null;

    return {
      ip: data.ip || ip,
      countryCode: (data.country || '').toUpperCase(),
      countryName: data.country_name || data.country || '',
      asn: data.asn ? (data.asn.asn || '') : '',
      asName: data.asn ? (data.asn.name || '') : (data.org || ''),
      isVpn: false,       // ipinfo lite does not provide VPN flags
      isHosting: false,
      isProxy: false,
      isMobile: false,
    };
  } catch {
    clearTimeout(timeout);
    return null;
  }
}

// ---------------------------------------------------------------------------
// Standard CORS + cache headers
// ---------------------------------------------------------------------------

const COMMON_HEADERS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Cache-Control': 'public, max-age=86400',
};

// ---------------------------------------------------------------------------
// Worker entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request) {
    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: COMMON_HEADERS });
    }

    // Only GET is supported
    if (request.method !== 'GET') {
      return new Response(
        JSON.stringify({ error: 'method_not_allowed' }),
        { status: 405, headers: COMMON_HEADERS },
      );
    }

    const url = new URL(request.url);
    const ip = url.searchParams.get('ip');

    // Validate required parameter
    if (!ip) {
      return new Response(
        JSON.stringify({ error: 'missing_ip_parameter' }),
        { status: 400, headers: COMMON_HEADERS },
      );
    }

    // Fast-path for private / reserved addresses
    if (isPrivateIp(ip)) {
      return new Response(
        JSON.stringify(buildLocalResponse(ip)),
        { status: 200, headers: COMMON_HEADERS },
      );
    }

    // Try providers in order
    const result = (await fetchIpApi(ip)) || (await fetchIpInfo(ip));

    if (result) {
      return new Response(
        JSON.stringify(result),
        { status: 200, headers: COMMON_HEADERS },
      );
    }

    // All providers failed — return empty shell with error flag
    return new Response(
      JSON.stringify({
        ip,
        countryCode: '',
        countryName: '',
        asn: '',
        asName: '',
        isVpn: false,
        isHosting: false,
        isProxy: false,
        isMobile: false,
        error: 'all_providers_failed',
      }),
      { status: 502, headers: COMMON_HEADERS },
    );
  },
};

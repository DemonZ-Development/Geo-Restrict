const V4 = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;

function parseIp(ip) {
  if (typeof ip !== 'string' || !ip.length || ip.length > 45) return null;
  if (V4.test(ip)) return ip.split('.').map(Number);
  if (!ip.includes(':') || !/^[0-9a-fA-F:.]+$/.test(ip)) return null;
  let value = ip;
  if (value.includes('.')) {
    const index = value.lastIndexOf(':');
    const tail = value.slice(index + 1);
    if (!V4.test(tail)) return null;
    const [a, b, c, d] = tail.split('.').map(Number);
    value = value.slice(0, index + 1) + ((a << 8) | b).toString(16) + ':' + ((c << 8) | d).toString(16);
  }
  const halves = value.split('::');
  if (halves.length > 2) return null;
  const left = halves[0] ? halves[0].split(':') : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  if (![...left, ...right].every(part => /^[0-9a-fA-F]{1,4}$/.test(part))) return null;
  const missing = 8 - left.length - right.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  return [...left, ...Array(halves.length === 2 ? missing : 0).fill('0'), ...right]
    .flatMap(part => { const word = parseInt(part, 16); return [word >> 8, word & 255]; });
}

export function isValidIp(ip) {
  return parseIp(ip) !== null;
}

function privateV4([a, b, c]) {
  return a === 0 || a === 10 || a === 127 || a >= 224
    || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && b === 168) || (a === 169 && b === 254)
    || (a === 100 && b >= 64 && b <= 127)
    || (a === 192 && b === 0 && (c === 0 || c === 2))
    || (a === 198 && (b === 18 || b === 19))
    || (a === 198 && b === 51 && c === 100)
    || (a === 203 && b === 0 && c === 113);
}

export function isPrivateIp(ip) {
  const bytes = parseIp(ip);
  if (!bytes) return false;
  if (bytes.length === 4) return privateV4(bytes);
  if (bytes.slice(0, 10).every(byte => byte === 0) && bytes[10] === 255 && bytes[11] === 255) {
    return privateV4(bytes.slice(12));
  }
  const [a, b, c, d] = bytes;
  return (bytes.slice(0, 15).every(byte => byte === 0) && bytes[15] <= 1)
    || (a & 254) === 252 || a === 255
    || (a === 254 && (b & 192) === 128) || (a === 254 && (b & 192) === 192)
    || (a === 32 && b === 1 && ((c === 13 && d === 184) || (c === 0 && d === 0)));
}

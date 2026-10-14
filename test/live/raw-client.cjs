// Minimal offline admission client for releases newer than minecraft-data.
// Packet IDs come from each official server's protocol registration bytecode.
// This proves reaching PLAY; it does not simulate rendering or gameplay.
const net = require('node:net');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const options = JSON.parse(process.argv[2]);
const definitions = JSON.parse(fs.readFileSync(path.resolve(__dirname,
  '../../.smoke/mods/evidence/raw-protocols.json')))[options.version];
if (!definitions) throw Error('No verified wire definitions for ' + options.version);
const result = { username: options.username, joined: false, disconnect: null, error: null };
let buffer = Buffer.alloc(0), compression = -1, state = 'login', done = false;
const socket = net.connect(options.port, '127.0.0.1');
const timer = setTimeout(() => { result.error = 'connection timed out'; finish(); }, 20000);
function finish() {
  if (done) return;
  done = true; clearTimeout(timer);
  console.log(JSON.stringify(result)); socket.destroy();
}
function vi(value) {
  const bytes = []; value >>>= 0;
  do { let next = value & 127; value >>>= 7; bytes.push(next | (value ? 128 : 0)); } while (value);
  return Buffer.from(bytes);
}
function readVi(data, offset = 0) {
  let value = 0;
  for (let i = 0; i < 5; i++) {
    if (offset + i >= data.length) return null;
    const byte = data[offset + i]; value |= (byte & 127) << (i * 7);
    if (!(byte & 128)) return { value: value >>> 0, size: i + 1 };
  }
  throw Error('Invalid VarInt');
}
function str(value) { const bytes = Buffer.from(value); return Buffer.concat([vi(bytes.length), bytes]); }
function send(id, ...values) {
  let payload = Buffer.concat([vi(id), ...values]);
  if (compression >= 0) payload = payload.length >= compression
    ? Buffer.concat([vi(payload.length), zlib.deflateSync(payload)])
    : Buffer.concat([vi(0), payload]);
  socket.write(Buffer.concat([vi(payload.length), payload]));
}
socket.on('connect', () => {
  if (options.proxyIp) {
    const ipv6 = net.isIP(options.proxyIp) === 6;
    socket.write(`PROXY ${ipv6 ? 'TCP6' : 'TCP4'} ${options.proxyIp} ${ipv6 ? '::1' : '127.0.0.1'} 40000 ${options.port}\r\n`);
  }
  const uuid = crypto.createHash('md5').update('OfflinePlayer:' + options.username).digest();
  uuid[6] = (uuid[6] & 15) | 48; uuid[8] = (uuid[8] & 63) | 128;
  const host = options.fixtureIp ? 'georestrict-live.invalid|' + options.fixtureIp
    : options.forwardedIp ? 'localhost\0' + options.forwardedIp + '\0' + uuid.toString('hex') + '\0[]' : 'localhost';
  const port = Buffer.alloc(2); port.writeUInt16BE(options.port);
  send(0, vi(definitions.protocol), str(host), port, vi(2));
  send(0, str(options.username), uuid);
});
function packet(id, data) {
  if (state === 'login') {
    if (id === 0) { result.disconnect = data.toString(); return finish(); }
    if (id === 3) { compression = readVi(data).value; return; }
    if (id === 4) {
      const query = readVi(data);
      const channelLength = readVi(data, query.size);
      const channel = data.subarray(query.size + channelLength.size,
        query.size + channelLength.size + channelLength.value).toString();
      if (options.modernForwardSecret && channel === 'velocity:player_info') {
        const uuid = crypto.createHash('md5').update('OfflinePlayer:' + options.username).digest();
        uuid[6] = (uuid[6] & 15) | 48; uuid[8] = (uuid[8] & 63) | 128;
        const payload = Buffer.concat([vi(1), str(options.modernForwardIp), uuid,
          str(options.username), vi(0)]);
        const signature = crypto.createHmac('sha256', options.modernForwardSecret).update(payload).digest();
        send(2, vi(query.value), Buffer.from([1]), signature, payload);
      } else send(2, vi(query.value), Buffer.from([0]));
      return;
    }
    if (id === 5) { send(4, data, Buffer.from([0])); return; }
    if (id === 2) {
      send(3); state = 'configuration';
      // Vanilla brand and client information, including the modern particle setting.
      send(2, str('minecraft:brand'), str('vanilla'));
      send(0, str('en_us'), Buffer.from([2]), vi(0), Buffer.from([1, 127]), vi(1), Buffer.from([0, 1]), vi(0));
    }
  } else if (state === 'configuration') {
    const c = definitions.configuration;
    if (id === c.CLIENTBOUND_DISCONNECT) { result.disconnect = data.toString(); return finish(); }
    if (id === c.CLIENTBOUND_COOKIE_REQUEST) { send(1, data, Buffer.from([0])); return; }
    if (id === c.CLIENTBOUND_KEEP_ALIVE) { send(4, data); return; }
    if (id === c.CLIENTBOUND_PING) { send(5, data); return; }
    if (id === c.CLIENTBOUND_SELECT_KNOWN_PACKS) { send(7, vi(0)); return; }
    if (id === c.CLIENTBOUND_CODE_OF_CONDUCT) { send(definitions.acceptCodeOfConduct); return; }
    if (id === c.CLIENTBOUND_FINISH_CONFIGURATION) { send(3); state = 'play'; }
  } else if (id === definitions.login) {
    result.joined = true; finish();
  } else if (id === definitions.disconnect) {
    result.disconnect = data.toString(); finish();
  }
}
socket.on('data', incoming => {
  try {
    buffer = Buffer.concat([buffer, incoming]);
    while (!done) {
      const length = readVi(buffer);
      if (!length || buffer.length < length.size + length.value) break;
      let frame = buffer.subarray(length.size, length.size + length.value);
      buffer = buffer.subarray(length.size + length.value);
      if (compression >= 0) {
        const decoded = readVi(frame); frame = frame.subarray(decoded.size);
        if (decoded.value) frame = zlib.inflateSync(frame);
      }
      const type = readVi(frame); packet(type.value, frame.subarray(type.size));
    }
  } catch (error) { result.error = error.message; finish(); }
});
socket.on('error', error => { result.error = error.message; finish(); });
socket.on('close', () => { if (!done) { result.disconnect ||= 'socket closed'; finish(); } });

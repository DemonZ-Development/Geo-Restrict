// A real TCP Minecraft client. Test-only forwarded addresses never leave loopback.
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const mc = require(path.resolve(__dirname, '../../.smoke/client/node_modules/minecraft-protocol'));
function connect(options) {
return new Promise(resolve => {
const result = { username: options.username, joined: false, disconnect: null, error: null };
let settled = false;
let timer;
function finish() {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  client.end('test complete');
  resolve(result);
}
const args = { host: '127.0.0.1', port: options.port, username: options.username,
  version: options.version || '1.21.11', auth: 'offline', hideErrors: true };
// Official server version.json verifies these pairs share the same wire IDs.
// minecraft-data omits the first release in each pair and otherwise falls back
// to the incompatible 1.21 protocol. The server under test keeps its exact version.
args.version = ({ '1.21.2': '1.21.3', '1.21.7': '1.21.8' })[args.version] || args.version;
if (options.forwardedIp) {
  const uuid = crypto.createHash('md5').update('OfflinePlayer:' + options.username).digest();
  uuid[6] = (uuid[6] & 15) | 48;
  uuid[8] = (uuid[8] & 63) | 128;
  // Legacy forwarding is enabled only in isolated test backend configurations.
  args.fakeHost = 'localhost\0' + options.forwardedIp + '\0' +
    (options.uuid || uuid.toString('hex')) + '\0[]';
}
if (options.proxyIp) {
  args.connect = client => {
    const socket = net.connect(options.port, '127.0.0.1');
    const ipv6 = net.isIP(options.proxyIp) === 6;
    socket.once('connect', () => socket.write(
      `PROXY ${ipv6 ? 'TCP6' : 'TCP4'} ${options.proxyIp} ${ipv6 ? '::1' : '127.0.0.1'} 40000 ${options.port}\r\n`));
    client.setSocket(socket);
  };
}
if (options.fixtureIp) args.fakeHost = 'georestrict-live.invalid|' + options.fixtureIp;
const client = mc.createClient(args);
// Vanilla sends its brand during configuration; NeoForge uses it to finish
// negotiating a vanilla connection. The protocol library does not send it.
client.on('state', state => {
  if (state === 'configuration') client.write('custom_payload', {
    channel: 'minecraft:brand', data: Buffer.from('\x07vanilla')
  });
});
client.on('ping', packet => client.write('pong', { id: packet.id }));
timer = setTimeout(() => { result.error = 'connection timed out'; finish(); }, 20000);
client.once('login', () => {
  result.joined = true;
  // Reaching PLAY is a completed login, including the configuration handshake.
  setTimeout(finish, 250);
});
client.on('kick_disconnect', packet => { result.disconnect = packet.reason; finish(); });
client.on('disconnect', packet => { result.disconnect = packet.reason; finish(); });
client.on('error', error => { result.error = error.message; finish(); });
client.on('end', reason => { if (!settled) { result.disconnect ||= reason; finish(); } });
});
}
module.exports = connect;
if (require.main === module) connect(JSON.parse(process.argv[2])).then(result => {
  console.log(JSON.stringify(result));
  setTimeout(() => process.exit(0), 100);
});

// Offline admission probe for the real Bedrock NetherNet/RakNet transport.
// This is a test companion, never part of a release artifact.
const path = require('node:path');
const root = path.resolve(__dirname, '../../.smoke/expansion/bedrock-client/node_modules');
const bedrock = require(path.join(root, 'bedrock-protocol'));
const { NethernetClient } = require(path.join(root, 'bedrock-protocol/src/nethernet'));
const options = JSON.parse(process.argv[2]);
const result = { username: options.username, joined: false, disconnect: null, error: null, authentication: 'offline', webRtcBackend: options.backend || 'wrtc', transport: options.transport || 'nethernet' };
let client, discovery, done = false;
const timer = setTimeout(() => { result.error = 'Bedrock admission timed out'; finish(); }, 35000);
function finish() {
  if (done) return; done = true; clearTimeout(timer);
  try { discovery?.close(); client?.close(); } catch {}
  console.log(JSON.stringify(result));
  setTimeout(() => process.exit(result.error ? 1 : 0), 100);
}
(async () => {
  let networkId;
  if (result.transport === 'nethernet') {
    discovery = new NethernetClient({ host: options.host || '127.0.0.1', webrtcBackend: options.backend || 'wrtc' });
    const ad = await discovery.ping(10000); networkId = ad.networkId;
    discovery.close(); discovery = undefined;
    result.advertisement = Object.fromEntries(Object.entries(ad).map(([k,v]) => [k, typeof v === 'bigint' ? v.toString() : v]));
    if (options.action === 'ping') return finish();
  }
  client = bedrock.createClient({ transport: result.transport, host: options.host || '127.0.0.1', port: options.port || 19220,
    skipPing: true, version: options.version || '1.26.51', username: options.username, offline: true, conLog: () => {},
    viewDistance: 2, connectTimeout: 30000, nethernet: { networkId, signalling: 'lan', webrtcBackend: options.backend || 'wrtc' },
    raknetBackend: 'jsp-raknet', useRaknetWorkers: false });
  const transport = client.connection?.nethernet;
  if (transport) {
    transport.inactivityTimeoutMs = 25000;
    transport.responseTimeoutMs = 25000;
    transport.on('disconnect', (_address, reason) => { result.transportReason = reason; });
  }
  client.on('start_game', () => { result.joined = true; finish(); });
  client.on('disconnect', packet => { result.disconnect = packet.message || JSON.stringify(packet); finish(); });
  client.on('close', () => { if (!done) { result.disconnect ||= 'transport closed'; finish(); } });
  client.on('error', error => { result.error = error.message; finish(); });
})().catch(error => { result.error = error.message; finish(); });

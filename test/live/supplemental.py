#!/usr/bin/env python3
"""Live modern forwarding and optional-addon checks. Only isolated test runtimes."""
import json
import hashlib
from datetime import datetime, timezone
import re
import socket
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from platforms import Runtime, SMOKE, artifact


def save(name, checks):
    result = {'suite': name, 'checks': checks, 'pass': all(c['pass'] for c in checks)}
    names = ['modern-backend', 'velocity-modern'] if name == 'velocity-modern' else [name]
    result['generatedUTC'] = datetime.now(timezone.utc).isoformat(timespec='seconds')
    result['artifacts'] = {}
    for runtime in names:
        directory = SMOKE / 'runtime' / runtime
        result['artifacts'][runtime] = {}
        for label, path in [('plugin', directory / 'plugins/GeoRestrict.jar'),
                            ('server', directory / 'platform.jar')]:
            with path.open('rb') as stream:
                result['artifacts'][runtime][label] = hashlib.file_digest(stream, 'sha256').hexdigest()
    (SMOKE/'evidence'/f'{name}-live.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)
    assert result['pass'], name


def modern():
    backend = Runtime('modern-backend', artifact('paper'), 25580)
    backend.legacy_forwarding = False
    (backend.directory/'spigot.yml').write_text('settings:\n  bungeecord: false\n')
    secret = 'georestrict-isolated-modern-fixture'
    (backend.directory/'config/paper-global.yml').write_text(
        'proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n'
        f'    secret: {secret}\n')
    proxy = Runtime('velocity-modern', artifact('velocity'), 25581, 'velocity', backend=25580)
    config = proxy.directory/'velocity.toml'
    config.write_text(config.read_text().replace('"legacy"', '"modern"'))
    (proxy.directory/'forwarding.secret').write_text(secret)
    checks = []
    def connect(label, expected, ip):
        result = proxy.connect('Modern' + str(len(checks)), ip)
        checks.append({'case': label, 'pass': result['joined'] == expected and result['error'] is None,
                       'connection': result})
    try:
        backend.start(); proxy.start()
        connect('modern forwarding allows default local join', True, '127.0.0.1')
        backend.policy(blockLocalConnections=True); backend.reload()
        connect('backend sees forwarded public address over signed modern forwarding', True, '8.8.8.8')
        connect('backend rejects forwarded local address', False, '127.0.0.1')
        backend.policy(blockLocalConnections=False); backend.reload()
        proxy.policy(blockLocalConnections=True); proxy.reload()
        connect('proxy rejects local player', False, '127.0.0.1')
        connect('proxy still permits public join through modern backend', True, '8.8.8.8')
        checks.append({'case':'proxy ping remains available','pass':'version' in proxy.status()})
    finally:
        proxy.stop(); backend.stop()
    save('velocity-modern', checks)


def addons():
    # These official addon JARs must already be installed in this isolated runtime.
    runtime = Runtime('geyser-floodgate', artifact('paper'), 25578)
    runtime.legacy_forwarding = False
    (runtime.directory/'spigot.yml').write_text('settings:\n  bungeecord: false\n')
    config = runtime.directory/'plugins/Geyser-Spigot/config.yml'
    assert config.exists(), 'First boot once to generate the official Geyser defaults'
    config.write_text(config.read_text().replace('address: 0.0.0.0', 'address: 127.0.0.1')
                      .replace('auth-type: online', 'auth-type: floodgate'))
    captured = []
    class Webhook(BaseHTTPRequestHandler):
        def do_POST(self):
            captured.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            self.send_response(204); self.end_headers()
        def log_message(self, *args): pass
    webhook = ThreadingHTTPServer(('127.0.0.1', 19084), Webhook)
    threading.Thread(target=webhook.serve_forever, daemon=True).start()
    checks = []
    try:
        offset = len(runtime.log()); runtime.start()
        runtime.wait(lambda: 'Started Geyser on' in runtime.log()[offset:], timeout=120, label='Geyser ready')
        log = runtime.log()[offset:]
        checks.append({'case':'real Geyser, Floodgate and ViaVersion enabled',
                       'pass': all(x in log for x in ['Enabling floodgate','Enabling Geyser-Spigot','Enabling ViaVersion'])})
        magic = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.settimeout(5)
            udp.sendto(b'\x01' + struct.pack('>Q', 1) + magic + struct.pack('>Q', 42), ('127.0.0.1',19132))
            pong = udp.recv(4096)
        checks.append({'case':'real Bedrock RakNet ping receives Geyser advertisement',
                       'pass': pong[0] == 0x1c and b'MCPE;' in pong, 'advertisement': pong[35:].decode(errors='replace')})
        result = runtime.connect('AddonJava')
        checks.append({'case':'Java joins with all addons installed','pass':result['joined'],'connection':result})
        offset = len(runtime.log()); runtime.console('geoprobe')
        runtime.wait(lambda:'GEO_LIVE_API ' in runtime.log()[offset:],label='addon API probe')
        line = next(line for line in runtime.log()[offset:].splitlines() if 'GEO_LIVE_API ' in line)
        api = json.JSONDecoder().raw_decode(line.split('GEO_LIVE_API ',1)[1])[0]
        checks.append({'case':'GeoRestrict detects the real Floodgate API','pass':api.get('floodgateAvailable') is True})
        runtime.policy(blockLocalConnections=True,
            floodgate={'enabled':True,'bypassGeorestrict':True,'bypassVpnCheck':True},
            discord={'webhook':'http://127.0.0.1:19084/','maskIp':True,'logDenied':True,
                     'fields':[{'name':'IP','value':'%ip%','inline':True},
                               {'name':'Country','value':'%country%','inline':True},
                               {'name':'Reason','value':'%reason%','inline':False}]})
        runtime.reload()
        result = runtime.connect('AddonBlocked')
        checks.append({'case':'Floodgate bypass settings do not exempt a Java player',
                       'pass':not result['joined'] and 'LIVE_LOCAL_BLOCK' in json.dumps(result['disconnect'])})
        runtime.wait(lambda:bool(captured),label='local webhook capture')
        fields = {f['name']:f['value'] for f in captured[0]['embeds'][0]['fields']}
        checks.append({'case':'local denial webhook includes LOCAL, reason and masked IP',
                       'pass':fields.get('Country')=='LOCAL' and fields.get('IP')!='127.0.0.1'
                              and fields.get('Reason')=='LIVE_LOCAL_BLOCK','fields':fields})
        runtime.policy(blockLocalConnections=False, discord={'webhook':''}); runtime.reload()
        result = runtime.connect('AddonRestored')
        checks.append({'case':'Java access restored with addons','pass':result['joined']})
    finally:
        runtime.stop(); webhook.shutdown(); webhook.server_close()
    save('geyser-floodgate', checks)


def admission():
    runtime = Runtime('paper-admission', artifact('paper'), 25582)
    checks = []
    def connect(label, expected, ip, reason=None):
        result = runtime.connect('Rule' + str(len(checks)), ip)
        passed = result['joined'] == expected and result['error'] is None
        if reason: passed = passed and reason in json.dumps(result['disconnect'])
        checks.append({'case': label, 'pass':passed, 'connection':result})
        assert passed, checks[-1]
    def command(text, expected):
        offset = len(runtime.log()); runtime.console(text)
        runtime.wait(lambda:expected in re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', runtime.log()[offset:]),label=text)
        checks.append({'case':text,'pass':True})
    try:
        runtime.start()
        runtime.policy(blockLocalConnections=True);runtime.reload()
        for ip in ['10.0.0.1','172.16.0.1','192.168.1.1','169.254.1.1','::1',
                   '0:0:0:0:0:0:0:1','fc00::1','fe80::1','::ffff:127.0.0.1']:
            connect('live forwarded local literal '+ip,False,ip,'LIVE_LOCAL_BLOCK')
        runtime.policy(blockLocalConnections=False, countries=['LOCAL','CN']);runtime.reload()
        command('georestrict check 127.0.0.1','Reason: LIVE_COUNTRY_BLOCK')
        command('georestrict check 8.8.8.8','ASN: AS15169')
        command('georestrict','GeoRestrict v2.1.0')
        command('georestrict invalid','Usage: /georestrict')
        command('georestrict check','Usage: /georestrict check')
        runtime.policy(countryMode='ALLOWLIST',countries=['US']);runtime.reload()
        connect('country allowlist admits US',True,'8.8.8.8')
        connect('country allowlist denies AU',False,'1.1.1.1','LIVE_COUNTRY_BLOCK')
        runtime.policy(countryMode='DISABLED',asnMode='ALLOWLIST',asns=['AS15169']);runtime.reload()
        connect('ASN allowlist admits listed network',True,'8.8.8.8')
        connect('ASN allowlist denies unlisted network',False,'1.1.1.1','LIVE_ASN_BLOCK')
        runtime.policy(asnMode='DISABLED',vpnCheckEnabled=True);runtime.reload()
        connect('VPN ISP keyword rejects hosting ASN',False,'8.8.8.8','LIVE_VPN_BLOCK')
        runtime.policy(vpnCheckEnabled=False);runtime.reload()
        connect('VPN rule can be disabled by live reload',True,'8.8.8.8')
        # Exercise simultaneous asynchronous pre-login checks and real PLAY transitions.
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            joined = list(pool.map(lambda i:runtime.connect('Burst'+str(i),'8.8.8.8'),range(16)))
        checks.append({'case':'16 simultaneous real player joins','pass':all(r['joined'] and r['error'] is None for r in joined)})
    finally:runtime.stop()
    save('paper-admission',checks)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('suite', choices=['modern','addons','admission'])
    args = parser.parse_args()
    {'modern':modern,'addons':addons,'admission':admission}[args.suite]()

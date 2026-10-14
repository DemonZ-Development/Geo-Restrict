#!/usr/bin/env python3
"""Exercise the built plugin in real, isolated Minecraft runtimes.

Downloads/clients are prepared under .smoke. Every bind is loopback-only and
public test addresses are synthetic trusted forwarding metadata, not source IPs.
"""
import argparse
import json
import os
import re
import shutil
import shlex
import socket
import struct
import subprocess
import time
from pathlib import Path
from client_pool import ClientPool
import fcntl

ROOT = Path(__file__).resolve().parents[2]
SMOKE = ROOT / '.smoke'
JAVA21 = Path(os.environ.get('GEO_LIVE_JAVA21', str(Path(os.environ.get('JAVA_HOME', '/usr'))/'bin/java')))
JAVA25 = Path(os.environ.get('GEO_LIVE_JAVA25', str(next((SMOKE / 'tools').glob('jdk*/bin/java'), JAVA21))))
PLUGIN = ROOT / 'target/georestrict-2.1.0.jar'
JVM = ['-XX:ActiveProcessorCount=4', '-Xms128M', '-Xmx1024M',
       *shlex.split(os.environ.get('GEO_LIVE_JVM_ARGS', ''))]


def artifact(name):
    return Path(json.loads((SMOKE / 'evidence' / f'{name}-artifact.json').read_text())['path'])


class Runtime:
    def __init__(self, name, jar, port, kind='server', version='1.21.11', backend=25579):
        self.name, self.port, self.kind, self.version = name, port, kind, version
        self.directory = SMOKE / 'runtime' / name
        self.directory.mkdir(parents=True, exist_ok=True)
        self.runtime_lock=(self.directory/'runtime.lock').open('w')
        try:fcntl.flock(self.runtime_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError(f'{name} already has an active live test')
        plugins = self.directory / 'plugins'
        plugins.mkdir(exist_ok=True)
        platform=self.directory/'platform.jar'
        if Path(jar).resolve()!=platform.resolve():
            platform.unlink(missing_ok=True)
            try:os.link(jar,platform)
            except OSError:shutil.copy2(jar,platform)
        shutil.copy2(PLUGIN, plugins / 'GeoRestrict.jar')
        probe = SMOKE / 'probe/GeoRestrictLiveProbe.jar'
        if probe.exists(): shutil.copy2(probe, plugins / 'GeoRestrictLiveProbe.jar')
        (plugins / 'bStats').mkdir(exist_ok=True)
        (plugins / 'bStats/config.yml').write_text('enabled: false\n')
        self.config = plugins / ('georestrict' if kind == 'velocity' else 'GeoRestrict') / 'config.yml'
        self.config.parent.mkdir(exist_ok=True)
        self.policy()
        if kind == 'server':
            for filename in ['ops.json','banned-ips.json','banned-players.json','whitelist.json']:
                (self.directory/filename).write_text('[]\n')
            (self.directory / 'eula.txt').write_text('eula=true\n')
            (self.directory / 'server.properties').write_text(
                f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
                'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
                'level-type=minecraft:flat\ngenerate-structures=false\n'
                'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
                'spawn-monsters=false\nmax-players=40\nenforce-secure-profile=false\n'
                'white-list=false\nenforce-whitelist=false\n')
            (self.directory / 'spigot.yml').write_text('settings:\n  bungeecord: true\n')
            (self.directory / 'bukkit.yml').write_text('settings:\n  connection-throttle: 0\n')
            (self.directory / 'config').mkdir(exist_ok=True)
            (self.directory / 'config/paper-global.yml').write_text('threaded-regions:\n  threads: 2\n')
        elif kind == 'bungee':
            (self.directory / 'config.yml').write_text(
                'online_mode: false\nip_forward: true\nconnection_throttle: -1\n'
                f'servers:\n  lobby:\n    address: 127.0.0.1:{backend}\n    restricted: false\n'
                'listeners:\n- host: 127.0.0.1:' + str(port) + '\n'
                '  max_players: 40\n  priorities: [lobby]\n  force_default_server: true\n'
                '  proxy_protocol: true\n  query_enabled: false\n  ping_passthrough: true\n')
        elif kind == 'velocity':
            (self.directory / 'velocity.toml').write_text(
                'config-version = "2.7"\n' + f'bind = "127.0.0.1:{port}"\n'
                'online-mode = false\nforce-key-authentication = false\n'
                'player-info-forwarding-mode = "legacy"\n'
                'forwarding-secret-file = "forwarding.secret"\n'
                '[servers]\n' + f'lobby = "127.0.0.1:{backend}"\n' + 'try = ["lobby"]\n'
                '[forced-hosts]\n"localhost" = ["lobby"]\n'
                '[advanced]\nconnection-rate-limit = 0\nlogin-ratelimit = 0\nhaproxy-protocol = true\n')
        self.process = None
        self.output = None

    def policy(self, **changes):
        self.settings = getattr(self, 'settings', {
            'configVersion': 6, 'updateCheck': False, 'gatewayUrl': 'http://127.0.0.1:19081/',
            'connectionTimeoutMs': 3000, 'vpnCheckEnabled': False, 'countryMode': 'BLOCKLIST',
            'countries': ['CN', 'RU'], 'blockLocalConnections': False,
            'blockOnLookupFailure': True, 'kickMessageLocalConnection': 'LIVE_LOCAL_BLOCK',
            'kickMessageCountry': 'LIVE_COUNTRY_BLOCK', 'kickMessageAsn': 'LIVE_ASN_BLOCK',
            'kickMessageVpn': 'LIVE_VPN_BLOCK', 'kickMessageLookupFailure': 'LIVE_LOOKUP_FAILURE',
        })
        self.settings.update(changes)
        # JSON is valid YAML and prevents accidental escaping of IPs/URLs/messages.
        self.config.write_text(json.dumps(self.settings, indent=2))

    def log(self):
        return re.sub(r'\x1b\[[0-9;]*m', '', (self.directory / 'console.log').read_text(errors='replace'))

    def start(self):
        offset = len(self.log()) if (self.directory / 'console.log').exists() else 0
        self.output = (self.directory / 'console.log').open('a')
        java = getattr(self, 'java', JAVA25 if self.kind == 'velocity' else JAVA21)
        command = [str(java), *JVM, '-jar', 'platform.jar']
        if self.kind == 'server': command.append('--nogui')
        self.process = subprocess.Popen(command, cwd=self.directory, stdin=subprocess.PIPE,
                                        stdout=self.output, stderr=subprocess.STDOUT, text=True)
        (self.directory / 'process.pid').write_text(str(self.process.pid))
        pattern = 'Listening on' if self.kind == 'bungee' else r'Done \('
        self.wait(lambda: re.search(pattern, self.log()[offset:]) and 'GeoRestrict enabled.' in self.log()[offset:],
                  timeout=240, label='healthy startup')

    def wait(self, condition, timeout=20, label='condition'):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition(): return
            if self.process.poll() is not None:
                raise RuntimeError(f'{self.name} exited ({self.process.returncode}) waiting for {label}')
            time.sleep(.25)
        raise TimeoutError(f'{self.name} timed out waiting for {label}; see {self.directory}/console.log')

    def console(self, command):
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()

    def reload(self):
        offset = len(self.log())
        self.console('georestrict reload')
        self.wait(lambda: 'GeoRestrict config reloaded.' in self.log()[offset:], label='config reload')

    def connect(self, username, ip='127.0.0.1'):
        options = {'username': username, 'port': self.port, 'version': self.version}
        if getattr(self,'event_address_fixture',False) and ip!='127.0.0.1':
            offset=len(self.log());self.console(f'geoprobe address {username} {ip}')
            self.wait(lambda:f'GEO_LIVE_ADDRESS {username} {ip}' in self.log()[offset:],label='test-only event address')
        if self.kind != 'server': options['proxyIp'] = ip
        elif getattr(self, 'legacy_forwarding', True): options['forwardedIp'] = ip
        return self.client_connection(options)

    def client_connection(self, options):
        if self.version not in ['26.2','26.3']:
            if getattr(self,'protocol_client',None) is None:self.protocol_client=ClientPool(self.directory)
            return self.protocol_client.connect(options)
        client='raw-client.cjs'
        output = subprocess.run(['node', str(ROOT/'test/live'/client), json.dumps(options)],
                                capture_output=True, text=True, timeout=30)
        lines = output.stdout.strip().splitlines()
        if not lines: raise RuntimeError(output.stderr or 'client produced no result')
        return json.loads(lines[-1])

    def status(self):
        def varint(value):
            output = b''
            while value > 127: output += bytes([(value & 127) | 128]); value >>= 7
            return output + bytes([value])
        with socket.create_connection(('127.0.0.1', self.port), timeout=5) as conn:
            if self.kind != 'server':
                conn.sendall(f'PROXY TCP4 127.0.0.1 127.0.0.1 40000 {self.port}\r\n'.encode())
            host = b'localhost'
            versions=json.loads((SMOKE/'client/node_modules/minecraft-data/minecraft-data/data/pc/common/protocolVersions.json').read_text())
            protocol=next(entry['version'] for entry in versions if entry['minecraftVersion']==self.version)
            handshake = b'\x00' + varint(protocol) + varint(len(host)) + host + struct.pack('>H',self.port) + b'\x01'
            conn.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
            def read_varint():
                value = shift = 0
                while True:
                    byte = conn.recv(1)
                    if not byte: raise RuntimeError('status connection closed')
                    value |= (byte[0]&127) << shift
                    if byte[0]&128 == 0: return value
                    shift += 7
            read_varint(); assert read_varint() == 0
            remaining = read_varint(); payload = b''
            while remaining:
                chunk = conn.recv(remaining)
                if not chunk: raise RuntimeError('truncated status')
                payload += chunk; remaining -= len(chunk)
            return json.loads(payload)

    def stop(self):
        if getattr(self,'protocol_client',None):
            self.protocol_client.close();self.protocol_client=None
        if not self.process: return
        if self.process.poll() is None:
            try:
                self.console('end' if self.kind == 'bungee' else 'shutdown' if self.kind == 'velocity' else 'stop')
                self.process.wait(timeout=25)
            except (subprocess.TimeoutExpired, BrokenPipeError):
                self.process.terminate()
                try: self.process.wait(timeout=5)
                except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.output.close()


def run_cases(runtime):
    checks = runtime.current_checks = []
    craftbukkit = getattr(runtime, 'software', runtime.name) == 'craftbukkit' and not getattr(runtime,'event_address_fixture',False)
    def connection(label, expected, ip='127.0.0.1', reason=None):
        result = runtime.connect('Live' + runtime.name[:3] + str(len(checks)), ip)
        passed = result['joined'] == expected and result['error'] is None
        if reason: passed = passed and reason in json.dumps(result['disconnect'])
        checks.append({'case': label, 'pass': passed, 'connection': result})
        print(json.dumps({'platform': runtime.name, **checks[-1]}), flush=True)
        if not passed: raise AssertionError(f'{runtime.name}: {label}: {result}')
    connection('local join with default policy', True)
    runtime.policy(blockLocalConnections=True); runtime.reload()
    connection('local join blocked by explicit setting', False, reason='LIVE_LOCAL_BLOCK')
    status = runtime.status()
    checks.append({'case':'server-list ping survives local blocking','pass':'version' in status})
    if not craftbukkit:
        connection('forwarded public player still joins', True, '8.8.8.8')
        connection('cached public player still joins', True, '8.8.8.8')
    runtime.policy(blockLocalConnections=False, countries=['LOCAL', 'CN']); runtime.reload()
    connection('LOCAL country entry rejects local player', False, reason='LIVE_COUNTRY_BLOCK')
    if not craftbukkit:
        connection('LOCAL country entry preserves public player', True, '8.8.8.8')
    runtime.policy(countries=['US']); runtime.reload()
    if not craftbukkit:
        connection('public country rule still rejects', False, '8.8.8.8', 'LIVE_COUNTRY_BLOCK')
    runtime.policy(countries=[], asnMode='BLOCKLIST', asns=['AS15169']); runtime.reload()
    if not craftbukkit:
        connection('public ASN rule still rejects', False, '8.8.8.8', 'LIVE_ASN_BLOCK')
    runtime.policy(asnMode='DISABLED', blockLocalConnections=True, countryMode='DISABLED'); runtime.reload()
    connection('local block works with country rules disabled', False, reason='LIVE_LOCAL_BLOCK')
    runtime.policy(blockLocalConnections=False, countryMode='BLOCKLIST', countries=['CN', 'RU']); runtime.reload()
    connection('local joins restored by reload', True)
    if not craftbukkit:
        connection('public IPv6 player completes admission',True,'2606:4700:4700::1111')
    # A separately loaded plugin consumes the public API on the real runtime.
    offset = len(runtime.log()); runtime.console('geoprobe')
    runtime.wait(lambda:'GEO_LIVE_API ' in runtime.log()[offset:], label='API probe')
    line = next(line for line in runtime.log()[offset:].splitlines() if 'GEO_LIVE_API ' in line)
    api = json.JSONDecoder().raw_decode(line.split('GEO_LIVE_API ',1)[1])[0]
    passed = (api.get('available') and api.get('version')=='2.1.0' and api.get('serviceRegistered')
        and api.get('countryCode')=='US' and api.get('asn')=='AS15169' and api.get('allowed')
        and api.get('blocked') is False and api.get('localBypass',{}).get('allowed')
        and api.get('cachedCountry')=='US' and api.get('cachedResponse') and api.get('purgedCacheSize')==0
        and 'error' not in api and api.get('batch',{}).get('127.0.0.1') is None)
    checks.append({'case':'external plugin API and cache helpers','pass':bool(passed),'api':api})
    if not passed: raise AssertionError(f'API probe failed: {api}')
    offset = len(runtime.log()); runtime.console('georestrict cachestats')
    runtime.wait(lambda:'GeoRestrict Cache Stats' in runtime.log()[offset:], label='cache command')
    checks.append({'case':'cache stats command','pass':True})
    runtime.console('georestrict purgecache'); time.sleep(.25)
    if craftbukkit:
        # CraftBukkit does not implement Spigot's legacy IP forwarding. Do not
        # mislabel a forwarded-host test as a real public-source connection.
        runtime.policy(blockLocalConnections=False); runtime.reload()
        seed=runtime.connect('livecraop');assert seed['joined'],seed
        offset=len(runtime.log());runtime.console('op livecraop')
        runtime.wait(lambda:'made livecraop' in runtime.log()[offset:].lower(),label='offline operator identity')
        runtime.policy(blockLocalConnections=True);runtime.reload()
        operator = runtime.connect('livecraop')
        checks.append({'case':'operator bypass preserves local access','pass':operator['joined'],'connection':operator})
        if not operator['joined']: raise AssertionError('operator bypass failed')
        runtime.console('deop livecraop')
        runtime.policy(blockLocalConnections=False, configVersion=5); runtime.reload()
        migrated = runtime.config.read_text()
        checks.append({'case':'live schema v5 migration','pass':bool(re.search(r'configVersion:\s*6',migrated))})
        connection('local access survives migration',True)
        return checks
    runtime.policy(gatewayUrl='http://127.0.0.1:1/', blockOnLookupFailure=True); runtime.reload()
    connection('gateway failure rejects when fail-closed', False, '8.8.8.8', 'LIVE_LOOKUP_FAILURE')
    runtime.policy(blockOnLookupFailure=False); runtime.reload()
    connection('gateway failure allows when fail-open', True, '8.8.8.8')
    runtime.policy(gatewayUrl='http://127.0.0.1:19081/',blockOnLookupFailure=True); runtime.reload()
    connection('lookup recovers after gateway restoration',True,'8.8.8.8')
    runtime.stop()
    cache_file = runtime.config.parent / 'geo_cache.json'
    assert cache_file.exists() and '8.8.8.8' in cache_file.read_text(), 'shutdown must persist cache'
    runtime.policy(gatewayUrl='http://127.0.0.1:1/')
    runtime.start()
    connection('persisted cache permits join with gateway offline after restart',True,'8.8.8.8')
    runtime.policy(gatewayUrl='http://127.0.0.1:19081/');runtime.reload()
    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('platform', choices=['paper','purpur','folia','bungee','waterfall','velocity','spigot','craftbukkit','backend'])
    parser.add_argument('--port', type=int, default=25570)
    parser.add_argument('--platform-jar', type=Path, help='Official runtime JAR; overrides the local artifact manifest')
    parser.add_argument('--backend-port', type=int, default=25579)
    args = parser.parse_args()
    name = args.platform
    kind = 'bungee' if name in ['bungee','waterfall'] else 'velocity' if name == 'velocity' else 'server'
    version = '1.21.1' if kind != 'server' or name == 'backend' else '1.21.11'
    if args.platform_jar:
        jar = args.platform_jar.resolve()
    elif name in ['spigot','craftbukkit']:
        jar = SMOKE / 'buildtools' / f'{name}-1.21.11.jar'
    else: jar = artifact('paper-legacy' if name == 'backend' else name)
    runtime = Runtime(name, jar, args.port, kind, version, args.backend_port)
    if name == 'craftbukkit': runtime.legacy_forwarding = False
    result = {'platform': name, 'kind': kind, 'minecraftVersion': version, 'port': args.port,
              'forwarding': 'not supported by CraftBukkit' if name=='craftbukkit' else 'isolated synthetic legacy metadata' if kind == 'server' else 'isolated HAProxy PROXY v1 + legacy backend',
              'log': str(runtime.directory/'console.log'), 'checks': [], 'pass': False}
    try:
        runtime.start()
        print(json.dumps({'platform':name,'startup':'PASS'}),flush=True)
        if name == 'backend':
            while runtime.process.poll() is None: time.sleep(1)
            raise RuntimeError('test backend stopped')
        result['checks'] = run_cases(runtime)
        result['pass'] = True
    except KeyboardInterrupt:
        result['error'] = 'stopped'
    except Exception as error:
        result['error'] = str(error)
        print(json.dumps({'platform':name,'error':str(error)}),flush=True)
    finally:
        runtime.stop()
        result['exitCode'] = runtime.process.returncode if runtime.process else None
        (SMOKE/'evidence'/f'{name}-live.json').write_text(json.dumps(result,indent=2))
    if not result['pass']: raise SystemExit(1)


if __name__ == '__main__': main()

#!/usr/bin/env python3
"""Real dedicated mod-loader runtimes; all paths/listeners are isolated tests."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import re
import threading
import concurrent.futures
import fcntl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from platforms import ROOT, SMOKE, JAVA21, JAVA25, JVM, Runtime, run_cases

MOD_SMOKE = SMOKE/'mods'


class ModRuntime(Runtime):
    def __init__(self, loader, minecraft, port, name_suffix=""):
        self.loader, self.version = loader, minecraft
        self.name, self.port, self.kind = loader+'-'+minecraft+name_suffix, port, 'server'
        self.directory = MOD_SMOKE/'runtime'/self.name
        self.directory.mkdir(parents=True,exist_ok=True)
        self.runtime_lock=(self.directory/'runtime.lock').open('w')
        try:fcntl.flock(self.runtime_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError(f'{self.name} already has an active live test')
        self.legacy_forwarding = False
        self.config = self.directory/'config/georestrict/config.yml'
        self.config.parent.mkdir(parents=True,exist_ok=True)
        self.policy()
        self.process = self.output = None
        mods = self.directory/'mods';mods.mkdir(exist_ok=True)
        artifact_loader = 'fabric' if loader == 'quilt' else loader
        candidate = ROOT/f'mods/{artifact_loader}/target/georestrict-{artifact_loader}-2.1.0.jar'
        shutil.copy2(candidate,mods/'GeoRestrict.jar')
        fixture=MOD_SMOKE/'fixture/GeoRestrictLiveFixture.jar'
        if fixture.exists():shutil.copy2(fixture,mods/'GeoRestrictLiveFixture.jar')
        (self.directory/'eula.txt').write_text('eula=true\n')
        for name in ['ops.json','banned-ips.json','banned-players.json','whitelist.json']:
            (self.directory/name).write_text('[]\n')
        (self.directory/'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
            'level-type=minecraft:flat\ngenerate-structures=false\n'
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
            'max-players=40\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\n')
        if loader=='fabric':
            source=MOD_SMOKE/f'downloads/fabric-{minecraft}.jar'
            target=self.directory/'platform.jar'
            if not target.exists() or not os.path.samefile(source,target):shutil.copy2(source,target)
            if name_suffix:
                original=MOD_SMOKE/'runtime'/f'fabric-{minecraft}'
                for relative in ['libraries','.fabric/server','.fabric/remappedJars']:
                    source=original/relative;destination=self.directory/relative
                    if source.exists() and not destination.exists():
                        destination.parent.mkdir(parents=True,exist_ok=True)
                        destination.symlink_to(source.resolve(),target_is_directory=True)

    def start(self):
        offset=len(self.log()) if (self.directory/'console.log').exists() else 0
        self.output=(self.directory/'console.log').open('a')
        if self.version.startswith('26.'):
            java=JAVA25
        elif self.version in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4']:
            java=Path(os.environ.get('GEO_LIVE_JAVA17',str(next((MOD_SMOKE/'tools').glob('jdk*/bin/java')))))
        else:java=JAVA21
        args=[str(java),*JVM]
        # Vanilla 1.20/1.20.1's login constructor shares a non-thread-safe challenge
        # RNG across Netty loops. A single I/O loop avoids that upstream race;
        # lookup workers and the concurrent real client sockets remain separate.
        if self.version in ['1.20','1.20.1']:args+=['-Dio.netty.eventLoopThreads=1']
        if self.loader in ['fabric','quilt']:args+=['-jar','platform.jar','nogui']
        else:
            installed=self.directory/'installed.json'
            launch=json.loads(installed.read_text()) if installed.exists() else {}
            if 'launchJar' in launch:args+=['-jar',launch['launchJar'],'nogui']
            else:
                files=[Path(launch['args'])] if launch else list((self.directory/'libraries').rglob('unix_args.txt'))
                if len(files)!=1:raise RuntimeError(f'Expected one official server args file, found {files}')
                args+=['@'+str(files[0].resolve()),'nogui']
        self.process=subprocess.Popen(args,cwd=self.directory,stdin=subprocess.PIPE,
            stdout=self.output,stderr=subprocess.STDOUT,text=True)
        (self.directory/'process.pid').write_text(str(self.process.pid))
        self.wait(lambda:'Done (' in self.log()[offset:] and 'GeoRestrict native commands registered.' in self.log()[offset:],
                  timeout=300,label='modded startup and commands')

    def connect(self, username, ip='127.0.0.1'):
        options={'username':username,'port':self.port,'version':self.version}
        if ip!='127.0.0.1':options['fixtureIp']=ip
        return self.client_connection(options)


def pilot(runtime):
    checks=runtime.current_checks=[]
    def connect(label,allowed,reason=None):
        result=runtime.connect('Mod'+str(len(checks)))
        passed=result['joined']==allowed and result['error'] is None
        if reason:passed=passed and reason in json.dumps(result['disconnect'])
        checks.append({'case':label,'pass':passed,'connection':result})
        assert passed,checks[-1]
    connect('default local admission',True)
    operator=runtime.connect('modliveop')
    checks.append({'case':'operator identity seeded by completed native join','pass':operator['joined'],'connection':operator})
    assert operator['joined'],checks[-1]
    runtime.policy(blockLocalConnections=True);runtime.reload()
    connect('explicit local rejection',False,'LIVE_LOCAL_BLOCK')
    assert 'version' in runtime.status()
    checks.append({'case':'status ping while local admission blocked','pass':True})
    runtime.policy(blockLocalConnections=False,countries=['LOCAL']);runtime.reload()
    connect('LOCAL country blocklist rejection',False,'LIVE_COUNTRY_BLOCK')
    offset=len(runtime.log());runtime.console('op modliveop')
    runtime.wait(lambda:'made modliveop' in runtime.log()[offset:].lower(),label='native operator command')
    result=runtime.connect('modliveop')
    checks.append({'case':'native operator bypass','pass':result['joined'],'connection':result})
    assert result['joined'],checks[-1]
    runtime.console('deop modliveop')
    runtime.policy(countries=['CN','RU']);runtime.reload()
    connect('admission restored by reload',True)
    offset=len(runtime.log());runtime.console('georestrict check 8.8.8.8')
    runtime.wait(lambda:'ASN: AS15169' in runtime.log()[offset:],label='real MMDB public lookup command')
    checks.append({'case':'native public lookup command through real gateway','pass':True})
    return checks


def native_extras(runtime, native=True, proxy=False):
    checks=runtime.current_checks=[]
    def command(text, expected):
        offset=len(runtime.log());runtime.console(text)
        runtime.wait(lambda:expected.lower() in runtime.log()[offset:].lower(),label=text)
        checks.append({'case':text,'pass':True})
    def connection(label,allowed,ip='127.0.0.1',reason=None):
        result=runtime.connect('Extra'+str(len(checks)),ip)
        passed=result['joined']==allowed and result['error'] is None
        if reason:passed=passed and reason.lower().replace('-', '') in json.dumps(result['disconnect']).lower().replace('-', '')
        checks.append({'case':label,'pass':passed,'connection':result})
        print(json.dumps({'platform':runtime.name,**checks[-1]}),flush=True)
        assert passed,checks[-1]
    if native:
        watched=[]
        for enabled,name in [(True,'WatchDenied'),(False,'WatchAllowed')]:
            offset=len(runtime.log())
            runtime.policy(blockLocalConnections=enabled)
            runtime.wait(lambda:'GeoRestrict config changed, reloaded.' in runtime.log()[offset:],
                         label='automatic native config reload')
            result=runtime.connect(name)
            assert result['joined']==(not enabled) and result['error'] is None,result
            if enabled:assert 'LIVE_LOCAL_BLOCK' in json.dumps(result['disconnect']),result
            watched.append(result)
        checks.append({'case':'automatic config watcher changes and restores native admission',
                       'pass':True,'connections':watched})
        offset=len(runtime.log());runtime.console('geoadminprobe')
        runtime.wait(lambda:'GEO_LIVE_PERMISSIONS ' in runtime.log()[offset:],label='native command permission probe')
        line=next(line for line in runtime.log()[offset:].splitlines() if 'GEO_LIVE_PERMISSIONS ' in line)
        permissions=json.JSONDecoder().raw_decode(line.split('GEO_LIVE_PERMISSIONS ',1)[1])[0]
        checks.append({'case':'native command levels and Brigadier access','pass':permissions=={
            'lowAdmin':False,'highAdmin':True,'lowCommandDenied':True,'highCommandExecuted':True},'permissions':permissions})
        assert checks[-1]['pass'],checks[-1]
    command('georestrict','GeoRestrict v2.1.0')
    if native:
        command('gr','GeoRestrict v2.1.0')
        command('gr check 8.8.8.8','ASN: AS15169')
    else:command('georestrict check 8.8.8.8','ASN: AS15169')
    command('georestrict invalid','Usage: /georestrict')
    command('georestrict check','Usage: /georestrict check')
    if not native and not proxy:
        seed=runtime.connect('LiveBukkitOp')
        assert seed['joined'],seed
        command('minecraft:op LiveBukkitOp','operator')
        runtime.policy(blockLocalConnections=True);runtime.reload()
        operator=runtime.connect('LiveBukkitOp')
        checks.append({'case':'real Bukkit operator bypass','pass':operator['joined'] and operator['error'] is None,'connection':operator})
        assert checks[-1]['pass'],checks[-1]
        command('minecraft:deop LiveBukkitOp','operator')
        command('geoprobe bypass LivePermission','GEO_LIVE_BYPASS')
        permission=runtime.connect('LivePermission')
        checks.append({'case':'real Bukkit permission attachment bypass','pass':permission['joined'] and permission['error'] is None,'connection':permission})
        assert checks[-1]['pass'],checks[-1]
    runtime.policy(blockLocalConnections=True);runtime.reload()
    for ip in ['10.0.0.1','172.16.0.1','192.168.1.1','169.254.1.1','::1',
               '0:0:0:0:0:0:0:1','fc00::1','fe80::1','::ffff:127.0.0.1']:
        connection('local literal '+ip,False,ip,'LIVE_LOCAL_BLOCK')
    # Vanilla decisions keep their original reason even while GeoRestrict denies locals.
    if not proxy:
        prefix='' if native else 'minecraft:'
        command(prefix+'ban-ip 127.0.0.1','Banned IP')
        connection('vanilla IP ban takes precedence',False,reason='banned')
        command(prefix+'pardon-ip 127.0.0.1','Unbanned IP')
        command(prefix+'whitelist on','Whitelist is now turned on')
        connection('vanilla whitelist takes precedence',False,reason='whitelist')
        command(prefix+'whitelist off','Whitelist is now turned off')
    runtime.policy(blockLocalConnections=False,countryMode='ALLOWLIST',countries=['US']);runtime.reload()
    connection('country allowlist admits US',True,'8.8.8.8')
    connection('country allowlist denies AU',False,'1.1.1.1','LIVE_COUNTRY_BLOCK')
    runtime.policy(countryMode='DISABLED',asnMode='ALLOWLIST',asns=['AS15169']);runtime.reload()
    connection('ASN allowlist admits listed network',True,'8.8.8.8')
    connection('ASN allowlist denies unlisted network',False,'1.1.1.1','LIVE_ASN_BLOCK')
    runtime.policy(asnMode='DISABLED',vpnCheckEnabled=True);runtime.reload()
    connection('VPN ISP keyword rejects hosting ASN',False,'8.8.8.8','LIVE_VPN_BLOCK')
    runtime.policy(vpnCheckEnabled=False);runtime.reload()
    connection('VPN check disabled by reload restores admission',True,'8.8.8.8')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        joins=list(pool.map(lambda i:runtime.connect('Burst'+str(i),'8.8.8.8'),range(16)))
    checks.append({'case':'16 real joins across concurrent clients','pass':all(r['joined'] and r['error'] is None for r in joins),'connections':joins})
    assert checks[-1]['pass'],checks[-1]
    # Hold an actual gateway request open. Both status and a main-thread console
    # command must work before the response is released and the player joins.
    entered=threading.Event();release=threading.Event()
    class DelayedGateway(BaseHTTPRequestHandler):
        def do_GET(self):
            entered.set();release.wait(10)
            body=json.dumps({'ip':'9.9.9.9','countryCode':'US','countryName':'Fixture country',
                'asn':'AS64500','asName':'Fixture network','provider':'local-delay-fixture',
                'isVpn':False,'isProxy':False,'isHosting':False,'isMobile':False}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
            try:self.wfile.write(body)
            except BrokenPipeError:pass
        def log_message(self,*args):pass
    delayed=ThreadingHTTPServer(('127.0.0.1',runtime.port+11000),DelayedGateway)
    threading.Thread(target=delayed.serve_forever,daemon=True).start()
    try:
        runtime.policy(gatewayUrl=f'http://127.0.0.1:{runtime.port+11000}/',connectionTimeoutMs=5000);runtime.reload()
        runtime.console('georestrict purgecache')
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(runtime.connect,'DelayedJoin','9.9.9.9')
            try:
                assert entered.wait(5),'Native lookup did not reach the delayed HTTP fixture'
                checks.append({'case':'status responds while gateway request is pending','pass':'version' in runtime.status() and not future.done()})
                assert checks[-1]['pass'],checks[-1]
                offset=len(runtime.log());runtime.console('georestrict cachestats' if proxy else 'list')
                expected='GeoRestrict Cache Stats' if proxy else 'There are'
                runtime.wait(lambda:expected in runtime.log()[offset:],timeout=4,label='console during pending lookup')
                checks.append({'case':'console executes command while gateway request is pending','pass':not future.done()})
                assert checks[-1]['pass'],checks[-1]
            finally:release.set()
            joined=future.result(timeout=20)
            checks.append({'case':'pending player joins after HTTP response is released','pass':joined['joined'] and joined['error'] is None,'connection':joined})
            assert checks[-1]['pass'],checks[-1]
    finally:release.set();delayed.shutdown();delayed.server_close()
    runtime.policy(gatewayUrl='http://127.0.0.1:19081/',connectionTimeoutMs=3000);runtime.reload()
    runtime.console('georestrict purgecache')
    captured=[]
    class Webhook(BaseHTTPRequestHandler):
        def do_POST(self):
            captured.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            self.send_response(204);self.end_headers()
        def log_message(self,*args):pass
    server=ThreadingHTTPServer(('127.0.0.1',runtime.port+10000),Webhook)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:
        runtime.policy(blockLocalConnections=True,discord={'webhook':f'http://127.0.0.1:{runtime.port+10000}/',
            'maskIp':True,'logDenied':True,'fields':[{'name':'IP','value':'%ip%'},
            {'name':'Country','value':'%country%'},{'name':'Reason','value':'%reason%'}]});runtime.reload()
        connection('local denial sends webhook',False,reason='LIVE_LOCAL_BLOCK')
        runtime.wait(lambda:bool(captured),label='native webhook')
        fields={f['name']:f['value'] for f in captured[0]['embeds'][0]['fields']}
        checks.append({'case':'webhook masks local IP and reports LOCAL reason','pass':fields.get('Country')=='LOCAL'
            and fields.get('IP')!='127.0.0.1' and fields.get('Reason')=='LIVE_LOCAL_BLOCK','fields':fields})
        assert checks[-1]['pass'],checks[-1]
    finally:server.shutdown();server.server_close()
    runtime.policy(blockLocalConnections=False,discord={'webhook':''},configVersion=5);runtime.reload()
    checks.append({'case':'native schema v5 migration','pass':bool(re.search(r'configVersion:\s*6',runtime.config.read_text()))})
    assert checks[-1]['pass'],checks[-1]
    connection('native access survives schema migration',True)
    if native:
        # Datapack reload replaces the Brigadier dispatcher. Maintenance registers anew.
        offset=len(runtime.log());runtime.console('reload')
        runtime.wait(lambda:'GeoRestrict native commands registered.' in runtime.log()[offset:],timeout=60,label='native commands after datapack reload')
        command('gr check 8.8.8.8','ASN: AS15169')
    return checks


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('loader',choices=['fabric','forge','neoforge'])
    parser.add_argument('minecraft')
    parser.add_argument('--port',type=int,default=25590)
    args=parser.parse_args()
    runtime=ModRuntime(args.loader,args.minecraft,args.port)
    result={'loader':args.loader,'minecraft':args.minecraft,'checks':[],'pass':False,
            'log':str(runtime.directory/'console.log')}
    try:
        runtime.start();print(json.dumps({'name':runtime.name,'startup':'PASS'}),flush=True)
        result['checks']=pilot(runtime)
        result['checks']+=run_cases(runtime)
        result['checks']+=native_extras(runtime)
        assert all(check.get('pass') is True for check in result['checks']),'One or more individual checks failed'
        result['pass']=True
    except Exception as error:
        result['checks']+=getattr(runtime,'current_checks',[])
        result['error']=str(error);print(json.dumps(result),flush=True)
    finally:
        runtime.stop()
        result['exitCode']=runtime.process.returncode if runtime.process else None
        artifact=runtime.directory/'mods/GeoRestrict.jar'
        result['sha256']=hashlib.sha256(artifact.read_bytes()).hexdigest()
        fixture=runtime.directory/'mods/GeoRestrictLiveFixture.jar'
        result['fixtureSha256']=hashlib.sha256(fixture.read_bytes()).hexdigest() if fixture.exists() else None
        (MOD_SMOKE/'evidence'/f'{runtime.name}-live.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result),flush=True)
    if not result['pass']:raise SystemExit(1)


if __name__=='__main__':main()

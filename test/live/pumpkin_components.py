#!/usr/bin/env python3
"""Exercise native Pumpkin rules, HTTP deadlines, cache and secure forwarding."""
import concurrent.futures
import hashlib
import json
import os
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
from pumpkin import Pumpkin,BASE,COMPONENT
from platforms import ROOT,Runtime,artifact

class Fixture:
    def __init__(self):
        self.requests=[];self.webhooks=[];self.delay=0;self.status=200;self.body=None
        self.info={'countryCode':'US','countryName':'United States','asn':'AS123','asName':'Example ISP',
            'isVpn':False,'isHosting':False,'isProxy':False,'provider':'native-live-fixture'}
        fixture=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                fixture.requests.append({'ip':parse_qs(urlparse(self.path).query).get('ip',[''])[0],
                    'authorization':self.headers.get('Authorization'),'tokenHeader':self.headers.get('X-GeoRestrict-Token'),
                    'userAgent':self.headers.get('User-Agent'),'query':parse_qs(urlparse(self.path).query)})
                time.sleep(fixture.delay)
                body=fixture.body if fixture.body is not None else json.dumps(fixture.info).encode()
                self.send_response(fixture.status);self.send_header('Content-Type','application/json');self.end_headers()
                try:self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError):pass
            def do_POST(self):
                if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                    parts=[]
                    while True:
                        size=int(self.rfile.readline().split(b';',1)[0].strip(),16)
                        if not size:
                            while self.rfile.readline().strip():pass
                            break
                        parts.append(self.rfile.read(size));self.rfile.read(2)
                    body=b''.join(parts)
                else:body=self.rfile.read(int(self.headers.get('Content-Length',0)))
                fixture.webhooks.append(json.loads(body))
                self.send_response(204);self.end_headers()
        self.server=ThreadingHTTPServer(('127.0.0.1',19320),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)


def toml_policy(directory,changes):
    path=directory/'pumpkin.toml';section='';lines=[]
    for line in path.read_text().splitlines():
        if line.startswith('['):section=line.strip('[]')
        if '=' in line and section in changes:
            key=line.split('=',1)[0].strip()
            if key in changes[section]:line=key+' = '+changes[section][key]
        lines.append(line)
    path.write_text('\n'.join(lines)+'\n')


def main():
    fixture=Fixture();runtime=Pumpkin();proxy=None;checks=[]
    result={'software':'pumpkin','pluginVersion':'2.1.0','sha256':hashlib.sha256(COMPONENT.read_bytes()).hexdigest(),
            'checks':checks,'pass':False,'authenticatedAccountCoverage':False,
            'forwarding':'HMAC-signed Velocity modern wire fixture; actual Velocity 4.0.0-6 has a protocol compatibility gap'}
    def record(case,passed,**details):
        check={'case':case,'pass':bool(passed),**details};checks.append(check);print(json.dumps(check),flush=True)
        if not passed:raise AssertionError(case)
    def command(text,expected):
        offset=len(runtime.log());runtime.console(text);runtime.wait(lambda:expected in runtime.log()[offset:],label=text)
    def clear():command('gr cache clear','GeoRestrict cache cleared.')
    def policy(**changes):runtime.policy(**changes);runtime.reload()
    def check(case,expected,ip='8.8.8.8',uncached=True):
        if uncached:clear()
        command('gr check '+ip,expected);record(case,True)
    def forwarded(name,ip,secret='pumpkin-modern-live-key'):
        args={'username':name,'port':runtime.port,'version':'26.3',
              'modernForwardSecret':secret,'modernForwardIp':ip}
        client=subprocess.run(['node',str(ROOT/'test/live/raw-client.cjs'),json.dumps(args)],
            capture_output=True,text=True,timeout=30)
        return json.loads(client.stdout.strip().splitlines()[-1])
    try:
        toml_policy(runtime.directory,{'networking.proxy':{'enabled':'false'},'networking.proxy.velocity':{'enabled':'false'}})
        runtime.policy(gatewayUrl='http://127.0.0.1:19320/lookup?fixture=native',gatewayToken='pumpkin-live-fixture',countryMode='DISABLED')
        runtime.start()
        check('native HTTP preserves existing query parameters','Allowed: Yes')
        request=fixture.requests[-1];record('existing gateway query parameters retained',request['query'].get('fixture')==['native']);record('native gateway credentials and user agent',
            request['authorization']=='Bearer pumpkin-live-fixture' and request['tokenHeader']=='pumpkin-live-fixture' and 'Pumpkin' in request['userAgent'],request=request)
        before=len(fixture.requests);command('gr check localhost','Expected an IP literal, not a hostname');record('native check rejects hostnames without gateway requests',len(fixture.requests)==before)
        check('native public IPv6 lookup','Allowed: Yes','2606:4700:4700::1111')
        record('IPv6 query arrives intact',fixture.requests[-1]['ip']=='2606:4700:4700::1111')
        policy(countryMode='BLOCKLIST',countries=['US']);check('native country blocklist','PUMPKIN_COUNTRY_BLOCK')
        policy(countryMode='ALLOWLIST',countries=['US']);check('native country allowlist admits match','Allowed: Yes')
        policy(countries=['CA']);check('native country allowlist denies mismatch','PUMPKIN_COUNTRY_BLOCK',uncached=False)
        policy(countryMode='DISABLED',asnMode='BLOCKLIST',asns=['AS123']);check('native ASN blocklist','PUMPKIN_ASN_BLOCK')
        policy(asnMode='ALLOWLIST');check('native ASN allowlist admits match','Allowed: Yes',uncached=False)
        policy(asns=['AS999']);check('native ASN allowlist denies mismatch','PUMPKIN_ASN_BLOCK',uncached=False)
        policy(asnMode='DISABLED',vpnCheckEnabled=True,vpnKeywords=['vpn'])
        for key in ['isVpn','isHosting','isProxy']:
            fixture.info[key]=True;check('native VPN flag '+key,'PUMPKIN_VPN_BLOCK');fixture.info[key]=False
        fixture.info['asName']='Example VPN Network';check('native VPN keyword','PUMPKIN_VPN_BLOCK')
        policy(vpnCheckEnabled=False);check('native VPN disable restores result','Allowed: Yes',uncached=False)
        fixture.info['asName']='Example ISP'
        # Cache is saved at insertion and remains available after a real restart.
        runtime.stop();fixture.status=503;runtime.start()
        check('native persistent cache survives restart with failed gateway','Allowed: Yes',uncached=False)
        check('native uncached HTTP failure denies admission','PUMPKIN_LOOKUP_FAILURE')
        policy(blockOnLookupFailure=False);check('native uncached HTTP failure permits fail-open','Allowed: Yes')
        policy(blockOnLookupFailure=True);fixture.status=200
        for body in [b'{}',b'not-json',b'{"error":"unavailable"}',b'{"countryCode":null}']:
            fixture.body=body;check('native malformed gateway response '+body.decode(),'PUMPKIN_LOOKUP_FAILURE')
        fixture.body=b'x'*70000;check('native oversized gateway response bounded','PUMPKIN_LOOKUP_FAILURE')
        fixture.body=None;fixture.delay=1.5;policy(connectionTimeoutMs=250);clear()
        offset=len(runtime.log());started=time.monotonic();runtime.console('gr check 8.8.8.8')
        record('server status survives delayed native HTTP lookup',bool(runtime.status().get('version')))
        runtime.wait(lambda:'PUMPKIN_LOOKUP_FAILURE' in runtime.log()[offset:],label='HTTP deadline')
        record('native first-byte timeout terminates lookup',time.monotonic()-started<4)
        fixture.delay=0;policy(connectionTimeoutMs=3000)
        check('native recovery after timeout','Allowed: Yes')
        policy(blockLocalConnections=True,countryMode='DISABLED',discord={'webhook':'http://127.0.0.1:19320/webhook','maskIp':True,'logDenied':True})
        initial=len(fixture.requests);client=runtime.connect('PumpWebhook')
        record('native local denial reaches actual Java client',not client['joined'] and 'PUMPKIN_LOCAL_BLOCK' in str(client.get('disconnect')),connection=client)
        record('native local decision makes no gateway request',len(fixture.requests)==initial)
        record('native webhook sends masked address',fixture.webhooks and '127.0.x.x' in json.dumps(fixture.webhooks[-1]) and '127.0.0.1' not in json.dumps(fixture.webhooks[-1]))
        policy(discord={'webhook':''},blockLocalConnections=False)
        original=runtime.config.read_text();runtime.config.write_text('countryMode: [invalid')
        client=runtime.connect('PumpInvalid')
        record('invalid live configuration denies admission',not client['joined'] and 'configuration is invalid' in str(client.get('disconnect')),connection=client)
        runtime.config.write_text(original);runtime.reload()
        client=runtime.connect('PumpRecover');record('valid configuration restores admission',client['joined'],connection=client)
        offset=len(runtime.log());runtime.console('op PumpRecover')
        runtime.wait(lambda:'PumpRecover' in (runtime.directory/'data/ops.json').read_text(),label='native operator grant')
        policy(blockLocalConnections=True)
        client=runtime.connect('PumpRecover');record('native operator bypass survives local blocking',client['joined'],connection=client)
        client=runtime.connect('PumpOrdinary');record('ordinary player remains blocked beside operator bypass',not client['joined'] and 'PUMPKIN_LOCAL_BLOCK' in str(client.get('disconnect')),connection=client)
        policy(blockLocalConnections=False)
        # Authenticated modern forwarding carries the synthetic public source
        # through a real Velocity proxy into Pumpkin's native pre-login event.
        runtime.stop()
        toml_policy(runtime.directory,{'networking.proxy':{'enabled':'true'},'networking.proxy.velocity':{'enabled':'true','secret':'"pumpkin-modern-live-key"'}})
        runtime.policy(blockLocalConnections=True,countryMode='BLOCKLIST',countries=['CN'],gatewayUrl='http://127.0.0.1:19320/lookup')
        runtime.start()
        proxy=Runtime('pumpkin-velocity-modern',artifact('velocity'),25621,'velocity','26.2',backend=runtime.port)
        proxy.policy(countryMode='DISABLED',vpnCheckEnabled=False,blockOnLookupFailure=False,gatewayUrl='http://127.0.0.1:19998/',connectionTimeoutMs=250)
        file=proxy.directory/'velocity.toml';file.write_text(file.read_text().replace('"legacy"','"modern"'))
        (proxy.directory/'forwarding.secret').write_text('pumpkin-modern-live-key')
        proxy.start();clear();fixture.info['countryCode']='US'
        client=proxy.connect('PumpForward','8.8.8.8')
        result['velocityProxyCompatibility']={'pass':client['joined'],'connection':client,
            'build':'4.0.0-6','clientProtocol':776,'backendProtocol':777}
        record('actual Velocity protocol gap identified',not client['joined'] and 'outdated_client' in str(client.get('disconnect')),connection=client)
        client=forwarded('PumpForward','8.8.8.8')
        record('signed forwarding public player survives local blocking',client['joined'],connection=client)
        record('native event uses authenticated forwarded source IP',fixture.requests[-1]['ip']=='8.8.8.8' and 'Pumpkin' in fixture.requests[-1]['userAgent'])
        initial=len(fixture.requests);client=forwarded('PumpBadSign','8.8.8.8','incorrect-fixture-secret')
        record('invalid forwarding signature rejected before native lookup',not client['joined'] and len(fixture.requests)==initial,connection=client)
        fixture.info['countryCode']='CN';clear();client=forwarded('PumpCountry','8.8.8.8')
        record('native country admission denies forwarded public client',not client['joined'] and 'PUMPKIN_COUNTRY_BLOCK' in str(client.get('disconnect')),connection=client)
        fixture.info['countryCode']='US';clear();client=forwarded('PumpLocal','127.0.0.1')
        record('native local admission denies actual forwarded local source',not client['joined'] and 'PUMPKIN_LOCAL_BLOCK' in str(client.get('disconnect')),connection=client)
        fixture.delay=1.5;clear();fixture.info['countryCode']='US'
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(forwarded,'PumpDelayed','8.8.8.8');time.sleep(.25)
            record('status remains responsive during real forwarded login lookup',bool(runtime.status().get('version')))
            client=future.result(timeout=30)
        record('delayed native public login completes admission',client['joined'],connection=client)
        result['pass']=all(c['pass'] for c in checks)
    except Exception as error:result['error']=str(error)
    finally:
        if proxy:proxy.stop();proxy.runtime_lock.close()
        runtime.stop();runtime.runtime_lock.close();fixture.close()
        toml_policy(runtime.directory,{'networking.proxy':{'enabled':'false'},'networking.proxy.velocity':{'enabled':'false'}})
        result['log']=str(runtime.directory/'console.log')
        (BASE/'evidence/pumpkin-native-components.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k!='checks'}),flush=True)
    if not result['pass']:raise SystemExit(1)

if __name__=='__main__':main()

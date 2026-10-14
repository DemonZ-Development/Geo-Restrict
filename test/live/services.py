#!/usr/bin/env python3
"""Real HTTP checks against workerd and a real MMDB fallback.

Provider responses are explicitly local fixtures, never paid-provider results.
The baseline Worker/fallback must already listen on 19081/19082.
"""
import concurrent.futures
import json
import hashlib
from datetime import datetime, timezone
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener

from platforms import ROOT, SMOKE

HTTP = build_opener(ProxyHandler({}))


def request(base, path='/', headers=None, method='GET'):
    try:
        response = HTTP.open(Request(base + path, headers=headers or {}, method=method), timeout=10)
    except HTTPError as error:
        response = error
    body = response.read().decode()
    try: body = json.loads(body)
    except ValueError: pass
    return response.status, body, dict(response.headers)


def main():
    checks = []
    def check(label, condition, **details):
        checks.append({'case':label, 'pass':bool(condition), **details})
        print(json.dumps(checks[-1]),flush=True)
        assert condition, label
    worker, fallback = 'http://127.0.0.1:19081', 'http://127.0.0.1:19082'
    fallback_auth = {'Authorization':'Bearer georestrict-local-test'}
    for base in [worker, fallback]:
        auth = fallback_auth if base == fallback else {}
        code, data, _ = request(base,'/health',auth)
        check(base+' health',code==200 and data['ok'],response=data)
        for ip in ['127.0.0.1','10.2.3.4','169.254.1.1','172.31.255.255','192.168.1.2',
                   '::1','0:0:0:0:0:0:0:1','fc00::1','fe80::1','::ffff:127.0.0.1',
                   '0:0:0:0:0:ffff:7f00:1','::ffff:10.0.0.1','ff02::1']:
            code, data, _ = request(base,'/?ip='+quote(ip,safe=''),auth)
            check(base+' local '+ip,code==200 and data.get('countryCode')=='LOCAL')
        for ip in ['1:2:3:4:5:6::7:8:9','127.00.0.1','example.com','999.1.1.1',':::1']:
            code, data, _ = request(base,'/?ip='+quote(ip,safe=''),auth)
            check(base+' invalid '+ip,code==400 and data.get('error')=='invalid_ip_parameter')
        for ip, country, asn in [('8.8.8.8','US','AS15169'),('1.1.1.1','AU','AS13335')]:
            code, data, _ = request(base,'/?ip='+ip,auth)
            check(base+' real MMDB '+ip,code==200 and data.get('countryCode')==country
                  and data.get('asn')==asn and data.get('provider')=='dbip-local',response=data)
        code, data, _ = request(base,'/?ip=2606:4700:4700::1111',auth)
        check(base+' real public IPv6 MMDB',code==200 and data.get('asn')=='AS13335',response=data)
    for path in ['/health','/?ip=8.8.8.8']:
        code, _, _ = request(fallback,path)
        check('fallback protects '+path,code==401)
    for path in ['/?ip=8.8.8.8&token=georestrict-local-test']:
        code, _, _ = request(fallback,path)
        check('fallback accepts query token',code==200)
    code,_,_ = request(fallback,'/?ip=8.8.8.8',{'X-GeoRestrict-Token':'georestrict-local-test'})
    check('fallback accepts custom-header token',code==200)
    code,_,_=request(worker,'/status')
    check('private status rejects missing token',code==401)
    code,data,headers=request(worker,'/status?key=georestrict-local-status&format=json')
    check('real status reports fallback/cache',code==200 and next(c for c in data['components'] if c['id']=='fallback')['status']=='up',response=data)
    code,data,headers=request(worker,'/status?key=georestrict-local-status')
    check('private status HTML omits token',code==200 and 'georestrict-local-status' not in data and 'no-store' in headers.get('Cache-Control',''))
    code,_,_=request(worker,'/',method='OPTIONS');check('Worker CORS preflight',code==204)
    code,_,_=request(worker,'/',method='POST');check('Worker rejects unsupported methods',code==405)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        responses=list(pool.map(lambda _:request(worker,'/?ip=8.8.8.8')[0],range(48)))
    check('48 concurrent real Worker/MMDB HTTP lookups',all(code==200 for code in responses))

    # Real HTTP provider contracts under a second real workerd instance.
    state={'info':'ok','api':'ok','hits':{'info':0,'api':0}}
    class Provider(BaseHTTPRequestHandler):
        def do_GET(self):
            kind='info' if self.path.startswith('/info/') else 'api'
            state['hits'][kind]+=1
            mode=state[kind]
            if mode=='slow': time.sleep(.6)
            if mode=='down' or mode=='slow':
                self.send_response(503);self.end_headers();return
            ip=self.path.split('/')[2].split('?')[0]
            body=({'ip':ip,'country_code':'US','country_name':'United States','asn':'AS15169','as_name':'Fixture ISP'}
                  if kind=='info' else {'status':'success','countryCode':'GB','country':'United Kingdom',
                                       'as':'AS64500 Fixture ISP','proxy':True,'hosting':True,'mobile':False})
            self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
            try:self.wfile.write(json.dumps(body).encode())
            except BrokenPipeError:pass
        def log_message(self,*args):pass
    server=ThreadingHTTPServer(('127.0.0.1',19085),Provider)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    config=SMOKE/'worker-fixtures.toml'
    config.write_text('name="georestrict-http-fixtures"\nmain="../worker/src/index.js"\ncompatibility_date="2024-03-01"\n'
        '[[kv_namespaces]]\nbinding="GEO_CACHE"\nid="00000000000000000000000000000001"\n'
        '[vars]\nPROVIDER_ORDER="ipinfo,ip-api"\nGATEWAY_TOKENS="local-gateway-fixture"\nSTATUS_TOKENS="local-status-fixture"\n'
        'IPINFO_ENDPOINTS="http://127.0.0.1:19085/info/{ip}"\nIP_API_ENDPOINTS="http://127.0.0.1:19085/api/{ip}"\n'
        'VPS_GATEWAY_URL="http://127.0.0.1:19082/"\nVPS_GATEWAY_TOKEN="georestrict-local-test"\n'
        'CACHE_TTL_SECONDS="86400"\nMEMORY_CACHE_MAX_ENTRIES="0"\nREQUEST_TIMEOUT_MS="250"\n')
    output=(SMOKE/'evidence/worker-fixtures-console.log').open('w')
    command=['node',str(ROOT/'worker/node_modules/wrangler/bin/wrangler.js'),'dev','--config',str(config),
             '--local','--ip','127.0.0.1','--port','19083','--persist-to',str(SMOKE/'fixture-storage')]
    process=subprocess.Popen(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    base='http://127.0.0.1:19083';auth={'Authorization':'Bearer local-gateway-fixture'}
    try:
        deadline=time.monotonic()+60
        while True:
            try:
                if request(base,'/health')[0]==200:break
            except URLError:pass
            if time.monotonic()>deadline:raise TimeoutError('fixture Worker startup')
            time.sleep(.25)
        for headers in [{},{'Authorization':'Bearer wrong'}]:
            code,_,_=request(base,'/?ip=8.8.4.4',headers)
            check('configured Worker rejects missing/wrong token',code==401)
        code,data,_=request(base,'/?ip=8.8.4.4',auth)
        check('real HTTP IPinfo fixture normalizes country/ASN',code==200 and data.get('provider')=='ipinfo' and data.get('asn')=='AS15169',response=data)
        time.sleep(.5);hits=dict(state['hits'])
        code,data,_=request(base,'/?ip=8.8.4.4',auth)
        check('Cache API/KV serve repeat lookup without provider HTTP',code==200 and state['hits']==hits)
        state['info']='down'
        code,data,_=request(base,'/?ip=9.9.9.9',auth)
        check('real HTTP failover from IPinfo to IP-API fixture',code==200 and data.get('provider')=='ip-api' and data.get('isProxy') and data.get('isHosting'),response=data)
        state['api']='down'
        code,data,_=request(base,'/?ip=1.0.0.1',auth)
        check('both HTTP providers fail over to real MMDB',code==200 and data.get('provider')=='dbip-local',response=data)
        state['info']='slow';state['api']='ok'
        code,data,_=request(base,'/?ip=4.2.2.2',auth)
        check('HTTP provider timeout resumes failover',code==200 and data.get('provider')=='ip-api')
        code,data,_=request(base,'/status?key=local-status-fixture&format=json')
        ids={c['id']:c['status'] for c in data['components']}
        check('private status reports provider degradation and KV binding',code==200 and data.get('overall')=='degraded'
              and ids.get('provider:ipinfo')=='down' and ids.get('provider:ip-api')=='up' and ids.get('cache-kv')=='up',response=data)
    finally:
        import signal
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        output.close();server.shutdown();server.server_close()
    result={'suite':'services','providerEndpoints':'local HTTP fixtures','database':'real DB-IP Lite MMDB',
            'checks':checks,'pass':all(c['pass'] for c in checks)}
    result['generatedUTC']=datetime.now(timezone.utc).isoformat(timespec='seconds')
    result['sourceSha256']={}
    for name in ['worker/src/index.js','vps-gateway/src/server.js','vps-gateway/src/ip-address.js']:
        with (ROOT/name).open('rb') as stream:
            result['sourceSha256'][name]=hashlib.file_digest(stream,'sha256').hexdigest()
    (SMOKE/'evidence/services-live.json').write_text(json.dumps(result,indent=2))


if __name__=='__main__':main()

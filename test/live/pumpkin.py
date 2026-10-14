#!/usr/bin/env python3
"""Run the native GeoRestrict WASI component on the official Pumpkin binary.

No Java admission bridge is used. Each result records the tested component hash.
Offline clients prove admission; they do not certify Microsoft/Xbox sessions.
"""
import fcntl
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import time
from pathlib import Path
from platforms import ROOT, Runtime

BASE=ROOT/'.smoke/expansion'
COMPONENT=BASE/'pumpkin-target/wasm32-wasip2/release/georestrict_pumpkin.wasm'

class Pumpkin(Runtime):
    def __init__(self,port=25620):
        self.name='pumpkin-native';self.port=port;self.version='26.3';self.kind='server'
        self.directory=BASE/'pumpkin-runtime';self.directory.mkdir(parents=True,exist_ok=True)
        self.runtime_lock=(self.directory/'runtime.lock').open('w');fcntl.flock(self.runtime_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        server_config=self.directory/'pumpkin.toml'
        if not server_config.exists():
            template=(ROOT/'test/live/pumpkin-template.toml').read_text()
            server_config.write_text(template.replace('127.0.0.1:25620',f'127.0.0.1:{port}'))
        self.config=self.directory/'plugins/data/georestrict/config.yml'
        self.process=self.output=None
        plugins=self.directory/'plugins';plugins.mkdir(exist_ok=True)
        shutil.copy2(COMPONENT,plugins/'GeoRestrict.wasm')
        self.config.parent.mkdir(parents=True,exist_ok=True)
        self.settings={'configVersion':6,'gatewayUrl':'http://127.0.0.1:19081/','gatewayToken':'georestrict-local-test',
            'countryMode':'BLOCKLIST','countries':['CN','RU','KP','IR'],'asnMode':'DISABLED','asns':[],
            'blockLocalConnections':False,'vpnCheckEnabled':False,'blockOnLookupFailure':True,'connectionTimeoutMs':3000,
            'cacheTtlDays':30,'maxCacheEntries':1000,'kickMessageLocalConnection':'PUMPKIN_LOCAL_BLOCK',
            'kickMessageCountry':'PUMPKIN_COUNTRY_BLOCK','kickMessageAsn':'PUMPKIN_ASN_BLOCK',
            'kickMessageVpn':'PUMPKIN_VPN_BLOCK','kickMessageLookupFailure':'PUMPKIN_LOOKUP_FAILURE'}
        self.policy()
        self.legacy_forwarding=False
    def start(self,stock=False):
        offset=len(self.log()) if (self.directory/'console.log').exists() else 0
        self.output=(self.directory/'console.log').open('a')
        env=dict(os.environ,RAYON_NUM_THREADS='1',TOKIO_WORKER_THREADS='2',RUST_LOG='info')
        self.process=subprocess.Popen([str(BASE/'pumpkin-X64-Linux')],cwd=self.directory,
            stdin=subprocess.PIPE,stdout=self.output,stderr=subprocess.STDOUT,text=True,env=env)
        (self.directory/'process.pid').write_text(str(self.process.pid))
        self.wait(lambda:self.config.exists() and ('Started server;' if stock else 'GeoRestrict native admission enabled (2.1.0)') in self.log()[offset:] and self.socket_ready(),timeout=240,label='native plugin startup')
        # Ensure handlers and commands are registered before accepting test traffic.
        time.sleep(1)
    def socket_ready(self):
        try:
            with socket.create_connection(('127.0.0.1',self.port),timeout=.3):return True
        except OSError:return False
    def reload(self):
        offset=len(self.log());self.console('gr reload')
        self.wait(lambda:'GeoRestrict configuration reloaded.' in self.log()[offset:],label='native reload')
    def status(self):
        def vi(n):
            b=[]
            while n>127:b.append((n&127)|128);n>>=7
            return bytes(b+[n])
        def readvi(conn):
            result=0
            for i in range(5):
                byte=conn.recv(1)
                if not byte:raise RuntimeError('Status socket closed')
                result|=(byte[0]&127)<<(7*i)
                if byte[0]<128:return result
            raise RuntimeError('Invalid status VarInt')
        with socket.create_connection(('127.0.0.1',self.port),timeout=10) as conn:
            handshake=b'\0'+vi(777)+vi(9)+b'localhost'+struct.pack('>H',self.port)+b'\1'
            conn.sendall(vi(len(handshake))+handshake+b'\1\0')
            length=readvi(conn);packet=b''
            while len(packet)<length:packet+=conn.recv(length-len(packet))
            assert packet[0]==0
            # Strip packet id + string length; JSON begins with '{'.
            return json.loads(packet[packet.index(b'{'):])
    def stop(self):
        if getattr(self,'protocol_client',None):self.protocol_client.close();self.protocol_client=None
        if self.process and self.process.poll() is None:
            try:self.console('stop');self.process.wait(timeout=60)
            except (BrokenPipeError,subprocess.TimeoutExpired):self.process.terminate();self.process.wait(timeout=20)
        if self.output:self.output.close()

def main():
    runtime=Pumpkin();checks=[]
    result={'software':'pumpkin','pluginVersion':'2.1.0','serverRelease':'0.2.0+26.3-26.51','sha256':hashlib.sha256(COMPONENT.read_bytes()).hexdigest(),
        'native':True,'javaBridgeRequired':False,'authenticatedAccountCoverage':False,'checks':checks,'pass':False}
    def record(case,passed,**details):
        check={'case':case,'pass':bool(passed),**details};checks.append(check);print(json.dumps(check),flush=True)
        if not passed:raise AssertionError(case)
    def join(case,name,allowed,reason=None):
        client=runtime.connect(name)
        record(case,client['joined']==allowed and (allowed or reason in str(client.get('disconnect'))),connection=client)
    def command(command,expected):
        offset=len(runtime.log());runtime.console(command);runtime.wait(lambda:expected in runtime.log()[offset:],label=command)
        return runtime.log()[offset:]
    try:
        runtime.start()
        record('native component loads without Java admission bridge',True)
        command('gr info','GeoRestrict 2.1.0');record('native info command',True)
        join('default local Java player reaches PLAY','PumpLive0',True)
        runtime.policy(blockLocalConnections=True);runtime.reload()
        join('native pre-login local block','PumpLive1',False,'PUMPKIN_LOCAL_BLOCK')
        record('Java server-list status survives denial',bool(runtime.status().get('version')))
        runtime.policy(blockLocalConnections=False,countries=['LOCAL']);runtime.reload()
        join('LOCAL country blocklist rejects loopback','PumpLive2',False,'PUMPKIN_COUNTRY_BLOCK')
        runtime.policy(countryMode='DISABLED',blockLocalConnections=True);runtime.reload()
        join('local flag works with country rules disabled','PumpLive3',False,'PUMPKIN_LOCAL_BLOCK')
        runtime.policy(blockLocalConnections=False,countryMode='BLOCKLIST',countries=[]);runtime.reload()
        join('native reload restores Java joins','PumpLive4',True)
        command('gr check 127.0.0.1','Allowed: Yes');record('native local check command',True)
        command('gr check 8.8.8.8','Allowed: Yes');record('native public lookup through real Worker and fallback',True)
        runtime.policy(countryMode='ALLOWLIST',countries=['ZZ']);runtime.reload()
        command('gr check 8.8.8.8','PUMPKIN_COUNTRY_BLOCK');record('native cached country rules reevaluate on reload',True)
        command('gr cache clear','GeoRestrict cache cleared.');record('native persistent cache clear',True)
        runtime.policy(countryMode='DISABLED',gatewayUrl='http://127.0.0.1:19998/',blockOnLookupFailure=True);runtime.reload()
        command('gr check 8.8.8.8','PUMPKIN_LOOKUP_FAILURE');record('native gateway failure closes admission',True)
        runtime.policy(blockOnLookupFailure=False);runtime.reload()
        command('gr check 8.8.8.8','Allowed: Yes');record('native gateway failure respects fail-open',True)
        runtime.policy(gatewayUrl='http://127.0.0.1:19081/',blockOnLookupFailure=True);runtime.reload()
        command('gr check 8.8.8.8','Allowed: Yes');record('native gateway recovery',True)
        def bedrock(case,name,allowed,reason=None,action=None):
            args={'username':name,'transport':'nethernet','version':'1.26.51'}
            if action:args['action']=action
            client=subprocess.run(['node',str(ROOT/'test/live/bedrock-client.cjs'),json.dumps(args)],capture_output=True,text=True,timeout=45)
            lines=client.stdout.strip().splitlines()
            if not lines:raise RuntimeError(client.stderr or 'Bedrock client produced no result')
            data=json.loads(lines[-1])
            passed=bool(data.get('advertisement')) if action=='ping' else data['joined']==allowed and (allowed or reason in str(data.get('disconnect')))
            record(case,passed,connection=data)
        bedrock('native Bedrock NetherNet advertisement','BedrockPing',False,action='ping')
        bedrock('default native Bedrock player reaches START_GAME','PumpBed0',True)
        runtime.policy(blockLocalConnections=True);runtime.reload()
        bedrock('native Bedrock local admission denied','PumpBed1',False,'PUMPKIN_LOCAL_BLOCK')
        bedrock('Bedrock discovery survives local denial','BedrockPing',False,action='ping')
        runtime.policy(floodgate={'enabled':True,'bypassGeorestrict':True});runtime.reload()
        bedrock('native Bedrock full bypass respects configuration','PumpBed2',True)
        join('Bedrock full bypass does not bypass Java denial','PumpJava9',False,'PUMPKIN_LOCAL_BLOCK')
        runtime.policy(floodgate={'enabled':False,'bypassGeorestrict':True});runtime.reload()
        bedrock('disabled Bedrock integration removes full bypass','PumpBed3',False,'PUMPKIN_LOCAL_BLOCK')
        runtime.policy(blockLocalConnections=False,floodgate={'enabled':True});runtime.reload()
        bedrock('native Bedrock joins restored by reload','PumpBed4',True)
        result['pass']=all(check['pass'] for check in checks)
    except Exception as error:result['error']=str(error)
    finally:
        runtime.stop();runtime.runtime_lock.close()
        result['log']=str(runtime.directory/'console.log')
        evidence=BASE/'evidence';evidence.mkdir(exist_ok=True)
        (evidence/'pumpkin-native-live.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k!='checks'}),flush=True)
    if not result['pass']:raise SystemExit(1)

if __name__=='__main__':main()

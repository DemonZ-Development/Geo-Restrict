#!/usr/bin/env python3
"""Published stable release inventory and resumable real-server matrix.

Upstream gaps are recorded as unavailable, never counted as successful tests.
Only loopback test listeners are created. Installers come from loader publishers.
"""
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
import urllib.request
import xml.etree.ElementTree as XML
from pathlib import Path

from platforms import ROOT, SMOKE, JAVA21, JAVA25, Runtime, run_cases
from mods import MOD_SMOKE, ModRuntime, native_extras

EVIDENCE=MOD_SMOKE/'evidence'
DOWNLOADS=MOD_SMOKE/'downloads'
INVENTORY=EVIDENCE/'version-inventory.json'


def fetch(url,destination):
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists():return destination
    request=urllib.request.Request(url,headers={'User-Agent':'GeoRestrict live compatibility tests (github.com/DemonZ-Development/Geo-Restrict)'})
    temporary=destination.with_suffix(destination.suffix+'.part')
    try:
        with urllib.request.urlopen(request,timeout=120) as response,temporary.open('wb') as output:
            shutil.copyfileobj(response,output)
        temporary.replace(destination)
    finally:temporary.unlink(missing_ok=True)
    return destination


def metadata(name,url):return json.loads(fetch(url,EVIDENCE/name).read_text())


def versions(name,url):return [v.text for v in XML.parse(fetch(url,EVIDENCE/name)).findall('.//version')]


def latest(values):
    stable=[v for v in values if '-beta' not in v and '-alpha' not in v and '-rc' not in v]
    return max(stable or values,key=lambda v:tuple(int(n) for n in re.findall(r'\d+',v))) if values else None


def bukkit_version(mc, revision):
    """Resolve BuildTools revision aliases using its official BuildData checkout."""
    checkout=SMOKE/'buildtools/BuildData'
    if not (checkout/'.git').exists():return None
    ref=revision['refs']['BuildData']
    info=json.loads(subprocess.check_output(['git','-C',str(checkout),'show',ref+':info.json'],text=True))
    return info['minecraftVersion'].removesuffix('_unobfuscated')


def inventory():
    manifest=metadata('minecraft-versions.json','https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    releases=[]
    for release in manifest['versions']:
        if release['type']=='release':releases.append(release)
        if release['id']=='1.20':break
    releases.reverse()
    if releases[-1]['id']!='26.3':raise RuntimeError('Refresh the requested upper bound explicitly')
    fabric=metadata('fabric-loaders.json','https://meta.fabricmc.net/v2/versions/loader')[0]['version']
    installer=metadata('fabric-installers.json','https://meta.fabricmc.net/v2/versions/installer')[0]['version']
    forge=versions('forge-versions.xml','https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml')
    neo=versions('neoforge-versions.xml','https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml')
    legacy=versions('neoforge-legacy-versions.xml','https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml')
    software={name:metadata(name+'-versions.json',f'https://fill.papermc.io/v3/projects/{name}/versions') for name in ['paper','folia','waterfall']}
    purpur=metadata('purpur-versions.json','https://api.purpurmc.org/v2/purpur')['versions']
    cases=[]
    for release in releases:
        mc=release['id']; java=25 if mc.startswith('26.') else 17 if mc in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4'] else 21
        info=metadata('minecraft-'+mc+'.json',release['url'])
        for name in ['fabric','forge','neoforge','paper','purpur','folia','spigot','craftbukkit','bungee','waterfall','velocity']:
            case={'software':name,'minecraft':mc,'java':java,'availability':'published','testStatus':'pending'}
            if name=='fabric':case.update(loader=fabric,url=f'https://meta.fabricmc.net/v2/versions/loader/{mc}/{fabric}/{installer}/server/jar')
            elif name in ['forge','neoforge']:
                if name=='forge':group='net/minecraftforge/forge';build=latest([v for v in forge if v.startswith(mc+'-')]);artifact='forge'
                elif mc=='1.20.1':group='net/neoforged/forge';build=latest([v for v in legacy if v.startswith(mc+'-')]);artifact='forge'
                else:
                    group='net/neoforged/neoforge';artifact='neoforge'
                    if mc.startswith('1.20.'):prefix='20.'+mc.split('.')[2]+'.'
                    elif mc.startswith('1.21'):prefix='21.'+(mc.split('.')[2] if len(mc.split('.'))==3 else '0')+'.'
                    else:prefix=mc+'.' if len(mc.split('.'))==3 else mc+'.0.'
                    build=latest([v for v in neo if v.startswith(prefix)])
                if build:
                    host='https://maven.minecraftforge.net/' if name=='forge' else 'https://maven.neoforged.net/releases/'
                    case.update(loader=build,url=f'{host}{group}/{build}/{artifact}-{build}-installer.jar',beta='-beta' in build)
                else:case.update(availability='unavailable',testStatus='upstream-gap',reason='No published loader for this exact Minecraft release')
            elif name in ['paper','folia','waterfall']:
                available=[x['version']['id'] for x in software[name]['versions']]
                if name=='waterfall':case['availability']='protocol-check-required';case['reason']='Proxy release lines are not Minecraft support guarantees'
                elif mc not in available:case.update(availability='unavailable',testStatus='upstream-gap',reason='No published server artifact for this exact release')
            elif name=='purpur' and mc not in purpur:case.update(availability='unavailable',testStatus='upstream-gap',reason='No published server artifact for this exact release')
            elif name in ['spigot','craftbukkit']:
                try:
                    revision=metadata('spigot-'+mc+'.json',f'https://hub.spigotmc.org/versions/{mc}.json')
                    actual=bukkit_version(mc,revision)
                    if actual and actual!=mc:
                        case.update(availability='unavailable',testStatus='upstream-alias',
                            reason=f'Official BuildTools --rev {mc} resolves to Minecraft {actual}',resolvedMinecraft=actual)
                except urllib.error.HTTPError as error:
                    if error.code!=404:raise
                    case.update(availability='unavailable',testStatus='upstream-gap',reason='No official BuildTools revision for this release')
            elif name in ['velocity','bungee']:case['availability']='protocol-check-required';case['reason']='Current proxy must be tested with this client/backend protocol'
            cases.append(case)
        info['testJava']=java
    with INVENTORY.with_suffix('.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        existing=json.loads(INVENTORY.read_text())['cases'] if INVENTORY.exists() else []
        for case in cases:
            prior=next((c for c in existing if c['software']==case['software'] and c['minecraft']==case['minecraft']),{})
            if case['availability']!='unavailable':
                for key in ['testStatus','testResult','error','buildStatus','runtimeBuild','runtimeSha256','channel']:
                    if key in prior:case[key]=prior[key]
        temporary=INVENTORY.with_suffix('.tmp')
        temporary.write_text(json.dumps({'minecraftReleases':[v['id'] for v in releases],'cases':cases},indent=2));temporary.replace(INVENTORY)
    return cases


def java_for(mc):
    return JAVA25 if mc.startswith('26.') else Path(os.environ.get('GEO_LIVE_JAVA17',str(next((MOD_SMOKE/'tools').glob('jdk*/bin/java'))))) if mc in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4'] else JAVA21


def install(case):
    name,mc=case['software'],case['minecraft']
    directory=MOD_SMOKE/'runtime'/f'{name}-{mc}';directory.mkdir(parents=True,exist_ok=True)
    if name=='fabric':return fetch(case['url'],DOWNLOADS/f'fabric-{mc}.jar')
    # Every runtime uses official coordinate paths, so immutable dependencies can
    # be shared without duplicating many gigabytes across Minecraft versions.
    libraries=MOD_SMOKE/'libraries';libraries.mkdir(exist_ok=True)
    local=directory/'libraries'
    if not local.exists():local.symlink_to(libraries, target_is_directory=True)
    if list(local.rglob('unix_args.txt')) and not local.is_symlink():return local
    own=directory/'installed.json'
    if own.exists():return local
    jar=fetch(case['url'],DOWNLOADS/f'{name}-{case["loader"]}-installer.jar')
    env=dict(os.environ);env['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=2 '+os.environ.get('GEO_LIVE_JVM_ARGS','')
    with (EVIDENCE/f'{name}-{mc}-install.log').open('w') as output:
        result=subprocess.run([str(java_for(mc)),'-jar',str(jar),'--installServer',str(directory)],cwd=directory,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=900)
    if result.returncode:raise RuntimeError(f'{name}-{mc} installer failed; inspect install log')
    args=[f for f in local.rglob('unix_args.txt') if case['loader'] in str(f)]
    shim=list(directory.glob('*-shim.jar'))
    if not args and len(shim)==1:
        own.write_text(json.dumps({'loader':case['loader'],'launchJar':str(shim[0].resolve()),
            'installerSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}))
        return local
    if len(args)!=1:raise RuntimeError(f'Cannot identify launch arguments for {case}: {args}')
    own.write_text(json.dumps({'loader':case['loader'],'args':str(args[0].resolve()),'installerSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}))
    return local


def persist(case, fields):
    with INVENTORY.with_suffix('.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        report=json.loads(INVENTORY.read_text())
        current=next(c for c in report['cases'] if c['software']==case['software'] and c['minecraft']==case['minecraft'])
        for field in fields:
            if field in case:current[field]=case[field]
            else:current.pop(field,None)
        temporary=INVENTORY.with_suffix('.tmp');temporary.write_text(json.dumps(report,indent=2));temporary.replace(INVENTORY)


def wire_definitions(mc):
    if mc not in ['26.2','26.3']:return
    info=json.loads((EVIDENCE/('minecraft-'+mc+'.json')).read_text())
    download=info['downloads']['server']
    jar=fetch(download['url'],DOWNLOADS/('vanilla-'+mc+'.jar'))
    if hashlib.sha1(jar.read_bytes()).hexdigest()!=download['sha1']:raise RuntimeError('Official Minecraft server checksum mismatch')
    inner=DOWNLOADS/('minecraft-'+mc+'.jar')
    if not inner.exists():
        with zipfile.ZipFile(jar) as bundle:
            entry=next(n for n in bundle.namelist() if n.startswith('META-INF/versions/') and n.endswith('.jar'))
            inner.write_bytes(bundle.read(entry))
    with zipfile.ZipFile(inner) as game:version=json.loads(game.read('version.json'))
    javap=JAVA25.parent/'javap'
    bytecode=subprocess.check_output([str(javap),'-c','-p','-classpath',str(inner),
        'net.minecraft.network.protocol.configuration.ConfigurationProtocols','net.minecraft.network.protocol.game.GameProtocols'],text=True)
    (EVIDENCE/f'protocols-{mc}.txt').write_text(bytecode)
    definitions={'protocol':version['protocol_version'],'officialServerSha1':download['sha1']}
    for section in bytecode.split('private static void '):
        names=re.findall(r'Field .*PacketTypes\.(CLIENTBOUND_\w+|SERVERBOUND_\w+):',section)
        if 'CLIENTBOUND_FINISH_CONFIGURATION' in names:definitions['configuration']={n:i for i,n in enumerate(names)}
        if 'SERVERBOUND_ACCEPT_CODE_OF_CONDUCT' in names:definitions['acceptCodeOfConduct']=names.index('SERVERBOUND_ACCEPT_CODE_OF_CONDUCT')
        if 'CLIENTBOUND_LOGIN' in names:
            definitions['login']=names.index('CLIENTBOUND_LOGIN');definitions['disconnect']=names.index('CLIENTBOUND_DISCONNECT')
    for key in ['configuration','login','disconnect','acceptCodeOfConduct']:
        if key not in definitions:raise RuntimeError(f'Cannot derive {key} for {mc}')
    file=EVIDENCE/'raw-protocols.json';all_versions=json.loads(file.read_text()) if file.exists() else {}
    all_versions[mc]=definitions;file.write_text(json.dumps(all_versions,indent=2))


def run_mods(selected=None, software=None, port=25594):
    report=json.loads(INVENTORY.read_text())
    for case in report['cases']:
        if case['software'] not in ['fabric','forge','neoforge'] or case['availability']=='unavailable':continue
        if selected and case['minecraft'] not in selected:continue
        if software and case['software'] not in software:continue
        evidence=EVIDENCE/f'{case["software"]}-{case["minecraft"]}-live.json'
        candidate=ROOT/f'mods/{case["software"]}/target/georestrict-{case["software"]}-2.1.0.jar'
        sha=hashlib.sha256(candidate.read_bytes()).hexdigest()
        if evidence.exists():
            prior=json.loads(evidence.read_text())
            fixture=MOD_SMOKE/'fixture/GeoRestrictLiveFixture.jar'
            fixture_sha=hashlib.sha256(fixture.read_bytes()).hexdigest()
            if (prior.get('pass') and prior.get('sha256')==sha and prior.get('fixtureSha256')==fixture_sha
                and len(prior.get('checks',[]))>=63
                and all(check.get('pass') is True for check in prior.get('checks',[]))):
                case['testStatus']='passed';persist(case,['testStatus']);continue
        print(json.dumps({'software':case['software'],'minecraft':case['minecraft'],'loader':case['loader'],
            'action':'install-and-live-test'}),flush=True)
        case.pop('error',None);case.pop('testResult',None)
        try:
            wire_definitions(case['minecraft'])
            install(case)
            with (EVIDENCE/f'{case["software"]}-{case["minecraft"]}-run.log').open('w') as output:
                result=subprocess.run([sys.executable,str(ROOT/'test/live/mods.py'),case['software'],case['minecraft'],'--port',str(port)],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,timeout=900)
            case['testStatus']='passed' if result.returncode==0 else 'failed'
            if evidence.exists():case['testResult']=json.loads(evidence.read_text())
        except Exception as error:case.update(testStatus='failed',error=str(error))
        print(json.dumps({'software':case['software'],'minecraft':case['minecraft'],'testStatus':case['testStatus']}),flush=True)
        persist(case,['testStatus','testResult','error'])


def plugin_artifact(case):
    name,mc=case['software'],case['minecraft']
    if name in ['spigot','craftbukkit']:
        jar=SMOKE/'buildtools'/f'{name}-{mc}.jar'
        if not jar.exists():raise RuntimeError('Official BuildTools artifact is not built yet')
        return jar
    if name in ['bungee','velocity','waterfall']:
        file=SMOKE/'evidence'/f'{name}-artifact.json'
        return Path(json.loads(file.read_text())['path'])
    if name=='purpur':
        info=metadata(f'purpur-{mc}-latest.json',f'https://api.purpurmc.org/v2/purpur/{mc}/latest')
        jar=fetch(f'https://api.purpurmc.org/v2/purpur/{mc}/{info["build"]}/download',DOWNLOADS/f'purpur-{mc}-{info["build"]}.jar')
        if info.get('md5') and hashlib.md5(jar.read_bytes()).hexdigest()!=info['md5']:raise RuntimeError('Official Purpur checksum mismatch')
    else:
        info=metadata(f'{name}-{mc}-latest.json',f'https://fill.papermc.io/v3/projects/{name}/versions/{mc}/builds/latest')
        data=info['downloads']['server:default'];jar=fetch(data['url'],DOWNLOADS/data['name'])
        if hashlib.sha256(jar.read_bytes()).hexdigest()!=data['checksums']['sha256']:raise RuntimeError('Official PaperMC checksum mismatch')
        case['channel']=info['channel']
    case['runtimeBuild']=info.get('id',info.get('build'));case['runtimeSha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
    persist(case,['channel','runtimeBuild','runtimeSha256'])
    return jar


def run_plugins(selected=None, software=None, port=25596):
    report=json.loads(INVENTORY.read_text())
    for case in report['cases']:
        name,mc=case['software'],case['minecraft']
        if name in ['fabric','forge','neoforge'] or case['availability']=='unavailable':continue
        if selected and mc not in selected:continue
        if software and name not in software:continue
        file=EVIDENCE/f'{name}-{mc}-plugin-live.json'
        prior=json.loads(file.read_text()) if file.exists() else {}
        minimum=56 if name in ['paper','purpur','folia','spigot','craftbukkit'] else 45
        probe_sha=hashlib.sha256((SMOKE/'probe/GeoRestrictLiveProbe.jar').read_bytes()).hexdigest()
        candidate_sha=hashlib.sha256((ROOT/'target/georestrict-2.1.0.jar').read_bytes()).hexdigest()
        if (prior.get('pass') and len(prior.get('checks',[]))>=minimum
                and all(check.get('pass') is True for check in prior.get('checks',[]))
                and prior.get('fixtureSha256')==probe_sha and prior.get('sha256')==candidate_sha):
            case['testStatus']='passed';persist(case,['testStatus']);continue
        print(json.dumps({'software':name,'minecraft':mc,'action':'plugin-live-test'}),flush=True)
        runtime=backend=None
        result={'software':name,'minecraft':mc,'pass':False,'checks':[]}
        try:
            jar=plugin_artifact(case);wire_definitions(mc)
            kind='bungee' if name in ['bungee','waterfall'] else 'velocity' if name=='velocity' else 'server'
            if kind!='server':
                fabric_case=next(c for c in report['cases'] if c['software']=='fabric' and c['minecraft']==mc)
                install(fabric_case)
                backend=ModRuntime('fabric',mc,port+1,'-proxy-'+name);backend.policy(countryMode='DISABLED',asnMode='DISABLED');backend.start()
            runtime=Runtime(name+'-'+mc,jar,port,kind,mc,port+1);runtime.software=name
            runtime.java=JAVA25 if kind!='server' else java_for(mc)
            if name=='craftbukkit':
                runtime.legacy_forwarding=False
                runtime.event_address_fixture=True
            libraries=runtime.directory/'libraries'
            if not libraries.exists():libraries.symlink_to(MOD_SMOKE/'libraries',target_is_directory=True)
            runtime.start();print(json.dumps({'software':name,'minecraft':mc,'startup':'PASS'}),flush=True)
            if name=='craftbukkit':
                offset=len(runtime.log());probe=runtime.connect('HookProbe')
                if not probe['joined']:raise AssertionError(f'Baseline CraftBukkit admission failed: {probe}')
                if 'GEO_LIVE_ASYNC HookProbe' not in runtime.log()[offset:]:
                    runtime.event_address_fixture=False
                    result['checks']=run_cases(runtime)
                    result['scope']='local-policy-only'
                    result['unsupported']='This CraftBukkit runtime skips asynchronous pre-login for offline players; public admission cannot be certified without blocking the server thread'
                    raise RuntimeError(result['unsupported'])
            result['checks']=run_cases(runtime)
            result['checks']+=native_extras(runtime,native=False,proxy=kind!='server')
            assert all(check.get('pass') is True for check in result['checks']),'One or more individual checks failed'
            result['pass']=True
        except Exception as error:
            result['error']=str(error)
            if runtime and getattr(runtime,'current_checks',None) is not result['checks']:
                result['checks']+=getattr(runtime,'current_checks',[])
        finally:
            if runtime:runtime.stop();result['exitCode']=runtime.process.returncode if runtime.process else None;result['log']=str(runtime.directory/'console.log')
            if backend:backend.stop()
        result['sha256']=hashlib.sha256((runtime.directory/'plugins/GeoRestrict.jar').read_bytes()).hexdigest() if runtime else candidate_sha
        result['fixtureSha256']=hashlib.sha256((runtime.directory/'plugins/GeoRestrictLiveProbe.jar').read_bytes()).hexdigest() if runtime else probe_sha
        file.write_text(json.dumps(result,indent=2));case['testStatus']='passed' if result['pass'] else 'unsupported-offline-admission' if result.get('unsupported') else 'failed';case['testResult']=result
        persist(case,['testStatus','testResult'])
        print(json.dumps({'software':name,'minecraft':mc,'testStatus':case['testStatus'],'error':result.get('error')}),flush=True)


def build_bukkit(selected=None):
    report=json.loads(INVENTORY.read_text())
    directory=SMOKE/'buildtools'
    for case in report['cases']:
        latest_case=next(c for c in json.loads(INVENTORY.read_text())['cases'] if c['software']==case['software'] and c['minecraft']==case['minecraft'])
        case.update(latest_case)
        if case['software']!='spigot' or case['availability']=='unavailable':continue
        mc=case['minecraft']
        if selected and mc not in selected:continue
        outputs=[directory/f'{name}-{mc}.jar' for name in ['spigot','craftbukkit']]
        if all(p.exists() for p in outputs):continue
        print(json.dumps({'minecraft':mc,'action':'official-BuildTools'}),flush=True)
        env=dict(os.environ);java=java_for(mc)
        env['JAVA_HOME']=str(java.parent.parent);env['PATH']=str(java.parent)+os.pathsep+env['PATH']
        tool_home=MOD_SMOKE/'tool-home';(tool_home/'.m2').mkdir(parents=True,exist_ok=True)
        settings=tool_home/'.m2/settings.xml'
        if not settings.exists():settings.symlink_to('/workspace/georestrict-environment/settings.xml')
        env['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=2 -Duser.home='+str(tool_home)+' -Dmaven.repo.local=/workspace/georestrict-environment/m2 '+os.environ.get('GEO_LIVE_JVM_ARGS','')
        with (EVIDENCE/f'bukkit-{mc}-build.log').open('w') as output:
            run=subprocess.run([str(java),'-jar','BuildTools.jar','--rev',mc,'--compile','SPIGOT,CRAFTBUKKIT','--nogui'],cwd=directory,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=1800)
        print(json.dumps({'minecraft':mc,'exitCode':run.returncode,'artifacts':{p.name:p.exists() for p in outputs}}),flush=True)
        for entry in report['cases']:
            if entry['minecraft']==mc and entry['software'] in ['spigot','craftbukkit']:
                entry['buildStatus']='built' if run.returncode==0 and all(p.exists() for p in outputs) else 'failed'
                persist(entry,['buildStatus'])

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['inventory','mods','bukkit-build','plugins']);parser.add_argument('--versions',nargs='+');parser.add_argument('--software',nargs='+');parser.add_argument('--port',type=int)
    args=parser.parse_args();EVIDENCE.mkdir(parents=True,exist_ok=True);DOWNLOADS.mkdir(parents=True,exist_ok=True)
    if args.action not in ['inventory']:
        key='buildtools' if args.action=='bukkit-build' else str(args.port or (25594 if args.action=='mods' else 25596))
        runner_lock=(MOD_SMOKE/f'matrix-{key}.lock').open('w')
        try:fcntl.flock(runner_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError(f'A live matrix runner already owns {key}')
    if args.action=='inventory':cases=inventory();print(json.dumps({'publishedCases':sum(c['availability']!='unavailable' for c in cases),'upstreamGaps':sum(c['availability']=='unavailable' for c in cases)}))
    elif args.action=='mods':run_mods(args.versions,args.software,args.port or 25594)
    elif args.action=='plugins':run_plugins(args.versions,args.software,args.port or 25596)
    else:build_bukkit(args.versions)


if __name__=='__main__':main()

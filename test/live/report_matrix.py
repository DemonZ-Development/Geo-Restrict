#!/usr/bin/env python3
"""Export a compact, current-artifact compatibility report from live evidence."""
import hashlib
import json
import os
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from platforms import ROOT, SMOKE
from mods import MOD_SMOKE

HASH_CACHE=MOD_SMOKE/'evidence/report-hash-cache.json'
_hashes=json.loads(HASH_CACHE.read_text()) if HASH_CACHE.exists() else {}

def sha(path):
    info=path.stat()
    key=':'.join(str(value) for value in [info.st_dev,info.st_ino,info.st_size,info.st_mtime_ns,info.st_ctime_ns])
    if key not in _hashes:
        with path.open('rb') as stream:_hashes[key]=hashlib.file_digest(stream,'sha256').hexdigest()
    return _hashes[key]

def write_atomic(path,content):
    temporary=path.with_name(path.name+f'.{os.getpid()}.tmp')
    temporary.write_text(content)
    temporary.replace(path)

def main():
    source=json.loads((MOD_SMOKE/'evidence/version-inventory.json').read_text())
    artifacts={'plugin':ROOT/'target/georestrict-2.1.0.jar'}
    artifacts.update({name:ROOT/f'mods/{name}/target/georestrict-{name}-2.1.0.jar'
        for name in ['fabric','forge','neoforge']})
    hashes={name:sha(path) for name,path in artifacts.items()}
    fixtures={'mod':sha(MOD_SMOKE/'fixture/GeoRestrictLiveFixture.jar'),
        'plugin':sha(SMOKE/'probe/GeoRestrictLiveProbe.jar')}
    cases=[]
    for original in source['cases']:
        native=original['software'] in ['fabric','forge','neoforge']
        case={k:v for k,v in original.items() if k not in ['testResult']}
        result=original.get('testResult',{})
        file=MOD_SMOKE/'evidence'/f'{case["software"]}-{case["minecraft"]}{"-live" if native else "-plugin-live"}.json'
        if file.exists():
            try:result=json.loads(file.read_text())
            except json.JSONDecodeError:pass # A runner may still be writing this evidence file.
        if result:
            minimum=63 if native else 45 if case['software'] in ['bungee','waterfall','velocity'] else 56
            current=result.get('sha256')==hashes[case['software'] if native else 'plugin'] and result.get('fixtureSha256')==fixtures['mod' if native else 'plugin']
            checks_passed=all(check.get('pass') is True for check in result.get('checks',[]))
            protocol_gap=False
            if case['software'] in ['waterfall','velocity'] and case['minecraft']=='26.3':
                control_file=MOD_SMOKE/'evidence/diagnostics'/f'{case["software"]}-26.3-plugin-free-protocol-control.json'
                control_platform=SMOKE/'runtime'/f'{case["software"]}-protocol-control/platform.jar'
                tested_platform=SMOKE/'runtime'/f'{case["software"]}-26.3/platform.jar'
                if control_file.exists() and control_platform.exists() and tested_platform.exists():
                    try:control=json.loads(control_file.read_text())
                    except json.JSONDecodeError:control={}
                    protocol_gap=(not result.get('pass') and control.get('verified') is True and control.get('pluginFree') is True
                        and control.get('26.2',{}).get('joined') is True
                        and control.get('26.3',{}).get('joined') is False
                        and sha(control_platform)==sha(tested_platform))
                    if protocol_gap:case['protocolControl']=control
            if case['availability']!='unavailable':
                if not current:case['testStatus']='needs-current-artifact-rerun'
                elif protocol_gap:case['testStatus']='upstream-protocol-gap'
                elif not checks_passed:case['testStatus']='failed'
                elif result.get('unsupported'):case['testStatus']='unsupported-offline-admission'
                elif not result.get('pass'):case['testStatus']='failed'
                elif len(result.get('checks',[]))<minimum:case['testStatus']='needs-current-artifact-rerun'
                else:case['testStatus']='passed'
            case.update(checkCount=len(result.get('checks',[])),currentArtifacts=current,
                testedSha256=result.get('sha256'),fixtureSha256=result.get('fixtureSha256'),
                checks=[{'case':c['case'],'pass':c['pass']} for c in result.get('checks',[])])
            if file.exists():case['evidenceWrittenUTC']=datetime.fromtimestamp(file.stat().st_mtime,timezone.utc).isoformat(timespec='seconds')
            if result.get('error'):case['error']=result['error']
            if result.get('scope'):case['scope']=result['scope']
            if protocol_gap:
                case['error']='The published proxy artifact rejects Minecraft 26.3 (protocol 777) without GeoRestrict. Its plugin-free control admits Minecraft 26.2 (protocol 776).'
            if case['testStatus']=='passed':case.pop('error',None)
        case['testJava']=25 if case['software'] in ['bungee','waterfall','velocity'] else case['java']
        runtime=MOD_SMOKE/'runtime'/f'{case["software"]}-{case["minecraft"]}' if native else SMOKE/'runtime'/f'{case["software"]}-{case["minecraft"]}'
        platform=runtime/'platform.jar'
        if platform.exists():case['runtimeSha256']=sha(platform)
        if case['software'] in ['bungee','waterfall','velocity']:
            artifact=json.loads((SMOKE/'evidence'/f'{case["software"]}-artifact.json').read_text())
            case['runtimeArtifact']=Path(artifact['path']).name
        elif case['software'] in ['spigot','craftbukkit'] and platform.exists():
            revision=json.loads((MOD_SMOKE/'evidence'/f'spigot-{case["minecraft"]}.json').read_text())
            case['buildToolsRevision']=revision['refs']
        world_fixture=MOD_SMOKE/'evidence/diagnostics'/f'{case["software"]}-{case["minecraft"]}-world-fixture.json'
        if world_fixture.exists():case['worldFixture']=json.loads(world_fixture.read_text())
        cases.append(case)
    counts=Counter(c['testStatus'] for c in cases)
    components=[]
    for name,minimum in [('services',62),('velocity-modern',6),('geyser-floodgate',7),('paper-admission',21)]:
        file=SMOKE/'evidence'/f'{name}-live.json'
        try:result=json.loads(file.read_text()) if file.exists() else {}
        except json.JSONDecodeError:result={}
        if name=='services':
            sources=result.get('sourceSha256',{})
            current=bool(sources) and all(sha(ROOT/path)==digest for path,digest in sources.items())
        else:
            runtimes=result.get('artifacts',{})
            current=bool(runtimes) and all(value.get('plugin')==hashes['plugin'] for value in runtimes.values())
        verified=(current and result.get('pass') and len(result.get('checks',[]))>=minimum
            and all(check.get('pass') is True for check in result.get('checks',[])))
        components.append({'suite':name,'pass':bool(verified),'currentArtifacts':current,
            'checkCount':len(result.get('checks',[])),'generatedUTC':result.get('generatedUTC'),
            'artifacts':result.get('artifacts',{}),'sourceSha256':result.get('sourceSha256',{}),
            'checks':[{'case':check['case'],'pass':check['pass']} for check in result.get('checks',[])]})
    generated=datetime.now(timezone.utc).isoformat(timespec='seconds')
    summary={'generatedUTC':generated,'minecraftReleases':source['minecraftReleases'],
        'artifactSha256':hashes,'fixtureSha256':fixtures,'statusCounts':dict(counts),'cases':cases,
        'componentTests':components}
    output=ROOT/'test/live/version-results-2026-10-10.json'
    write_atomic(output,json.dumps(summary,indent=2)+'\n')
    names=['fabric','forge','neoforge','paper','purpur','folia','spigot','craftbukkit','bungee','waterfall','velocity']
    remaining=counts['pending']+counts['needs-current-artifact-rerun']+counts['failed']+sum(not item['pass'] for item in components)
    progress='**Validation is still in progress.**' if remaining else '**Every available combination has been attempted.**'
    lines=['# Minecraft 1.20–26.3 live compatibility report','',
        f'Generated **{generated}**. These are unreleased v2.1.0 candidates.', '',
        progress+' Every successful cell requires the current release artifact and test companion hashes and the complete applicable suite. Previous artifact runs cannot certify the current candidate.', '',
        f'Current status: {json.dumps(dict(counts),sort_keys=True)}.', '',
        'The requested “256.3” upper bound is interpreted as **26.3**, the newest stable release in the official Mojang manifest. The range contains 24 stable releases and 264 software/version combinations.', '',
        '| Minecraft | '+' | '.join(names)+' |',
        '| --- | '+' | '.join(['---']*len(names))+' |']
    labels={'passed':'PASS','pending':'Pending','failed':'FAIL','upstream-gap':'Unavailable',
        'upstream-alias':'Alias','needs-current-artifact-rerun':'Rerun',
        'unsupported-offline-admission':'Local only','upstream-protocol-gap':'Protocol gap'}
    for mc in source['minecraftReleases']:
        cells=[]
        for name in names:
            case=next(c for c in cases if c['minecraft']==mc and c['software']==name)
            label=labels.get(case['testStatus'],case['testStatus'])
            if case['testStatus']=='passed':
                label+=f' ({case["checkCount"]})'
                if case.get('worldFixture'):label+='*'
            cells.append(label)
        lines.append('| '+mc+' | '+' | '.join(cells)+' |')
    lines+=['', '## What the live suites exercise', '',
        'Real stock dedicated-server/loader processes accept TCP clients into PLAY or send the expected denial. Native mod suites require 63 checks; Bukkit server suites require 56; proxy suites require 45. Tests cover local IPv4/IPv6, LOCAL country rules, public country and ASN rules and allowlists, VPN keywords, reloads, external API consumers, cache persistence across restart, fail-open/fail-closed lookup recovery, concurrent joins, HTTP delay without stalled status/commands, masked webhook output and schema migration. Native suites also check automatic config-file reloads, Minecraft command permissions and datapack reloads; Bukkit suites check operators, explicit permission attachments and vanilla ban/whitelist precedence.', '',
        'All listeners are isolated on loopback. Public IPs are synthetic trusted forwarding metadata or test-only companion modifications; they are not real remote network clients. The mods never trust arbitrary client hostname metadata in production. Fixtures are separate artifacts, excluded from normal release builds.', '',
        'Minecraft 1.20–1.20.4 uses Java 17; 1.20.5–1.21.11 uses Java 21; 26.x uses Java 25. Proxies use their own required runtime. Exact loader builds, beta flags, server builds, hashes, individual check names and failures are recorded in [the machine-readable matrix](test/live/version-results-2026-10-10.json). Full raw logs and wire definitions remain in ignored `.smoke/mods/evidence/` and runtime directories.', '',
        'The Minecraft 1.20 and 1.20.1 fixtures use one Netty I/O loop to avoid a vanilla shared-random-generator race during simultaneous handshakes. Sixteen real clients and asynchronous lookup workers are still exercised. Protocol clients for 1.21.2 and 1.21.7 use the identical wire definitions from 1.21.3 and 1.21.8 respectively; the server under test remains the exact requested release. Minimal raw clients verify PLAY admission for 26.2 and 26.3.', '',
        'Folia builds that fail during fresh flat-world spawn initialization are checked with plugin-free controls. Where those controls reproduce the cross-region chunk thread failure, admission reruns use pre-generated Paper worlds from the exact same Minecraft release. Each affected row records its fixture provenance; original worlds and control logs are retained. Those rows do not certify fresh-world generation.', '',
        'An asterisk marks admission tested with a world fixture. **Folia 1.21.8 remains limited to existing-world admission:** its GeoRestrict runtime repeatedly failed during initial spawn loading, but two plugin-free controls stayed healthy. The cause is unresolved. A successful admission rerun with a Paper-generated world does not resolve that startup failure. Initial Bungee status timeouts and successful unchanged-artifact reruns are also retained in internal diagnostics.', '',
        '## Publisher gaps and revision aliases', '',
        'Unavailable combinations have no published exact-release artifact. BuildTools aliases resolve to another Minecraft patch and are never relabelled as the requested version. Proxy release names do not guarantee client protocol support; admission must be verified with the matching backend.', '']
    for case in cases:
        if case['availability']=='unavailable':lines.append(f'- **{case["software"]} {case["minecraft"]}:** {case["reason"]}.')
    limitations=[c for c in cases if c['testStatus'] in ['failed','unsupported-offline-admission','upstream-protocol-gap']]
    if limitations:
        lines+=['','## Admission limits and failures','']
        for case in limitations:lines.append(f'- **{case["software"]} {case["minecraft"]}:** {case.get("error",case.get("reason","See individual checks"))}.')
    lines+=['','## Additional current component checks','','| Suite | Checks | Current result |','| --- | --- | --- |']
    for item in components:
        lines.append(f'| {item["suite"]} | {item["checkCount"]} | {"PASS" if item["pass"] else "Awaiting verification"} |')
    lines+=['','Gateway checks use real MMDB data and local provider fixtures. Supplemental plugin suites record the actual candidate and server hashes. Addon checks cover RakNet advertising, Java admission, API detection and webhook capture; they do not certify an authenticated Bedrock session.', '','## Limits', '',
        'This matrix does not test Microsoft account authentication, GUI/rendered gameplay, arbitrary other mods or plugin packs, or a deployed production server. Older CraftBukkit offline mode may skip asynchronous pre-login events: local blocking is independently enforced without HTTP, but missing public-address admission hooks must not be counted as a full-suite pass.', '',
        'Build instructions: [mods/README.md](mods/README.md). Reproduction: [test/live/README.md](test/live/README.md).', '',
        '## Current candidate hashes', '', '| Artifact | SHA-256 |', '| --- | --- |']
    for name,path in artifacts.items():lines.append(f'| `{path.relative_to(ROOT)}` | `{hashes[name]}` |')
    write_atomic(ROOT/'MOD_LIVE_TEST_REPORT.md','\n'.join(lines)+'\n')
    write_atomic(HASH_CACHE,json.dumps(_hashes)+'\n')
    print(json.dumps({'generatedUTC':generated,'statusCounts':dict(counts),'path':str(output)}))

if __name__=='__main__':main()

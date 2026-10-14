#!/usr/bin/env python3
"""Run complete native admission checks on official Quilt dedicated servers."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
from mods import ModRuntime, MOD_SMOKE, ROOT, pilot, run_cases, native_extras
from version_matrix import java_for


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--versions', nargs='+', default=['1.20.1', '1.21.1', '1.21.11', '26.3'])
    parser.add_argument('--loader', default='0.30.0')
    parser.add_argument('--port', type=int, default=25610)
    args = parser.parse_args()
    evidence = ROOT/'.smoke/expansion/evidence';evidence.mkdir(parents=True, exist_ok=True)
    for mc in args.versions:
        runtime = ModRuntime('quilt', mc, args.port)
        result = {'software': 'quilt', 'minecraft': mc, 'loader': args.loader, 'pass': False,
                  'sha256': hashlib.sha256((runtime.directory/'mods/GeoRestrict.jar').read_bytes()).hexdigest(),
                  'fixtureSha256': hashlib.sha256((runtime.directory/'mods/GeoRestrictLiveFixture.jar').read_bytes()).hexdigest()}
        try:
            marker = runtime.directory/'quilt-installed.json'
            if not marker.exists():
                vanilla = MOD_SMOKE/'runtime'/f'fabric-{mc}/.fabric/server/{mc}-server.jar'
                if not (runtime.directory/'server.jar').exists():os.link(vanilla, runtime.directory/'server.jar')
                libraries = runtime.directory/'libraries'
                if not libraries.exists():libraries.symlink_to((MOD_SMOKE/'libraries').resolve(), target_is_directory=True)
                with (evidence/f'quilt-{mc}-install.log').open('w') as output:
                    installed = subprocess.run([str(java_for(mc)), '-Dhttp.agent=Apache-Maven/3.9.9',
                        *os.environ.get('GEO_LIVE_JVM_ARGS', '').split(), '-jar',
                        str(ROOT/'.smoke/expansion/quilt-installer.jar'), 'install', 'server', mc,
                        args.loader, '--install-dir='+str(runtime.directory)],
                        stdout=output, stderr=subprocess.STDOUT, timeout=300)
                if installed.returncode:raise RuntimeError('Quilt installation failed; see install log')
                launcher = runtime.directory/'quilt-server-launch.jar'
                shutil.copy2(launcher, runtime.directory/'platform.jar')
                marker.write_text(json.dumps({'minecraft': mc, 'loader': args.loader,
                    'launcherSha256': hashlib.sha256(launcher.read_bytes()).hexdigest()}))
            runtime.start()
            result['checks'] = pilot(runtime)
            result['checks'] += run_cases(runtime)
            result['checks'] += native_extras(runtime)
            result['pass'] = len(result['checks']) >= 63 and all(c['pass'] for c in result['checks'])
        except Exception as error:
            result['error'] = str(error)
            current = getattr(runtime, 'current_checks', [])
            if current is not result.get('checks'):result.setdefault('checks', []).extend(current)
        finally:
            runtime.stop();runtime.runtime_lock.close()
            (evidence/f'quilt-{mc}-live.json').write_text(json.dumps(result, indent=2)+'\n')
            print(json.dumps({'software': 'quilt', 'minecraft': mc, 'pass': result['pass'],
                              'checks': len(result.get('checks', [])), 'error': result.get('error')}), flush=True)


if __name__ == '__main__':main()

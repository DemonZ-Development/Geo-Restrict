#!/usr/bin/env python3
"""Export committed and new source files without Git metadata or runtime caches."""
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.smoke', 'target', 'node_modules', '.wrangler', '.aws',
            '.codex', 'secrets', '__pycache__', 'dist', 'release-input'}


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root)


def export_source(root, output, version, tracked_only=False):
    args = ['ls-files', '--cached', '-z']
    if not tracked_only:
        args += ['--others', '--exclude-standard']
    paths = sorted(set(name.decode() for name in git(root, *args).split(b'\0') if name))
    files = {}
    for name in paths:
        path = root / name
        if set(Path(name).parts) & EXCLUDED or path == output or not path.exists():
            continue
        if name.endswith(('.env', '.pem', '.key', '.p12', '.pfx')):
            raise ValueError(f'Review private-file candidate before exporting: {name}')
        if path.is_symlink():
            raise ValueError(f'Review symlink before exporting: {name}')
        if path.is_file():
            files[name] = path.read_bytes()
    manifest = {'project': 'Geo-Restrict', 'version': version,
                'createdUTC': datetime.now(timezone.utc).isoformat(timespec='seconds'),
                'baseCommit': git(root, 'rev-parse', 'HEAD').decode().strip(),
                'trackedFilesOnly': tracked_only, 'fileCount': len(files),
                'deletedTrackedPaths': [name.decode() for name in git(root, 'ls-files', '--deleted', '-z').split(b'\0') if name],
                'sha256': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    instructions = '''# Sync with your local Git checkout

This snapshot includes current source, adapters, tests, documentation, CI and
lockfiles. It excludes Git metadata, builds, downloaded servers, worlds,
dependency caches and private credentials. Live compatibility is described in
the included reports; a source snapshot is not certification of every platform.

1. Commit or stash your local changes.
2. Copy the contents of Geo-Restrict/ into your existing checkout, including
   .github/ and .gitignore. Preserve your local .git/ directory.
3. Review git status and git diff, then stage and commit your changes.

SOURCE_SNAPSHOT.json records the base commit, checksums and deleted tracked
paths. Apply listed deletions manually when syncing from that base; keep
unrelated local work. Test runtimes/client dependencies must be prepared
separately according to test/live/README.md.
'''
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            entry = zipfile.ZipInfo('Geo-Restrict/' + name)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if (root / name).stat().st_mode & 0o111 else 0o100644) << 16
            archive.writestr(entry, data)
        archive.writestr('SOURCE_SNAPSHOT.json', json.dumps(manifest, indent=2) + '\n')
        archive.writestr('SYNC_WITH_LOCAL_GIT.md', instructions)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError('Corrupt source archive')
        for name, expected in manifest['sha256'].items():
            if hashlib.sha256(archive.read('Geo-Restrict/' + name)).hexdigest() != expected:
                raise ValueError(f'Archive checksum mismatch: {name}')
    for name, data in files.items():
        if (root / name).read_bytes() != data:
            raise ValueError(f'Source changed during export; repeat it: {name}')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tracked-only', action='store_true')
    args = parser.parse_args()
    version = ET.parse(ROOT / 'pom.xml').getroot().find('{*}version').text
    manifest = export_source(ROOT, args.output.resolve(), version, args.tracked_only)
    checksum = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.output.with_suffix(args.output.suffix + '.sha256').write_text(checksum + '  ' + args.output.name + '\n')
    print(f'{args.output}: {manifest["fileCount"]} source files; SHA-256 {checksum}')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Validate versions and assemble GitHub Release assets; never publish locally."""
import argparse
import hashlib
import json
import re
import shutil
import tomllib
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from source_snapshot import export_source

ROOT = Path(__file__).resolve().parents[1]
LOADERS = ('fabric', 'forge', 'neoforge', 'sponge', 'geyser', 'standalone')
TAG = re.compile(r'v((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')


def validate_version(root, tag=''):
    version = ET.parse(root / 'pom.xml').getroot().find('{*}version').text
    if not re.fullmatch(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)', version):
        raise ValueError('Use a numeric major.minor.patch source version.')
    versions = {}
    for path in [root / 'test/pom.xml', *sorted((root / 'mods').rglob('pom.xml')),
                 root / 'test/live/mod-fixture/pom.xml']:
        if 'target' in path.parts:
            continue
        element = ET.parse(path).getroot()
        declared = element.find('{*}version')
        if declared is None:
            declared = element.find('{*}parent/{*}version')
        versions[str(path.relative_to(root))] = declared.text if declared is not None else None
    for directory in ['worker', 'vps-gateway']:
        package = json.loads((root / directory / 'package.json').read_text())
        lock = json.loads((root / directory / 'package-lock.json').read_text())
        versions[directory + '/package.json'] = package['version']
        versions[directory + '/package-lock.json'] = lock['version']
        versions[directory + '/package-lock.json root package'] = lock['packages']['']['version']
    versions['adapters/pumpkin/Cargo.toml'] = tomllib.loads((root / 'adapters/pumpkin/Cargo.toml').read_text())['package']['version']
    lock = tomllib.loads((root / 'adapters/pumpkin/Cargo.lock').read_text())
    versions['adapters/pumpkin/Cargo.lock'] = next(p['version'] for p in lock['package'] if p['name'] == 'georestrict-pumpkin')
    for name in ['plugin.yml', 'bungee.yml']:
        text = (root / 'src/main/resources' / name).read_text()
        versions[name] = re.search(r'^version:\s*["\']?([^\s"\']+)', text, re.M).group(1)
    versions['velocity-plugin.json'] = json.loads((root / 'src/main/resources/velocity-plugin.json').read_text())['version']
    text = (root / 'src/main/java/zip/linuxaddict/georestrict/PluginInfo.java').read_text()
    versions['PluginInfo.VERSION'] = re.search(r'VERSION\s*=\s*"([^"]+)"', text).group(1)
    mismatches = [f'{name}: {value}' for name, value in versions.items() if value != version]
    if mismatches:
        raise ValueError('Release version mismatch: ' + '; '.join(mismatches))
    if tag:
        match = TAG.fullmatch(tag)
        if not match or match.group(1) != version:
            raise ValueError(f'Tag must be v{version} or a SemVer prerelease of v{version}.')
        if match.group(2) and any(part.isdigit() and len(part) > 1 and part[0] == '0' for part in match.group(2).split('.')):
            raise ValueError('Numeric prerelease identifiers cannot have leading zeroes.')
    return version


def pumpkin_notices(root, metadata, output):
    resolved = {node['id'] for node in metadata['resolve']['nodes']}
    files = {}
    catalog = ['# Locked Pumpkin dependency notices', '',
               'Includes build dependencies. Corresponding crate sources/checksums are in Cargo.lock.',
               'MIT alternatives are used for wasm-tools and wit-bindgen where offered.', '',
               '| Crate | Version | License | Source |', '|---|---|---|---|']
    for package in sorted(metadata['packages'], key=lambda p: p['name']):
        if package['id'] not in resolved or package['id'] == metadata['resolve']['root']:
            continue
        name, version = package['name'], package['version']
        if not (package.get('source') or '').startswith('registry+') or not package.get('license'):
            raise ValueError('Uncatalogued dependency: ' + name)
        folder = Path(package['manifest_path']).parent
        licenses = {p.name: p.read_bytes() for p in folder.iterdir() if p.is_file()
                    and any(word in p.name.upper() for word in ('LICENSE', 'COPYING', 'COPYRIGHT', 'NOTICE'))}
        if package.get('license_file'):
            path = folder / package['license_file']
            licenses[path.name] = path.read_bytes()
        if not licenses:
            filename = ('WAKI-APACHE-2.0' if name in ('waki', 'waki-macros')
                        else 'WIT-BINDGEN-MIT' if name.startswith('wit-bindgen')
                        else 'WASM-TOOLS-MIT' if name in ('wasm-encoder', 'wasm-metadata', 'wasmparser', 'wit-component', 'wit-parser') else None)
            if filename is None:
                raise ValueError('Missing original dependency license: ' + name)
            licenses[filename] = (root / 'adapters/pumpkin/licenses' / filename).read_bytes()
        for filename, data in licenses.items():
            files[f'third-party/{name}-{version}/{filename}'] = data
        catalog.append(f'| {name} | {version} | {package["license"]} | [crate](https://crates.io/api/v1/crates/{name}/{version}/download) |')
    files['THIRD_PARTY.md'] = ('\n'.join(catalog) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)


def collect_artifacts(directory, version):
    expected = [f'georestrict-{version}.jar', *[f'georestrict-{loader}-{version}.jar' for loader in LOADERS], 'georestrict_pumpkin.wasm', 'pumpkin-notices.zip']
    files = {}
    for name in expected:
        matches = list(directory.rglob(name))
        if len(matches) != 1 or not matches[0].is_file():
            raise ValueError(f'Expected exactly one {name}, found {len(matches)}.')
        path = matches[0]
        if name.endswith('.jar'):
            with zipfile.ZipFile(path) as jar:
                if jar.testzip() is not None:
                    raise ValueError('Corrupt release JAR: ' + name)
                if any(n.startswith('zip/linuxaddict/georestrict/live/') for n in jar.namelist()):
                    raise ValueError('Live fixture leaked into release JAR: ' + name)
        elif name.endswith('.wasm'):
            if path.read_bytes()[:8] != b'\x00asm\x0d\x00\x01\x00':
                raise ValueError('Expected a WASI component, not a core module.')
        else:
            with zipfile.ZipFile(path) as notices:
                if notices.testzip() is not None or 'THIRD_PARTY.md' not in notices.namelist():
                    raise ValueError('Incomplete Pumpkin notices archive.')
        destination = f'georestrict-pumpkin-{version}.wasm' if name.endswith('.wasm') else name
        files[destination] = path
    return files


def bundle(root, tag, directory, output):
    version = validate_version(root, tag)
    files = collect_artifacts(directory, version)
    if output.exists() and any(output.iterdir()):
        raise ValueError('Use an empty release output directory to avoid stale assets.')
    output.mkdir(parents=True, exist_ok=True)
    for name, path in files.items():
        shutil.copy2(path, output / name)
    for name in ['LICENSE', 'CHANGELOG.md']:
        shutil.copy2(root / name, output / name)
    for name in ['PUMPKIN_LIVE_TEST_REPORT.md', 'MOD_LIVE_TEST_REPORT.md']:
        if (root / name).exists():
            shutil.copy2(root / name, output / name)
    export_source(root, output / f'georestrict-{version}-full-source.zip', version, tracked_only=True)
    for directory_name in ['worker', 'vps-gateway']:
        with zipfile.ZipFile(output / f'georestrict-{directory_name}-{version}-source.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted((root / directory_name).rglob('*')):
                if path.is_file() and not set(path.relative_to(root).parts) & {'node_modules', '.wrangler', 'data', 'secrets', 'target'} and not path.name.endswith(('.env', '.log')):
                    archive.write(path, str(path.relative_to(root)))
            archive.write(root / 'LICENSE', 'LICENSE')
    matrix = json.loads((root / 'test/live/version-results-2026-10-10.json').read_text())
    native = json.loads((root / 'test/live/pumpkin-results-2026-10-10.json').read_text())
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()}
    matches = {name: hashes[f'georestrict-{name + "-" if name != "plugin" else ""}{version}.jar'] == matrix['artifactSha256'].get(name)
               for name in ['plugin', 'fabric', 'forge', 'neoforge']}
    matches['pumpkin'] = hashes[f'georestrict-pumpkin-{version}.wasm'] == native.get('artifactSha256') and native.get('pluginVersion') == version and native.get('pass') is True
    counts = matrix.get('statusCounts', {})
    complete = (len(matrix.get('cases', [])) >= 264 and sum(counts.values()) == len(matrix['cases'])
                and not any(counts.get(s, 0) for s in ('pending', 'failed', 'needs-current-artifact-rerun'))
                and len(matrix.get('componentTests', [])) == 4 and all(c.get('pass') for c in matrix['componentTests']) and all(matches.values()))
    prerelease = bool(TAG.fullmatch(tag).group(2)) or not complete
    manifest = {'tag': tag, 'version': version, 'prerelease': prerelease,
                'liveEvidenceCompleteAndMatching': complete, 'liveTestedArtifactMatches': matches,
                'matrixStatusCounts': counts, 'artifactSha256': hashes}
    (output / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    sums = ''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in sorted(output.iterdir()) if path.is_file())
    (output / 'SHA256SUMS').write_text(sums)
    notes = f'''GeoRestrict {tag} includes the Java plugin, Fabric/Forge/NeoForge/Sponge adapters, Geyser extension, standalone gateway, and native Pumpkin WASI component.

Install only the artifact for your server. Pumpkin uses its own component and native Java/Bedrock admission; it does not require a Java bridge. Geyser standalone's extension controls local admission; geographic rules run in the Java backend/proxy. Local blocking is opt-in and needs trusted player addresses on proxied backends.

Full source, gateway source archives, dependency notices and SHA256SUMS are attached. CI builds the adapters and runs Java unit/integration, Rust rule-engine and gateway tests. It does not repeat every live server matrix cell or authenticated Microsoft/Xbox session.

Read the attached compatibility reports for exact versions and limits. Their live evidence applies to the recorded artifact hashes, not automatically to every CI rebuild. `release-manifest.json` shows which assets match those hashes.
'''
    if not complete:
        notes += '\nThis release is marked as a prerelease because complete live evidence matching the rebuilt artifacts is not available.\n'
    (output / 'RELEASE_NOTES.md').write_text(notes)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    version = commands.add_parser('version')
    version.add_argument('--tag', default='')
    version.add_argument('--github-output', type=Path)
    notices = commands.add_parser('pumpkin-notices')
    notices.add_argument('--metadata', type=Path, required=True)
    notices.add_argument('--output', type=Path, required=True)
    package = commands.add_parser('bundle')
    package.add_argument('--tag', required=True)
    package.add_argument('--artifacts', type=Path, required=True)
    package.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'version':
            value = validate_version(ROOT, args.tag)
            if args.github_output:
                with args.github_output.open('a') as file:
                    file.write(f'version={value}\n')
            print(value)
        elif args.command == 'pumpkin-notices':
            pumpkin_notices(ROOT, json.loads(args.metadata.read_text()), args.output)
        else:
            manifest = bundle(ROOT, args.tag, args.artifacts, args.output)
            print(json.dumps(manifest, indent=2))
    except (ValueError, KeyError, StopIteration, OSError) as error:
        parser.exit(1, f'Release validation failed: {error}\n')


if __name__ == '__main__':
    main()

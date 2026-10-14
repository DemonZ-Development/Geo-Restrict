#!/usr/bin/env python3
"""Package only the native component certified by the Pumpkin evidence."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from report_pumpkin import ROOT, COMPONENT, REPORT_JSON, REPORT_MD, digest, main as report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path,
                        default=ROOT / '.smoke/expansion/pumpkin-cargo-metadata.json',
                        help='cargo metadata --locked --filter-platform wasm32-wasip2 --format-version 1 output')
    args = parser.parse_args()
    report()
    evidence = json.loads(REPORT_JSON.read_text())
    metadata = json.loads(args.metadata.read_text())
    root_package = next(p for p in metadata['packages'] if p['id'] == metadata['resolve']['root'])
    if root_package['version'] != evidence['pluginVersion']:
        raise SystemExit('Regenerate Cargo metadata for the current release.')
    version = evidence['pluginVersion']
    component_name = f'georestrict-pumpkin-{version}.wasm'
    guide = (ROOT / 'adapters/pumpkin/README.md').read_text()
    files = {component_name: COMPONENT.read_bytes(), 'LICENSE': (ROOT / 'LICENSE').read_bytes(),
             'README.md': guide.encode(),
             'test/live/pumpkin-results-2026-10-10.json': REPORT_JSON.read_bytes()}
    if REPORT_MD.exists():
        files['PUMPKIN_LIVE_TEST_REPORT.md'] = REPORT_MD.read_text().replace('adapters/pumpkin/README.md', 'README.md').encode()
    for path, expected in evidence['sourceSha256'].items():
        source = ROOT / path
        if digest(source) != expected:
            raise SystemExit(f'Source changed after the native report: {path}')
        files['source/' + path] = source.read_bytes()
    files['source/LICENSE'] = (ROOT / 'LICENSE').read_bytes()
    files['source/adapters/pumpkin/README.md'] = guide.encode()
    resolved = {node['id'] for node in metadata['resolve']['nodes']}
    notices = ['# Third-party dependency notices', '',
               'The locked Rust dependency graph is listed below, including build dependencies.',
               'Original license/copyright texts are included under `third-party/`.',
               'Exact source packages and checksums are recorded in the included Cargo.lock.',
               'Registry source URLs retrieve the corresponding published crate sources.', '',
               '| Crate | Version | License | Source |', '|---|---|---|---|']
    supplemental = ROOT / 'adapters/pumpkin/licenses'
    for package in sorted(metadata['packages'], key=lambda p: p['name']):
        if package['id'] not in resolved or package['id'] == root_package['id']:
            continue
        name, crate_version = package['name'], package['version']
        if not package.get('source', '').startswith('registry+') or not package.get('license'):
            raise SystemExit(f'Uncatalogued dependency: {name}')
        folder = Path(package['manifest_path']).parent
        licenses = {p.name: p.read_bytes() for p in folder.iterdir()
                    if p.is_file() and any(word in p.name.upper()
                        for word in ('LICENSE', 'COPYING', 'COPYRIGHT', 'NOTICE'))}
        if package.get('license_file'):
            path = folder / package['license_file']
            licenses[path.name] = path.read_bytes()
        if not licenses:
            filename = ('WAKI-APACHE-2.0' if name in ('waki', 'waki-macros')
                        else 'WIT-BINDGEN-MIT' if name.startswith('wit-bindgen')
                        else 'WASM-TOOLS-MIT' if name in ('wasm-encoder', 'wasm-metadata',
                            'wasmparser', 'wit-component', 'wit-parser') else None)
            if filename is None:
                raise SystemExit(f'Missing original dependency notices: {name}')
            licenses[filename] = (supplemental / filename).read_bytes()
        for filename, data in licenses.items():
            files[f'third-party/{name}-{crate_version}/{filename}'] = data
        url = f'https://crates.io/api/v1/crates/{name}/{crate_version}/download'
        notices.append(f'| {name} | {crate_version} | {package["license"]} | [registry]({url}) |')
    files['THIRD_PARTY.md'] = ('\n'.join(notices) + '\n').encode()
    files['SHA256SUMS'] = ''.join(
        f'{hashlib.sha256(data).hexdigest()}  {name}\n'
        for name, data in sorted(files.items())).encode()
    output_dir = ROOT / 'target/releases'
    output_dir.mkdir(parents=True, exist_ok=True)
    component_output = output_dir / component_name
    shutil.copy2(COMPONENT, component_output)
    output = output_dir / f'georestrict-pumpkin-{version}-candidate.zip'
    prefix = f'georestrict-pumpkin-{version}/'
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(prefix + name, date_time=(2026, 10, 10, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None, 'Corrupt native archive'
        assert archive.read(prefix + component_name) == COMPONENT.read_bytes()
    print(output)
    print('sha256:', digest(output))


if __name__ == '__main__':
    main()

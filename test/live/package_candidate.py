#!/usr/bin/env python3
"""Bundle the already tested candidates after the live matrix is finalized."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'georestrict-2.1.0-candidate/'


def main():
    report = json.loads((ROOT / 'test/live/version-results-2026-10-10.json').read_text())
    unfinished = ['pending', 'failed', 'needs-current-artifact-rerun']
    if any(report['statusCounts'].get(status, 0) for status in unfinished):
        raise SystemExit('Finish the live matrix before packaging this candidate.')
    if len(report.get('componentTests', [])) != 4 or not all(item['pass'] for item in report['componentTests']):
        raise SystemExit('Finish the current component checks before packaging this candidate.')
    artifacts = {'plugin': ('target/georestrict-2.1.0.jar', 'plugin/georestrict-2.1.0.jar')}
    for loader in ['fabric', 'forge', 'neoforge']:
        filename = f'georestrict-{loader}-2.1.0.jar'
        artifacts[loader] = (f'mods/{loader}/target/{filename}', f'mods/{filename}')
    files = {}
    sums = []
    for name, (source, destination) in artifacts.items():
        data = (ROOT / source).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest != report['artifactSha256'][name]:
            raise SystemExit(f'{name} differs from the live-tested artifact.')
        files[destination] = data
        sums.append(f'{digest}  {destination}')
    for source in ['LICENSE', 'CHANGELOG.md',
                   'mods/README.md', 'test/live/README.md',
                   'test/live/version-results-2026-10-10.json']:
        files[source] = (ROOT / source).read_bytes()
    if (ROOT / 'MOD_LIVE_TEST_REPORT.md').exists():
        files['MOD_LIVE_TEST_REPORT.md'] = (ROOT / 'MOD_LIVE_TEST_REPORT.md').read_bytes()
    files['docs/native-mods.md'] = (ROOT / 'mods/README.md').read_bytes()
    for source in (ROOT / 'docs').rglob('*'):
        if source.is_file() and 'node_modules' not in source.parts:
            files[str(source.relative_to(ROOT))] = source.read_bytes()
    files['SHA256SUMS'] = ('\n'.join(sums) + '\n').encode()
    files['README.md'] = b'''# GeoRestrict v2.1.0 candidate

This is an unreleased, live-tested candidate. Choose the jar for your server:

- Bukkit/Paper/Purpur/Folia/Spigot/CraftBukkit/BungeeCord/Waterfall/Velocity:
  put `plugin/georestrict-2.1.0.jar` in the server's `plugins/` directory.
- Fabric, Forge or NeoForge: put the matching jar from `mods/` in `mods/`.
  Clients do not need GeoRestrict; Fabric API is not required.

Restart the server. Plugin configuration lives under its plugin data directory;
mod configuration lives at `config/georestrict/config.yml`.

Local blocking is off by default. To enable it, set:

```yaml
blockLocalConnections: true
```

An existing country BLOCKLIST containing `LOCAL` also rejects local players.
No fixed player IP list is needed. In a proxy network, apply blocking at the
public-facing proxy or configure trusted player IP forwarding on the backend.

Read `test/live/version-results-2026-10-10.json` for exact verified releases, loader builds,
upstream gaps and admission limits. The tests use isolated servers, offline
protocol clients and forwarding fixtures; production accounts and arbitrary
other mod/plugin packs are outside their scope.

Folia 1.21.8 has an unresolved initial-world-spawn failure; its admission suite
passed with an existing Paper-generated world. Four Folia rows use world
fixtures and do not certify fresh-world generation. See the diagnostic notes.

Installation/configuration pages are in `docs/`; native details are in
`docs/native-mods.md`. Build instructions in those guides refer to the project
source checkout. SHA256SUMS identifies the four tested release jars. Test
companions, server binaries, caches, credentials and raw runtime logs are not
included in this bundle. Nothing has been published or deployed.
'''
    output = ROOT / 'target/releases/georestrict-2.1.0-candidate.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(PREFIX + name, date_time=(2026, 10, 10, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None, 'Corrupt archive'
    print(output)
    print('sha256:', hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()

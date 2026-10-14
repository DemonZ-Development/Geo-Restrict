#!/usr/bin/env python3
"""Freeze native-only coverage without borrowing Java-platform results."""
import hashlib
import json
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / '.smoke/expansion'
COMPONENT = BASE / 'pumpkin-target/wasm32-wasip2/release/georestrict_pumpkin.wasm'
REPORT_JSON = ROOT / 'test/live/pumpkin-results-2026-10-10.json'
REPORT_MD = ROOT / 'PUMPKIN_LIVE_TEST_REPORT.md'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = tomllib.loads((ROOT / 'adapters/pumpkin/Cargo.toml').read_text())
    version = manifest['package']['version']
    component_hash = digest(COMPONENT)
    sources = [ROOT / 'adapters/pumpkin/Cargo.toml', ROOT / 'adapters/pumpkin/Cargo.lock']
    sources += sorted((ROOT / 'adapters/pumpkin/src').glob('*'))
    if any(p.stat().st_mtime > COMPONENT.stat().st_mtime for p in sources):
        raise SystemExit('Rebuild and live-test the component after source changes.')
    suites = []
    for name, minimum in [('live', 23), ('components', 43), ('lifecycle', 7)]:
        suite = json.loads((BASE / f'evidence/pumpkin-native-{name}.json').read_text())
        if (suite.get('sha256') != component_hash or suite.get('pluginVersion') != version
                or not suite.get('pass') or len(suite.get('checks', [])) < minimum
                or not all(check['pass'] for check in suite['checks'])):
            raise SystemExit(f'Complete {name} against the current component before reporting.')
        suite.pop('log', None)
        suite['suite'] = name
        suites.append(suite)
    unit_log = BASE / 'pumpkin-unit-native.log'
    units = re.findall(r'^test (policy::tests::\S+) \.\.\. ok$', unit_log.read_text(), re.M)
    if (len(units) != 6 or '6 passed; 0 failed' not in unit_log.read_text()
            or unit_log.stat().st_mtime < (ROOT / 'adapters/pumpkin/src/policy.rs').stat().st_mtime):
        raise SystemExit('Run the six native rule-engine tests on the current sources.')
    server = json.loads((BASE / 'pumpkin-release-evidence.json').read_text())
    if digest(BASE / 'pumpkin-X64-Linux') != server['sha256']:
        raise SystemExit('Pumpkin runtime differs from the recorded official release.')
    result = {'pluginVersion': version, 'native': True, 'javaBridgeRequired': False,
              'artifactSha256': component_hash, 'artifactBytes': COMPONENT.stat().st_size,
              'server': server, 'rust': (BASE / 'pumpkin-rust-version.txt').read_text().strip(),
              'target': 'wasm32-wasip2', 'javaProtocol': 777, 'bedrockProtocol': 2193,
              'bedrockVersion': '1.26.51', 'authenticatedAccountCoverage': False,
              'sourceSha256': {str(p.relative_to(ROOT)): digest(p) for p in sources},
              'unitTests': units, 'suites': suites,
              'liveAssertions': sum(len(suite['checks']) for suite in suites), 'pass': True}
    REPORT_JSON.write_text(json.dumps(result, indent=2) + '\n')
    text = f'''# Native Pumpkin live testing — GeoRestrict {version}

The self-contained Rust/WASI component passed **{result['liveAssertions']} live
assertions and six native rule-engine tests** on the official Linux Pumpkin
release `{server['tag']}`. No Java admission bridge was running. This report
certifies this component and server release; it does not certify other Pumpkin
releases, every Minecraft version, or the Java adapters.

- Component SHA-256: `{component_hash}`.
- Official server SHA-256: `{server['sha256']}` (matches published asset digest).
- Compiler: `{result['rust']}`; target: `wasm32-wasip2`.
- Java: 26.3, protocol 777, real clients reached PLAY.
- Native Bedrock: 1.26.51, protocol 2193, NetherNet/WebRTC clients reached START_GAME.
- Bedrock test client: bedrock-protocol 3.60.1, @roamhq/wrtc 0.10.0.

## Verified behavior

| Suite | Assertions | Coverage |
|---|---:|---|
| Direct Java and Bedrock admission | 23 | Component loading, native commands, opt-in local denial, LOCAL blocklist, reload, real Worker/fallback lookup, cache decisions, gateway recovery, discovery/status and Bedrock bypasses |
| Native components and forwarding | 43 | HTTP authentication/query encoding, IPv6, country/ASN/VPN rules, persistent cache across restart, HTTP errors/malformed and oversized responses, deadlines, webhooks, invalid configuration, operator bypass and signed forwarding |
| Startup and recovery | 7 | Generated defaults/Discord settings, default admission, invalid startup configuration denied without disabling the handler, status and recovery without restart |

Signed Velocity-format forwarding was tested directly against Pumpkin using
HMAC-signed wire fixtures. A forwarded public address was admitted while local
blocking was enabled, and invalid signatures were rejected before lookup.
Status remained responsive during a delayed public admission lookup.

**Actual Velocity 4.0.0-6 cannot join this Pumpkin backend:** it rejects protocol
777, and a protocol 776 client receives Pumpkin's outdated-client rejection.
One of the 43 component assertions records this compatibility gap. The signed
wire fixture verifies Pumpkin's forwarding handler, not an end-to-end pass
through that Velocity build. Bungee forwarding is not certified here.

## Practical limits

All clients used isolated offline identities and loopback transport. Synthetic
public addresses were supplied through authenticated forwarding fixtures;
Microsoft/Xbox signed-in sessions, rendered gameplay, arbitrary third-party
plugin packs and production deployments were not tested. Account authentication
does not remove a connection's IP, but its integration path still needs testing
with real credentials before claiming authenticated-session coverage.

The pure JavaScript Werift Bedrock backend failed its WebRTC handshake on both
stock and plugin-enabled Pumpkin. The native wrtc backend succeeded. That
stock-reproduced transport failure is not counted as an admission pass.

Local blocking is disabled by default and requires no fixed player IP list.
Enable it where Pumpkin sees real player addresses; a backend that sees only
its proxy's LAN address will otherwise classify every such join as local.

Detailed checks, source hashes and runtime provenance are in
[the machine-readable evidence](test/live/pumpkin-results-2026-10-10.json).
Installation and rebuild instructions are in
[the native guide](adapters/pumpkin/README.md). No marketplace listing was
published by this task.
'''
    REPORT_MD.write_text(text)
    print(f'{result["liveAssertions"]} live assertions, {len(units)} unit tests; {component_hash}')


if __name__ == '__main__':
    main()

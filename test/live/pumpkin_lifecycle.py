#!/usr/bin/env python3
"""Verify native configuration generation and startup failure handling."""
import hashlib
import json
from pumpkin import Pumpkin, BASE, COMPONENT


def main():
    runtime = Pumpkin()
    checks = []
    result = {'software': 'pumpkin', 'pluginVersion': '2.1.0',
              'sha256': hashlib.sha256(COMPONENT.read_bytes()).hexdigest(),
              'checks': checks, 'pass': False, 'authenticatedAccountCoverage': False}

    def record(case, passed, **details):
        checks.append({'case': case, 'pass': bool(passed), **details})
        print(json.dumps(checks[-1]), flush=True)
        if not passed:
            raise AssertionError(case)

    try:
        runtime.config.unlink()
        runtime.start()
        text = runtime.config.read_text()
        record('first start generates schema 6 and opt-in local policy',
               'configVersion: 6' in text and 'blockLocalConnections: false' in text)
        record('first start includes native Discord configuration',
               'discord:' in text and 'maskIp: true' in text)
        client = runtime.connect('PumpGenerated')
        record('generated configuration admits default Java connection', client['joined'], connection=client)
        runtime.stop()
        runtime.config.write_text('countryMode: [invalid')
        runtime.start()
        record('invalid startup configuration still registers admission handler',
               'GeoRestrict configuration error:' in runtime.log())
        client = runtime.connect('PumpBootBad')
        record('invalid startup configuration rejects new admission',
               not client['joined'] and 'configuration is invalid' in str(client.get('disconnect')), connection=client)
        record('invalid startup configuration preserves server-list status', bool(runtime.status().get('version')))
        runtime.policy(countryMode='DISABLED')
        runtime.reload()
        client = runtime.connect('PumpBootFixed')
        record('corrected startup configuration restores admission without restart', client['joined'], connection=client)
        result['pass'] = all(check['pass'] for check in checks)
    except Exception as error:
        result['error'] = str(error)
    finally:
        runtime.stop()
        runtime.runtime_lock.close()
        (BASE / 'evidence/pumpkin-native-lifecycle.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: v for k, v in result.items() if k != 'checks'}), flush=True)
    if not result['pass']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

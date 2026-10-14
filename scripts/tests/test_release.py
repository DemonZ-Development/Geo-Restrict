import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release import LOADERS, bundle, collect_artifacts, validate_version
from source_snapshot import export_source


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.artifacts = Path(self.temp.name) / 'artifacts'
        self.artifacts.mkdir()
        self.output = Path(self.temp.name) / 'dist'
        self.write('pom.xml', '<project><version>2.1.0</version></project>')
        for name in ['test/pom.xml', 'mods/pom.xml', 'test/live/mod-fixture/pom.xml']:
            self.write(name, '<project><version>2.1.0</version></project>')
        for name in LOADERS:
            self.write(f'mods/{name}/pom.xml', '<project><parent><version>2.1.0</version></parent></project>')
        for directory in ['worker', 'vps-gateway']:
            self.write(directory + '/package.json', json.dumps({'version': '2.1.0'}))
            self.write(directory + '/package-lock.json', json.dumps({'version': '2.1.0', 'packages': {'': {'version': '2.1.0'}}}))
        self.write('adapters/pumpkin/Cargo.toml', '[package]\nversion = "2.1.0"\n')
        self.write('adapters/pumpkin/Cargo.lock', '[[package]]\nname = "georestrict-pumpkin"\nversion = "2.1.0"\n')
        self.write('src/main/resources/plugin.yml', 'version: 2.1.0\n')
        self.write('src/main/resources/bungee.yml', 'version: 2.1.0\n')
        self.write('src/main/resources/velocity-plugin.json', '{"version":"2.1.0"}')
        self.write('src/main/java/zip/linuxaddict/georestrict/PluginInfo.java', 'public static final String VERSION = "2.1.0";')
        for name in ['LICENSE', 'CHANGELOG.md', 'PUMPKIN_LIVE_TEST_REPORT.md', 'MOD_LIVE_TEST_REPORT.md']:
            self.write(name, 'Test fixture\n')
        self.write('.gitignore', '.smoke/\ntarget/\nnode_modules/\n*.env\n')
        self.write('.github/workflows/build.yml', 'name: fixture\n')
        self.write('test/live/version-results-2026-10-10.json', json.dumps({'cases': [{}], 'artifactSha256': {}, 'statusCounts': {'failed': 1}, 'componentTests': []}))
        self.write('test/live/pumpkin-results-2026-10-10.json', json.dumps({'pluginVersion': '2.1.0', 'pass': False}))
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=CI Test', '-c', 'user.email=ci@example.invalid', 'commit', '-qm', 'fixture')
        for filename in ['georestrict-2.1.0.jar', *[f'georestrict-{name}-2.1.0.jar' for name in LOADERS]]:
            with zipfile.ZipFile(self.artifacts / filename, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
        (self.artifacts / 'georestrict_pumpkin.wasm').write_bytes(b'\x00asm\x0d\x00\x01\x00')
        with zipfile.ZipFile(self.artifacts / 'pumpkin-notices.zip', 'w') as archive:
            archive.writestr('THIRD_PARTY.md', 'Test fixture')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True)

    def test_valid_tags_and_mismatched_or_unsafe_tags(self):
        for tag in ['', 'v2.1.0', 'v2.1.0-rc.1']:
            self.assertEqual(validate_version(self.root, tag), '2.1.0')
        for tag in ['v2.1.1', 'v2.1.0-rc.01', 'v2.1.0;echo unsafe', 'v2.1.0\n', '2.1.0']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_version(self.root, tag)

    def test_mismatched_adapter_parent_blocks_release(self):
        self.write('mods/sponge/pom.xml', '<project><parent><version>2.0.3</version></parent></project>')
        with self.assertRaisesRegex(ValueError, 'Release version mismatch'):
            validate_version(self.root, 'v2.1.0')

    def test_missing_and_duplicate_artifacts_block_packaging(self):
        expected = self.artifacts / 'georestrict-sponge-2.1.0.jar'
        duplicate = self.artifacts / 'duplicate' / expected.name
        duplicate.parent.mkdir()
        duplicate.write_bytes(expected.read_bytes())
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            collect_artifacts(self.artifacts, '2.1.0')
        duplicate.unlink()
        expected.unlink()
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            collect_artifacts(self.artifacts, '2.1.0')

    def test_core_wasm_is_rejected(self):
        (self.artifacts / 'georestrict_pumpkin.wasm').write_bytes(b'\x00asm\x01\x00\x00\x00')
        with self.assertRaisesRegex(ValueError, 'WASI component'):
            collect_artifacts(self.artifacts, '2.1.0')

    def test_source_snapshot_includes_current_and_new_files_without_caches(self):
        self.write('src/new-file.txt', 'new source')
        self.write('LICENSE', 'modified source')
        self.write('.smoke/runtime/world.dat', 'runtime')
        self.write('private.env', 'test-only fake credential')
        output = self.output / 'source.zip'
        manifest = export_source(self.root, output, '2.1.0')
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read('Geo-Restrict/LICENSE'), b'modified source')
            self.assertEqual(archive.read('Geo-Restrict/src/new-file.txt'), b'new source')
            self.assertIn('Geo-Restrict/.github/workflows/build.yml', archive.namelist())
            self.assertFalse(any('.git/' in n or '.smoke/' in n or 'private.env' in n for n in archive.namelist()))
            for name, expected in manifest['sha256'].items():
                self.assertEqual(hashlib.sha256(archive.read('Geo-Restrict/' + name)).hexdigest(), expected)

    def test_release_sources_are_tracked_only(self):
        self.write('scratch.txt', 'untracked')
        manifest = export_source(self.root, self.output / 'source.zip', '2.1.0', tracked_only=True)
        self.assertNotIn('scratch.txt', manifest['sha256'])

    def test_incomplete_live_evidence_marks_stable_tag_as_prerelease(self):
        result = bundle(self.root, 'v2.1.0', self.artifacts, self.output)
        self.assertTrue(result['prerelease'])
        self.assertFalse(result['liveEvidenceCompleteAndMatching'])
        for line in (self.output / 'SHA256SUMS').read_text().splitlines():
            expected, name = line.split('  ', 1)
            self.assertEqual(hashlib.sha256((self.output / name).read_bytes()).hexdigest(), expected)
        with self.assertRaisesRegex(ValueError, 'empty release output'):
            bundle(self.root, 'v2.1.0', self.artifacts, self.output)

    def test_complete_matching_evidence_allows_stable_but_rc_stays_prerelease(self):
        def sha(filename):
            return hashlib.sha256((self.artifacts / filename).read_bytes()).hexdigest()
        hashes = {name: sha(f'georestrict-{name + "-" if name != "plugin" else ""}2.1.0.jar') for name in ['plugin', 'fabric', 'forge', 'neoforge']}
        self.write('test/live/version-results-2026-10-10.json', json.dumps({'cases': [{}] * 264,
                   'artifactSha256': hashes, 'statusCounts': {'passed': 264}, 'componentTests': [{'pass': True}] * 4}))
        self.write('test/live/pumpkin-results-2026-10-10.json', json.dumps({'pluginVersion': '2.1.0', 'pass': True,
                   'artifactSha256': sha('georestrict_pumpkin.wasm')}))
        result = bundle(self.root, 'v2.1.0', self.artifacts, self.output)
        self.assertFalse(result['prerelease'])
        self.assertTrue(result['liveEvidenceCompleteAndMatching'])
        result = bundle(self.root, 'v2.1.0-rc.1', self.artifacts, Path(self.temp.name) / 'rc')
        self.assertTrue(result['prerelease'])


if __name__ == '__main__':
    unittest.main()

# Builds, source exports and automatic releases

`workflows/build.yml` runs on pull requests, pushes to `main` and manual
dispatches. It is also reused by the tag-release workflow. Checks include:

- Consistent versions in Maven modules, plugin descriptors, Node packages and
  lockfiles, the Rust package/lockfile, and the Java version constant.
- Java 21 plugin unit tests, PMD/dependency checks and admission integration tests.
- All six server adapters built on Java 21. Sponge's API requires Java 21.
- Java 17 build compatibility for Fabric, Forge, NeoForge and the standalone gateway.
- Locked native Pumpkin build, Rust formatting and rule-engine tests.
- Node 22 Worker/fallback syntax and tests, plus release/export regression tests.

Build artifacts are downloadable from the workflow run. No release or gateway
deployment is triggered by a pull request or a manual build dispatch.
The existing Cloudflare documentation deployment remains a separate workflow.

## Trigger a GitHub Release

Commit and push the source and workflows to your GitHub repository. Then push
a version tag. For the current candidate:

```sh
git tag -a v2.1.0-rc.1 -m "GeoRestrict 2.1.0 release candidate 1"
git push origin v2.1.0-rc.1
```

For a later stable release, use `v2.1.0`. Future patch tags must match the
updated source version everywhere; for example, `v2.1.1` requires source
version `2.1.1`. Prerelease suffixes follow SemVer, such as `-rc.1`.

`workflows/release.yml` runs all checks before collecting assets. It stages a
draft, uploads the complete asset set, then publishes it automatically. Failed
builds do not publish; an upload failure leaves a draft that can be completed
by rerunning the workflow. Published releases are not overwritten on reruns.
Only the publishing job requests `contents: write`; it uses the built-in
`GITHUB_TOKEN`, so no personal access token or publishing secret is required.

Assets include the Java plugin, six adapter JARs, the native Pumpkin WASI
component, Pumpkin dependency notices, full/gateway source ZIPs, compatibility
reports, license, release manifest and `SHA256SUMS`.

**CI builds are not the full live-server matrix.** Prerelease tags always create
prereleases. A stable-looking tag also produces a prerelease while the committed
live evidence is incomplete or does not match the rebuilt plugin/native hashes.
The release manifest records that decision and the individual hash matches.
The current 2.1.0 matrix remains incomplete, so it is a candidate.

These workflows have been checked locally with actionlint and their build,
test and packaging commands. They have not yet run on GitHub in this task;
no remote tag or release has been created.

## Export the current source for a local checkout

```sh
python3 scripts/source_snapshot.py --output target/releases/georestrict-2.1.0-full-source.zip
```

This includes tracked modifications and new, non-ignored source files, including
`.github/` and `.gitignore`. It excludes build/runtime caches and Git metadata.
The ZIP includes per-file checksums, its base commit and local-sync instructions.
Preserve your local `.git/` directory when copying the source into your checkout.
Commit or stash local work first, then review the resulting diff.

Release CI uses `--tracked-only` so the full-source asset contains the committed
tag checkout and excludes generated files or runner state.

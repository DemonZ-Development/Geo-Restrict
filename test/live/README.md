# Live checks for the v2.1.0 candidate

These scripts run actual Minecraft clients and HTTP requests. They create and
overwrite only isolated test configurations under the ignored `.smoke/` folder.
Never point a runtime JAR argument at a production data directory. All test
TCP/UDP listeners and published container ports use loopback.

See the [version matrix](version-results-2026-10-10.json) and
[native Pumpkin results](pumpkin-results-2026-10-10.json) for tested versions
and coverage limits. Public test addresses are synthetic trusted forwarding metadata;
the clients themselves connect from loopback using offline accounts.

## Prepare

Use Python 3.11 or newer, Node 22 or newer, Maven, Java 21 for server/Bungee runtimes and
Java 25 for Velocity 4.0.0. Build and install the candidate with `mvn clean install`.
Install the protocol client outside the production plugin:

```bash
mkdir -p .smoke/client .smoke/probe/classes .smoke/evidence
npm install --prefix .smoke/client --no-save minecraft-protocol@1.68.0
mvn -q dependency:build-classpath -Dmdep.outputFile=.smoke/probe/classpath.txt
javac --release 17 -proc:full \
  -cp "target/georestrict-2.1.0.jar:$(cat .smoke/probe/classpath.txt)" \
  -d .smoke/probe/classes test/live/probe/*.java
cp test/live/probe/plugin.yml test/live/probe/bungee.yml .smoke/probe/classes/
jar --create --file .smoke/probe/GeoRestrictLiveProbe.jar -C .smoke/probe/classes .
```

The probe is a separate test plugin, never included in the release JAR. Its
`geoprobe` console command consumes the public API on each actual platform.
Velocity's annotation processor generates its descriptor during compilation.

Set `JAVA_HOME` to Java 21, or set `GEO_LIVE_JAVA21` to its executable.
`GEO_LIVE_JAVA25` overrides the Velocity executable. Without an override, the
harness looks under `.smoke/tools/jdk*/bin/java`, then falls back to Java 21.
Use `GEO_LIVE_JVM_ARGS` for required proxy/CA settings; the harness never disables
TLS verification or supplies production credentials.

Obtain official runtime JARs from PaperMC, Purpur and SpigotMC. Verify published
checksums where available. Spigot and CraftBukkit were compiled using official
BuildTools 201 with `--rev 1.21.11 --compile SPIGOT,CRAFTBUKKIT`. The harness can
consume an explicit path with `--platform-jar`, or an ignored artifact manifest:

```json
{"path":"/absolute/path/to/paper.jar","sha256":"recorded-download-hash"}
```

Save this as `.smoke/evidence/paper-artifact.json` (and similarly for `purpur`,
`folia`, `bungee`, `waterfall`, `velocity`, and `paper-legacy`). Spigot/CraftBukkit
default to `.smoke/buildtools/<platform>-1.21.11.jar`. The recorded results contain
the exact artifact hashes used in this run.

## Start the real lookup services

Install Worker/fallback packages with `npm ci`. Download MMDB files with
`DB_DIR="$PWD/.smoke/mmdb" npm --prefix vps-gateway run update-db`.
Start the fallback in a separate terminal:

```bash
DB_DIR="$PWD/.smoke/mmdb" HOST=127.0.0.1 PORT=19082 \
  GATEWAY_TOKENS=georestrict-local-test node vps-gateway/src/server.js
```

Create `.smoke/worker.toml` for the local workerd service:

```toml
name = "georestrict-live-test"
main = "../worker/src/index.js"
compatibility_date = "2024-03-01"
[vars]
PROVIDER_ORDER = "ipinfo,ip-api"
VPS_GATEWAY_URL = "http://127.0.0.1:19082/"
VPS_GATEWAY_TOKEN = "georestrict-local-test"
STATUS_TOKENS = "georestrict-local-status"
CACHE_TTL_SECONDS = "86400"
MEMORY_CACHE_MAX_ENTRIES = "5000"
GATEWAY_VERSION = "2.1.0"
```

```bash
node worker/node_modules/wrangler/bin/wrangler.js dev \
  --config .smoke/worker.toml --local --ip 127.0.0.1 --port 19081
```

These tokens are local test fixtures. Keep them out of deployed services.

## Platform admission suites

```bash
python3 test/live/platforms.py paper --port 25570 --platform-jar /path/to/paper.jar
python3 test/live/platforms.py purpur --port 25571 --platform-jar /path/to/purpur.jar
python3 test/live/platforms.py folia --port 25572 --platform-jar /path/to/folia.jar
python3 test/live/platforms.py spigot --port 25573 --platform-jar /path/to/spigot.jar
python3 test/live/platforms.py craftbukkit --port 25577 --platform-jar /path/to/craftbukkit.jar
```

For the three proxy suites, run the Paper **1.21.1** legacy backend in a separate
terminal at port 25579, then use protocol 1.21.1 clients through each proxy:

```bash
python3 test/live/platforms.py backend --port 25579 --platform-jar /path/to/paper-1.21.1.jar
python3 test/live/platforms.py bungee --port 25574 --platform-jar /path/to/BungeeCord.jar
python3 test/live/platforms.py waterfall --port 25575 --platform-jar /path/to/waterfall.jar
python3 test/live/platforms.py velocity --port 25576 --platform-jar /path/to/velocity.jar
```

Use Ctrl-C on the backend after proxy checks. Finite suites stop their runtimes
in `finally`, including on failures, and write result JSON under `.smoke/evidence/`.
Console logs append to preserve earlier runs. Each admitted client reaches PLAY;
denied clients must receive the expected GeoRestrict message.

## Complete Minecraft version matrix and loader mods

The machine-readable [version matrix](version-results-2026-10-10.json) records
each stable Minecraft release from 1.20 through 26.3. Install Java 17, 21 and
25 and set `GEO_LIVE_JAVA17`, `GEO_LIVE_JAVA21` and `GEO_LIVE_JAVA25` to their
executables. Java 17 is used through 1.20.4; Java 21 through 1.21.11; Java 25
for 26.x and current proxy runtimes.

Build the release artifacts and isolated companions:

```bash
mvn install
mvn -f mods/pom.xml -P live-fixture package
mkdir -p .smoke/mods/fixture
cp test/live/mod-fixture/target/georestrict-live-mod-fixture-2.1.0.jar \
  .smoke/mods/fixture/GeoRestrictLiveFixture.jar
python3 test/live/build_probe.py
python3 test/live/version_matrix.py inventory
```

The inventory uses official Mojang, Fabric, Forge, NeoForge, PaperMC, Purpur
and Spigot metadata. BuildTools needs its official `BuildData` checkout to
resolve revision aliases; regenerate the inventory after the first official
BuildTools build. Cached artifact metadata and downloads live in ignored
`.smoke/mods/`. Published Minecraft/Paper/Purpur checksums are verified.

With the Worker and fallback running as above, use separate ports for
independent runners. Each command is resumable and skips only full-suite
successes for the current release/companion hashes:

```bash
python3 test/live/version_matrix.py mods --software fabric --port 25594
python3 test/live/version_matrix.py mods --software forge neoforge --port 25595
python3 test/live/version_matrix.py bukkit-build
python3 test/live/version_matrix.py plugins --software paper purpur folia --port 25596
python3 test/live/version_matrix.py plugins --software spigot craftbukkit --port 25598
python3 test/live/version_matrix.py plugins --software bungee waterfall velocity --port 25599
python3 test/live/report_matrix.py
```

Run only as many server processes as the machine can sustain. The matrix is
serial within each command. Use `--versions 1.20.1 26.3` to select specific
releases. Do not run the same software/version simultaneously: its world and
configuration directory is shared. Native runtime locks reject overlapping
cases; proxy backends have separate directories for each proxy.

The mod companion supplies a separate API consumer, native permission probe
and synthetic connection addresses. The Bukkit companion supplies permission
attachments and loopback-only event-address fixtures for CraftBukkit, which
does not implement Spigot's legacy forwarding. These companions never belong
in a distributed artifact or production server. Their source is under
`test/live/`; the normal mod Maven build excludes them.

Releases through 26.1.2 use `minecraft-protocol` with a persistent parser process;
each request creates a separate real TCP client. The 26.2/26.3 admission client
derives packet IDs from the checksummed official server bytecode because the
protocol library has no corresponding game data yet. Both clients prove PLAY
admission and expected disconnects, without claiming GUI gameplay coverage.

Native suites also change the config file without sending a reload command,
then verify that the automatic watcher rejects and restores a real login.
For Minecraft 1.20 and 1.20.1 the fixture selects one Netty I/O loop to avoid the vanilla
login constructor's shared-random-generator race; concurrent client sockets
and asynchronous lookup workers remain enabled. For 1.21.2 and 1.21.7 the
protocol client uses the identical wire definitions from 1.21.3 and 1.21.8,
while the dedicated server remains the exact requested version.

Reports preserve publisher gaps, beta loader flags, BuildTools revision aliases,
protocol incompatibilities and failures. A missing runtime or client protocol
is never counted as a successful test. Full evidence remains under
`.smoke/mods/evidence/`; the compact tracked export is
`test/live/version-results-2026-10-10.json`.

The common suite covers defaults, explicit local denial, `LOCAL` blocklist,
status ping, public country/ASN denials, external API/cache helpers, live reload,
fail-open/fail-closed, gateway recovery and persisted cache after restart.
CraftBukkit uses a direct suite with operator bypass and schema migration because
it does not implement Spigot's legacy forwarding option.

## Supplemental suites

```bash
python3 test/live/supplemental.py modern
python3 test/live/supplemental.py admission
python3 test/live/services.py
```

`modern` uses the Paper/Velocity artifact manifests and tests signed modern
forwarding on 25580/25581. `admission` uses Paper on 25582 and adds local literal
boundaries, console commands, allowlists, VPN keyword denial and 16 simultaneous
joins. `services` uses the baseline Worker/fallback and starts a second workerd
instance plus explicit HTTP provider fixtures. It tests authentication, MMDB
IPv4/IPv6, malformed input, status, CORS, HTTP caching, timeouts and failover.
Paid-provider endpoints are not contacted by that fixture suite.

For `supplemental.py addons`, install official Geyser-Spigot, Floodgate and
ViaVersion JARs under `.smoke/runtime/geyser-floodgate/plugins`, boot once to
generate their complete defaults, stop, then run the suite. It binds the Bedrock
listener on loopback, enables Floodgate authentication, checks actual RakNet
advertising, Java admission, Floodgate API detection and a local webhook capture.
It does not claim an authenticated Bedrock player session.

The report also records the real Docker build/health/restart, MMDB updater
download/repeat/corruption checks, Modrinth update lookup and local documentation
HTTP checks. Production deployment, systemd execution, authenticated Minecraft
accounts and cross-region Cloudflare cache behavior need their own staging setup.

## Package the tested candidate

Native Pumpkin is packaged separately with
`python3 test/live/package_pumpkin.py`, after `pumpkin.py`,
`pumpkin_components.py` and `pumpkin_lifecycle.py` pass. That helper requires
native unit results and Cargo metadata for the locked WASI dependency graph,
checks source/component hashes and includes dependency notices and Rust source.
It does not borrow coverage from the Java matrix or publish to the marketplace.

After regenerating the report, run `python3 test/live/package_candidate.py`.
It requires completed current-artifact matrix and supplemental checks, verifies
the four release jar hashes and creates `target/releases/georestrict-2.1.0-candidate.zip`.
The bundle includes guides and the matrix;
it excludes the test companions, server binaries, caches and credentials.

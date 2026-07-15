#!/usr/bin/env bash
# Run the GeoRestrict integration tests.
#
# Builds the plugin jar (if missing) and then runs the standalone test module
# under test/pom.xml, which exercises the real decision engine and the real
# Bukkit login handler against an in-process mock gateway.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> installing plugin artifact"
mvn -q -B install -DskipTests

echo "==> running integration tests"
mvn -B -f test/pom.xml test

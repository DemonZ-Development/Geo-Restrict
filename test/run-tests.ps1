# Run the GeoRestrict integration tests.
# Installs the plugin artifact then runs the standalone test module
# under test/pom.xml, which exercises the real decision engine and the real
# Bukkit login handler against an in-process mock gateway.
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

Write-Host "==> installing plugin artifact"
mvn -q -B install -DskipTests

Write-Host "==> running integration tests"
mvn -B -f test\pom.xml test

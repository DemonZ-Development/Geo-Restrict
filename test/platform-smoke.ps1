# Starts the release jar inside real platform runtimes and verifies startup logs.
# Downloads are cached under .smoke/downloads and generated runtime data is ignored by Git.
param([string]$JavaCommand = "java")

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PluginJar = Join-Path $Root "target\georestrict-2.0.0.jar"
$SmokeRoot = Join-Path $Root ".smoke"
$Downloads = Join-Path $SmokeRoot "downloads"
$RunRoot = Join-Path $SmokeRoot "runtime"
$UserAgent = "GeoRestrict-SmokeTests/2.0.0 (https://github.com/DemonZ-Development/Geo-Restrict)"

if (-not (Test-Path -LiteralPath $PluginJar)) {
    throw "Build the release jar first: mvn -B clean verify"
}

New-Item -ItemType Directory -Force -Path $Downloads, $RunRoot | Out-Null

function Get-FillDownload {
    param([string]$Project, [string]$Version)
    $headers = @{ "User-Agent" = $UserAgent }
    $builds = Invoke-RestMethod -Headers $headers -Uri "https://fill.papermc.io/v3/projects/$Project/versions/$Version/builds"
    $build = $builds | Where-Object { $_.channel -eq "STABLE" } | Select-Object -First 1
    if (-not $build) { throw "No stable $Project build found for $Version" }
    $download = $build.downloads.'server:default'
    [pscustomobject]@{
        Name = "$Project-$Version-$($build.id).jar"
        Url = $download.url
        Sha256 = $download.checksums.sha256
        Label = "$Project $Version build $($build.id)"
    }
}

function Save-VerifiedDownload {
    param([pscustomobject]$Artifact)
    $path = Join-Path $Downloads $Artifact.Name
    $valid = $false
    if (Test-Path -LiteralPath $path) {
        if ($Artifact.Sha256) {
            $valid = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash -eq $Artifact.Sha256
        } else {
            $valid = (Get-Item -LiteralPath $path).Length -gt 100000
        }
    }
    if (-not $valid) {
        Invoke-WebRequest -Headers @{ "User-Agent" = $UserAgent } -Uri $Artifact.Url -OutFile $path
    }
    if ($Artifact.Sha256 -and (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash -ne $Artifact.Sha256) {
        throw "Checksum mismatch for $($Artifact.Label)"
    }
    & jar tf $path *> $null
    if ($LASTEXITCODE -ne 0) { throw "Downloaded file is not a valid jar: $($Artifact.Label)" }
    $path
}

function Get-CombinedLog {
    param([string]$Directory)
    $files = @(
        (Join-Path $Directory "stdout.log"),
        (Join-Path $Directory "stderr.log"),
        (Join-Path $Directory "logs\latest.log")
    ) | Where-Object { Test-Path -LiteralPath $_ }
    ($files | ForEach-Object { Get-Content -Raw -ErrorAction SilentlyContinue -LiteralPath $_ }) -join "`n"
}

function Invoke-PlatformSmoke {
    param(
        [string]$Name,
        [string]$ServerJar,
        [string[]]$Arguments,
        [bool]$AcceptEula,
        [string]$ReadyPattern,
        [int]$TimeoutSeconds = 150
    )

    $directory = Join-Path $RunRoot $Name
    $plugins = Join-Path $directory "plugins"
    New-Item -ItemType Directory -Force -Path $plugins | Out-Null
    Copy-Item -Force -LiteralPath $PluginJar -Destination (Join-Path $plugins "GeoRestrict.jar")
    Copy-Item -Force -LiteralPath $ServerJar -Destination (Join-Path $directory "platform.jar")

    if ($AcceptEula) {
        [IO.File]::WriteAllText((Join-Path $directory "eula.txt"), "eula=true`n")
        [IO.File]::WriteAllText((Join-Path $directory "server.properties"), "server-ip=127.0.0.1`nserver-port=0`nonline-mode=false`nview-distance=2`nsimulation-distance=2`nlevel-type=minecraft:flat`n")
    }

    $stdout = Join-Path $directory "stdout.log"
    $stderr = Join-Path $directory "stderr.log"
    $process = $null
    try {
        $process = Start-Process -FilePath $JavaCommand -ArgumentList $Arguments -WorkingDirectory $directory `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            if ($process.HasExited) { break }
            $log = Get-CombinedLog $directory
            if ($log -match $ReadyPattern -and $log -match "GeoRestrict enabled\." -and
                $log -match "listening to its users comes before anything else" -and
                $log -match [regex]::Escape("https://discord.com/invite/GYsTt96ypf")) {
                $ready = $true
                break
            }
        }
        $log = Get-CombinedLog $directory
        $fatalPattern = "(?im)(Could not load.*GeoRestrict|Error occurred while enabling GeoRestrict|Exception.*GeoRestrict|NoClassDefFoundError|UnsupportedClassVersionError)"
        if (-not $ready) {
            throw "$Name did not reach a healthy GeoRestrict startup before timeout. Exit=$($process.ExitCode)"
        }
        if ($log -match $fatalPattern) {
            throw "$Name logged a plugin startup failure: $($Matches[0])"
        }
        Write-Host "PASS $Name"
        [pscustomobject]@{ Platform = $Name; Result = "PASS"; Log = $stdout }
    } finally {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force
            $process.WaitForExit(10000) | Out-Null
        }
    }
}

$paper = Save-VerifiedDownload (Get-FillDownload "paper" "1.21.11")
$folia = Save-VerifiedDownload (Get-FillDownload "folia" "1.21.11")
$velocity = Save-VerifiedDownload (Get-FillDownload "velocity" "4.0.0")

$purpurInfo = Invoke-RestMethod -Headers @{ "User-Agent" = $UserAgent } -Uri "https://api.purpurmc.org/v2/purpur/1.21.11/latest"
$purpur = Save-VerifiedDownload ([pscustomobject]@{
    Name = "purpur-1.21.11-$($purpurInfo.build).jar"
    Url = "https://api.purpurmc.org/v2/purpur/1.21.11/latest/download"
    Sha256 = $null
    Label = "Purpur 1.21.11 build $($purpurInfo.build)"
})

$waterfall = Save-VerifiedDownload (Get-FillDownload "waterfall" "1.21")
$bungee = Save-VerifiedDownload ([pscustomobject]@{
    Name = "BungeeCord-latest.jar"
    Url = "https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar"
    Sha256 = $null
    Label = "BungeeCord latest successful build"
})

$results = @()
$results += Invoke-PlatformSmoke "Paper-1.21.11" $paper @("-Xms256M", "-Xmx768M", "-jar", "platform.jar", "--nogui") $true "Done \("
$results += Invoke-PlatformSmoke "Purpur-1.21.11" $purpur @("-Xms256M", "-Xmx768M", "-jar", "platform.jar", "--nogui") $true "Done \("
$results += Invoke-PlatformSmoke "Folia-1.21.11" $folia @("-Xms256M", "-Xmx768M", "-jar", "platform.jar", "--nogui") $true "Done \("
$results += Invoke-PlatformSmoke "BungeeCord-latest" $bungee @("-Xms128M", "-Xmx384M", "-jar", "platform.jar") $false "Listening on"
$results += Invoke-PlatformSmoke "Waterfall-1.21" $waterfall @("-Xms128M", "-Xmx384M", "-jar", "platform.jar") $false "Listening on"
$results += Invoke-PlatformSmoke "Velocity-4.0.0" $velocity @("-Xms128M", "-Xmx384M", "-jar", "platform.jar") $false "Done \("

$results | Format-Table -AutoSize
"Runtime logs: $RunRoot"

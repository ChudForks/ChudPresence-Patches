param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$cli = Join-Path $PSScriptRoot 'install/revanced-cli-6.0.0-all.jar'
if (-not (Test-Path -LiteralPath $cli)) { throw "Missing ReVanced CLI: $cli" }

if (-not $SkipBuild) {
    $env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login
    if ($LASTEXITCODE -ne 0) { throw 'Authenticate GitHub CLI with read:packages first.' }
    $env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token
    if ($LASTEXITCODE -ne 0) { throw 'GitHub package authentication is unavailable.' }
    Push-Location $PSScriptRoot
    try {
        & .\gradlew.bat :patches:buildAndroid :extensions:metadata-extension:testDebugUnitTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Patch bundle build failed.' }
    } finally { Pop-Location }
}

$versionLine = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') | Where-Object { $_ -match '^version=' }
$bundleVersion = ($versionLine -split '=', 2)[1].Trim()
$bundle = Join-Path $PSScriptRoot "patches/build/libs/patches-$bundleVersion.rvp"
$output = Join-Path $PSScriptRoot "patches/build/libs/hulu-metadata-$bundleVersion.rvp"
$tools = Join-Path $PSScriptRoot 'patches/build/hulu-tools'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
& javac -classpath $cli -d $tools (Join-Path $PSScriptRoot 'verification/HuluPatchTools.java')
if ($LASTEXITCODE -ne 0) { throw 'Hulu packaging tool compilation failed.' }
& java -classpath "$cli;$tools" HuluPatchTools package $bundle $output
if ($LASTEXITCODE -ne 0) { throw 'Hulu-only packaging failed.' }
& java -jar $cli list-patches "--patches=$output" --bypass-verification --packages --versions
if ($LASTEXITCODE -ne 0) { throw 'Hulu bundle verification failed.' }
Write-Host "Hulu patch bundle: $output"

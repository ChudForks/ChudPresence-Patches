$ErrorActionPreference = 'Stop'
function Get-FileSha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
$revancedRoot = Split-Path $PSScriptRoot -Parent
$projectRoot = Split-Path $revancedRoot -Parent
$sourceRoot = Join-Path $revancedRoot 'install\upstream-revanced-patches'
$buildRoot = Join-Path $revancedRoot 'build\upstream-youtube-quality-fix'
$patchPath = Join-Path $PSScriptRoot 'youtube-quality-selection-6983.patch'
$patchHash = Get-FileSha256 $patchPath
$version = '6.1.0-chudpresence-quality.1'
$outputDir = Join-Path $revancedRoot 'install\youtube-quality-fix'
$outputBundle = Join-Path $outputDir ("patches-$version.rvp")
$provenancePath = Join-Path $outputDir 'provenance.json'
if ((Test-Path -LiteralPath $outputBundle) -and (Test-Path -LiteralPath $provenancePath)) {
    $record = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
    if ($record.patchSha256 -eq $patchHash -and $record.bundleSha256 -eq (Get-FileSha256 $outputBundle)) {
        Write-Host "Using verified YouTube quality-fix bundle: $outputBundle"
        return
    }
}
$marker = Join-Path $buildRoot 'source-quality-fix.json'
if (-not (Test-Path -LiteralPath $buildRoot)) {
    # Exclude generated build folders by absolute path: a source package is also named 'build'.
    $excluded = @('.git', '.gradle', '.kotlin', '.idea') + @(Get-ChildItem -LiteralPath $sourceRoot -Directory -Recurse -Filter build |
        Where-Object { $_.FullName -notmatch '\\src\\' } | ForEach-Object FullName)
    & robocopy $sourceRoot $buildRoot /E /XD @excluded /R:1 /W:1 /NFL /NDL /NJH /NJS | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'Copying isolated upstream sources failed.' }
    Push-Location $projectRoot
    try {
        & git apply --check --ignore-space-change --directory=revanced/build/upstream-youtube-quality-fix $patchPath
        if ($LASTEXITCODE -ne 0) { throw 'The upstream quality fix does not apply to this source snapshot.' }
        & git apply --ignore-space-change --directory=revanced/build/upstream-youtube-quality-fix $patchPath
        if ($LASTEXITCODE -ne 0) { throw 'Applying the upstream quality fix failed.' }
    } finally { Pop-Location }
    @{patchSha256=$patchHash} | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding utf8
}
if (-not (Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json).patchSha256 -ne $patchHash) {
    throw 'The isolated source folder has no matching provenance marker; inspect it before rebuilding.'
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:ORG_GRADLE_PROJECT_githubPackagesUsername -or -not $env:ORG_GRADLE_PROJECT_githubPackagesPassword) {
    $env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login
    $env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token
}
$logPath = Join-Path $buildRoot 'build-quality.log'
Write-Host "Building upstream quality fix. Log: $logPath"
Push-Location $buildRoot
try {
    & .\gradlew.bat --console=plain "-Pversion=$version" :patches:buildAndroid *> $logPath
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $logPath -Tail 40; throw 'Upstream quality-fix bundle build failed.' }
} finally { Pop-Location }
& (Join-Path $PSScriptRoot 'Test-YouTubeQualityFix.ps1')
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $buildRoot "patches\build\libs\patches-$version.rvp") -Destination $outputBundle
[ordered]@{
    upstreamBase='015fe10a'; mergeRequest='https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6983';
    upstreamMergeCommit='0558357345049b9fbaae43fdd0793f9bbf8ab1b2';
    patchSha256=$patchHash; bundleSha256=(Get-FileSha256 $outputBundle);
    version=$version
} | ConvertTo-Json | Set-Content -LiteralPath $provenancePath -Encoding utf8
Write-Host "Upstream quality-fix bundle ready: $outputBundle"

param(
    [ValidateSet('youtube', 'youtube_music')][string]$App = 'youtube_music',
    [string]$InputApk,
    [string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$installDir = Join-Path $PSScriptRoot 'install'
$cli = Join-Path $installDir 'revanced-cli-6.0.0-all.jar'
$music = $App -eq 'youtube_music'
$stem = if ($music) { 'youtube-music' } else { 'youtube' }
$pattern = if ($music) { 'com.google.android.apps.youtube.music*.apk' } else { 'com.google.android.youtube_*.apk' }
$patchName = if ($music) { 'Enrich media metadata' } else { 'Expose YouTube media metadata' }
if (-not $InputApk) {
    $stock = Get-ChildItem -LiteralPath $installDir -Filter $pattern | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $stock) { throw "Put the stock $App APK in $installDir, or pass -InputApk." }
    $InputApk = $stock.FullName
}
$InputApk = (Resolve-Path -LiteralPath $InputApk).Path
if (-not $OutputApk) { $OutputApk = Join-Path $installDir ($stem + '-revanced-chudpresence-metadata.apk') }
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
if ($OutputApk -eq $InputApk) { throw 'The output path must differ from the stock APK.' }
if (Test-Path -LiteralPath $OutputApk) {
    $backup = $OutputApk + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.bak'
    Copy-Item -LiteralPath $OutputApk -Destination $backup
    Write-Host "Existing patched APK backed up to $backup"
}
$upstream = $env:REVANCED_PATCHES_RVP
if (-not $upstream -and -not $music) {
    & (Join-Path $PSScriptRoot 'upstream-fixes\Build-YouTubeQualityBundle.ps1')
    $upstream = Join-Path $installDir 'youtube-quality-fix\patches-6.1.0-chudpresence-quality.1.rvp'
}
if (-not $upstream) {
    $bundle = Get-ChildItem -LiteralPath (Join-Path $installDir 'upstream-revanced-patches\patches\build\libs') -Filter 'patches-*.rvp' |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($bundle) { $upstream = $bundle.FullName }
}
if (-not $upstream -or -not (Test-Path -LiteralPath $upstream)) { throw 'Set REVANCED_PATCHES_RVP to a compatible upstream patch bundle.' }
if (-not (Test-Path -LiteralPath $cli)) { throw "Missing ReVanced CLI: $cli" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:ORG_GRADLE_PROJECT_githubPackagesUsername -or -not $env:ORG_GRADLE_PROJECT_githubPackagesPassword) {
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        $env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login 2>$null
        $env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token 2>$null
    }
}
$buildArgs = @(':patches:buildAndroid')
if (-not $env:ORG_GRADLE_PROJECT_githubPackagesUsername -or -not $env:ORG_GRADLE_PROJECT_githubPackagesPassword) {
    $buildArgs = @('--offline', '-PgithubPackagesUsername=offline', '-PgithubPackagesPassword=offline') + $buildArgs
}
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat @buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'Custom patch bundle build failed.' }
} finally { Pop-Location }
$custom = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'patches\build\libs') -Filter 'patches-*.rvp' |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $custom) { throw 'Custom patch bundle is missing.' }
# Keep the existing Music signing identity so Music updates preserve the installed app data.
$keystore = Join-Path $installDir 'youtube-music-revanced-chudpresence.keystore'
$arguments = @('-Xmx3g', '-jar', $cli, 'patch', "--patches=$upstream", '--bypass-verification',
    "--patches=$($custom.FullName)", '--bypass-verification', "--enable=$patchName",
    "--keystore=$keystore", "--out=$OutputApk", "--temporary-files-path=$(Join-Path $installDir ('patch-work-' + $stem))", '--purge', $InputApk)
& java @arguments
if ($LASTEXITCODE -ne 0) { throw 'ReVanced CLI failed.' }
Write-Host "Build complete: $OutputApk"

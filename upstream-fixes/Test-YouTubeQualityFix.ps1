$ErrorActionPreference = 'Stop'
$revancedRoot = Split-Path $PSScriptRoot -Parent
$buildRoot = Join-Path $revancedRoot 'build\upstream-youtube-quality-fix'
$qaRoot = Join-Path $buildRoot 'build\quality-qa'
$stubDir = Join-Path $qaRoot 'stubs'
$classesDir = Join-Path $qaRoot 'classes'
New-Item -ItemType Directory -Path $stubDir, $classesDir -Force | Out-Null
# Replace only Android-dependent class initialization/logging. The selector and protobuf
# messages are the real compiled production classes produced by the upstream build.
$stubs = @{
    'Utils.java' = 'package app.revanced.extension.shared; public class Utils { public static boolean isNotEmpty(String s) { return s != null && !s.isEmpty(); } public static <T,V> java.util.Map<T,V> createSizeRestrictedMap(int max) { return new java.util.LinkedHashMap<>(); } }';
    'Logger.java' = 'package app.revanced.extension.shared; public class Logger { public interface LogMessage { String buildMessageString(); } public static void printDebug(LogMessage value) {} }';
    'ClientType.java' = 'package app.revanced.extension.shared.spoof; public enum ClientType { TEST }'
}
foreach ($entry in $stubs.GetEnumerator()) { [IO.File]::WriteAllText((Join-Path $stubDir $entry.Key), $entry.Value) }
$library = Join-Path $buildRoot 'extensions\shared\library\build\intermediates\javac\release\compileReleaseJavaWithJavac\classes'
$protobuf = Get-ChildItem -LiteralPath (Join-Path $buildRoot 'extensions\shared\protobuf\build\libs') -Filter '*-all.jar' |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $protobuf) { throw 'Build the quality-fix upstream bundle before running these checks.' }
$stubSources = @(Get-ChildItem -LiteralPath $stubDir -Filter '*.java' | ForEach-Object FullName)
& javac -d $classesDir @stubSources
if ($LASTEXITCODE -ne 0) { throw 'Compiling Android test adapters failed.' }
$classpath = $classesDir + ';' + $library + ';' + $protobuf.FullName
& javac -cp $classpath -d $classesDir (Join-Path $PSScriptRoot 'QualitySelectionCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Compiling quality checks failed.' }
& java -cp $classpath QualitySelectionCheck
if ($LASTEXITCODE -ne 0) { throw 'Upstream quality checks failed.' }

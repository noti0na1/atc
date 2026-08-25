# Native PowerShell launcher for the release jars beside this file.
$ErrorActionPreference = 'Stop'
$AtcArgs = [string[]]$args
$launchCwd = (Get-Location).Path
$dist = $PSScriptRoot
$jar = Join-Path $dist 'atc.jar'
$libJar = Join-Path $dist 'atc-lib.jar'

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
  throw "atc.jar is missing beside '$PSCommandPath'. Download atc.ps1, atc.jar, and atc-lib.jar from the same release."
}
if (-not (Test-Path -LiteralPath $libJar -PathType Leaf)) {
  throw "atc-lib.jar is missing beside '$PSCommandPath'. Download atc.ps1, atc.jar, and atc-lib.jar from the same release."
}

$java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
  Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
  $found = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
  if (-not $found) { throw 'Java 17 or newer is required, but java.exe was not found in JAVA_HOME or PATH.' }
  $found.Source
}

$versionProbe = New-Object System.Diagnostics.Process
$versionProbe.StartInfo.FileName = $java
$versionProbe.StartInfo.Arguments = '-version'
$versionProbe.StartInfo.UseShellExecute = $false
$versionProbe.StartInfo.CreateNoWindow = $true
$versionProbe.StartInfo.RedirectStandardOutput = $true
$versionProbe.StartInfo.RedirectStandardError = $true
try {
  if (-not $versionProbe.Start()) { throw "Could not start '$java' to check its version." }
  $versionText = $versionProbe.StandardOutput.ReadToEnd() + $versionProbe.StandardError.ReadToEnd()
  $versionProbe.WaitForExit()
} finally {
  $versionProbe.Dispose()
}
if (-not ($versionText -match '(?im)^\S+\s+version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?')) {
  throw "Unable to determine the Java version from '$java'. Java 17 or newer is required."
}
$major = [int]$Matches.major
if ($major -eq 1 -and $Matches.minor) { $major = [int]$Matches.minor }
if ($major -lt 17) { throw "Java 17 or newer is required; '$java' reports major version $major." }

$javaArgs = @('-Dfile.encoding=UTF-8', '-Datc.version=@ATC_VERSION@', '-jar', 'atc.jar')
$savedLaunchCwd = $env:ATC_INTERNAL_LAUNCH_CWD
$savedLibClasspath = $env:ATC_INTERNAL_LIB_CLASSPATH
$internalArgNames = @('ATC_INTERNAL_ARG_COUNT')
if ($AtcArgs.Count -gt 0) {
  $internalArgNames += @(0..($AtcArgs.Count - 1) | ForEach-Object { "ATC_INTERNAL_ARG_$_" })
}
$savedInternalArgs = @{}
foreach ($name in $internalArgNames) {
  $savedInternalArgs[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:ATC_INTERNAL_LAUNCH_CWD = $launchCwd
$env:ATC_INTERNAL_LIB_CLASSPATH = $libJar
$env:ATC_INTERNAL_ARG_COUNT = $AtcArgs.Count
for ($index = 0; $index -lt $AtcArgs.Count; $index++) {
  [Environment]::SetEnvironmentVariable("ATC_INTERNAL_ARG_$index", "x$($AtcArgs[$index])", 'Process')
}

Push-Location -LiteralPath $dist
try {
  & $java @javaArgs
  $javaExit = $LASTEXITCODE
} finally {
  Pop-Location
  $env:ATC_INTERNAL_LAUNCH_CWD = $savedLaunchCwd
  $env:ATC_INTERNAL_LIB_CLASSPATH = $savedLibClasspath
  foreach ($name in $internalArgNames) {
    [Environment]::SetEnvironmentVariable($name, $savedInternalArgs[$name], 'Process')
  }
}
exit $javaExit

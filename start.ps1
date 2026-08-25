# Start ATC from a Windows checkout. Loads .env, rebuilds stale jars, then runs them.
$ErrorActionPreference = 'Stop'
$capturedCountText = $env:ATC_INTERNAL_START_ARG_COUNT
if ($null -ne $capturedCountText) {
  try { $capturedCount = [int]$capturedCountText }
  catch { throw "Invalid internal start.cmd argument count: $capturedCountText" }
  if ($capturedCount -lt 0 -or $capturedCount -gt 10000) {
    throw "Invalid internal start.cmd argument count: $capturedCountText"
  }
  $captured = [Collections.Generic.List[string]]::new()
  for ($index = 0; $index -lt $capturedCount; $index++) {
    $name = "ATC_INTERNAL_START_ARG_$index"
    $encoded = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ($null -eq $encoded -or -not $encoded.StartsWith('x')) {
      throw "start.cmd did not provide argument $index of $capturedCount"
    }
    $captured.Add($encoded.Substring(1))
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
  }
  [Environment]::SetEnvironmentVariable('ATC_INTERNAL_START_ARG_COUNT', $null, 'Process')
  $AtcArgs = $captured.ToArray()
} else {
  $AtcArgs = [string[]]$args
}
$launchCwd = (Get-Location).Path
$root = $PSScriptRoot
$envFile = if ($env:ATC_ENV_FILE) { $env:ATC_ENV_FILE } else { Join-Path $root '.env' }

# Load simple KEY=value entries without replacing variables inherited from the shell.
if (Test-Path -LiteralPath $envFile -PathType Leaf) {
  foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $name = $Matches[1]
      $value = $Matches[2].Trim()
      if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
          ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
      }
      if ($value -and -not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
      }
    }
  }
}

$dist = Join-Path $root 'out\dist.dest'
$jar = Join-Path $dist 'atc.jar'
$libJar = Join-Path $dist 'atc-lib.jar'
$versionFile = Join-Path $dist 'version.txt'
$needsBuild = $env:ATC_SKIP_BUILD -ne '1' -and
  (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $libJar))

if (-not $needsBuild -and $env:ATC_SKIP_BUILD -ne '1') {
  $builtAt = (Get-Item -LiteralPath $jar).LastWriteTimeUtc
  $sources = @((Join-Path $root 'build.mill'), (Join-Path $root 'app'), (Join-Path $root 'lib'))
  $needsBuild = Get-ChildItem -LiteralPath $sources -File -Recurse |
    Where-Object LastWriteTimeUtc -GT $builtAt | Select-Object -First 1
}

if ($needsBuild) {
  Write-Host '[start.cmd] building distribution (Mill Windows launcher)...' -ForegroundColor DarkGray
  Push-Location -LiteralPath $root
  try {
    & (Join-Path $root 'mill.bat') dist
    if ($LASTEXITCODE -ne 0) { throw "Mill exited with code $LASTEXITCODE" }
  } finally {
    Pop-Location
  }
}

$argsList = [Collections.Generic.List[string]]::new()
if ($env:ATC_CWD) { $argsList.Add('-C'); $argsList.Add($env:ATC_CWD) }
if ($env:ATC_CONFIG) { $argsList.Add('-c'); $argsList.Add($env:ATC_CONFIG) }
if ($env:ATC_MODEL) { $argsList.Add('-m'); $argsList.Add($env:ATC_MODEL) }
if ($AtcArgs) { $argsList.AddRange([string[]]$AtcArgs) }

$javaArgs = [Collections.Generic.List[string]]::new()
if ($env:ATC_JAVA_OPTS) {
  foreach ($option in ($env:ATC_JAVA_OPTS -split '\s+' | Where-Object { $_ })) { $javaArgs.Add($option) }
}
$javaArgs.Add('-Dfile.encoding=UTF-8')
$appVersion = if (Test-Path -LiteralPath $versionFile -PathType Leaf) {
  (Get-Content -LiteralPath $versionFile -Encoding UTF8 -Raw).Trim()
} else { 'dev' }
$javaArgs.Add("-Datc.version=$appVersion")
$javaArgs.Add('-jar')
$javaArgs.Add('atc.jar')

$java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
  Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
  (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source
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

$savedLaunchCwd = $env:ATC_INTERNAL_LAUNCH_CWD
$savedLibClasspath = $env:ATC_INTERNAL_LIB_CLASSPATH
$internalArgNames = @('ATC_INTERNAL_ARG_COUNT')
if ($argsList.Count -gt 0) {
  $internalArgNames += @(0..($argsList.Count - 1) | ForEach-Object { "ATC_INTERNAL_ARG_$_" })
}
$savedInternalArgs = @{}
foreach ($name in $internalArgNames) {
  $savedInternalArgs[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:ATC_INTERNAL_LAUNCH_CWD = $launchCwd
$env:ATC_INTERNAL_LIB_CLASSPATH = $libJar
$env:ATC_INTERNAL_ARG_COUNT = $argsList.Count
for ($index = 0; $index -lt $argsList.Count; $index++) {
  [Environment]::SetEnvironmentVariable("ATC_INTERNAL_ARG_$index", "x$($argsList[$index])", 'Process')
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

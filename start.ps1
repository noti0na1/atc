# Start ATC from a Windows checkout. Loads .env, rebuilds stale jars, then runs them.
$ErrorActionPreference = 'Stop'
$AtcArgs = [string[]]$args
$root = $PSScriptRoot
$envFile = if ($env:ATC_ENV_FILE) { $env:ATC_ENV_FILE } else { Join-Path $root '.env' }

# Load simple KEY=value entries without replacing variables inherited from the shell.
if (Test-Path -LiteralPath $envFile -PathType Leaf) {
  foreach ($line in Get-Content -LiteralPath $envFile) {
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
$needsBuild = $env:ATC_SKIP_BUILD -ne '1' -and
  (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $libJar))

if (-not $needsBuild -and $env:ATC_SKIP_BUILD -ne '1') {
  $builtAt = (Get-Item -LiteralPath $jar).LastWriteTimeUtc
  $sources = @((Join-Path $root 'build.mill'), (Join-Path $root 'app'), (Join-Path $root 'lib'))
  $needsBuild = Get-ChildItem -LiteralPath $sources -File -Recurse |
    Where-Object LastWriteTimeUtc -GT $builtAt | Select-Object -First 1
}

if ($needsBuild) {
  Write-Host '[start.cmd] building distribution (Mill JVM launcher)...' -ForegroundColor DarkGray
  $oldMillVersion = $env:MILL_VERSION
  try {
    $env:MILL_VERSION = '1.1.8-jvm'
    & bash (Join-Path $root 'mill') dist
    if ($LASTEXITCODE -ne 0) { throw "Mill exited with code $LASTEXITCODE" }
  } finally {
    $env:MILL_VERSION = $oldMillVersion
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
$javaArgs.Add("-Datc.lib.classpath=$libJar")
$javaArgs.Add('-jar')
$javaArgs.Add($jar)
$javaArgs.AddRange($argsList)
& java @javaArgs
exit $LASTEXITCODE

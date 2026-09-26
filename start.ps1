# Start ATC from a Windows checkout. Rebuilds stale jars, loads .env, then runs them.
$ErrorActionPreference = 'Stop'
$AtcArgs = [string[]]$args
$launchCwd = (Get-Location).Path
$root = $PSScriptRoot

# `-Xmx<size>` / `-Xms<size>` are the JVM's flags, not ATC's: take them out for java. The
# value of an ATC option that takes one (-p 'text', -C dir, ...) is forwarded untouched even
# when it starts with -Xm: the list of those options mirrors `FlagsWithValues` in
# app/src/atc/Cli.scala (`atc`, start.sh and windows/atc.ps1 carry the same list).
$optionsWithValues = @('-c', '--config', '-C', '--cwd', '-m', '--model', '-p', '--prompt', '--mode')
$jvmOpts = @()
$forwarded = [Collections.Generic.List[string]]::new()
$expectValue = $false
foreach ($arg in $AtcArgs) {
  if ($expectValue) {
    $expectValue = $false
    $forwarded.Add($arg)
  } elseif ($optionsWithValues -ccontains $arg) {
    $expectValue = $true
    $forwarded.Add($arg)
  } elseif ($arg -cmatch '^-Xm[sx]') {
    if ($arg -cnotmatch '^-Xm[sx][0-9]+[kKmMgG]?$') {
      throw "Invalid JVM heap size '$arg': use a number with an optional k, m or g suffix, e.g. -Xmx4g or -Xms512m"
    }
    $jvmOpts += $arg
  } else {
    $forwarded.Add($arg)
  }
}
$AtcArgs = [string[]]$forwarded.ToArray()
$envFile = if ($env:ATC_ENV_FILE) { $env:ATC_ENV_FILE } else { Join-Path $root '.env' }

# Load simple KEY=value entries without replacing variables inherited from the shell; with a
# name, only that entry.
function Import-EnvFile([string]$Only) {
  if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { return }
  foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $name = $Matches[1]
      if ($Only -and $name -ne $Only) { continue }
      $value = $Matches[2].Trim()
      if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
          ($value.StartsWith("'") -and $value.EndsWith("'")))) {
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
# Written after each build. Mill leaves the jars alone when the content they are built from
# did not change, so a source only touched or checked out again would stay newer than them
# and make every start rebuild.
$stamp = Join-Path $dist 'build.stamp'
# The build runs before the rest of .env is loaded, so a Mill server it starts does not keep
# the API keys in its environment.
Import-EnvFile 'ATC_SKIP_BUILD'
$needsBuild = $env:ATC_SKIP_BUILD -ne '1' -and (-not (Test-Path -LiteralPath $jar) -or
  -not (Test-Path -LiteralPath $libJar) -or -not (Test-Path -LiteralPath $stamp))

if (-not $needsBuild -and $env:ATC_SKIP_BUILD -ne '1') {
  $builtAt = (Get-Item -LiteralPath $stamp).LastWriteTimeUtc
  # What the distribution is built from; the tests are not part of it.
  $sources = @('build.mill', 'app\src', 'app\resources', 'lib\src', 'windows') |
    ForEach-Object { Join-Path $root $_ }
  $needsBuild = Get-ChildItem -LiteralPath $sources -File -Recurse |
    Where-Object LastWriteTimeUtc -GT $builtAt | Select-Object -First 1
}

if ($needsBuild) {
  Write-Host '[start.ps1] building distribution (Mill Windows launcher)...' -ForegroundColor DarkGray
  Push-Location -LiteralPath $root
  try {
    & (Join-Path $root 'mill.bat') dist
    if ($LASTEXITCODE -ne 0) { throw "Mill exited with code $LASTEXITCODE" }
    [IO.File]::WriteAllText($stamp, '')
  } finally {
    Pop-Location
  }
}
Import-EnvFile

$argsList = [Collections.Generic.List[string]]::new()
if ($env:ATC_CWD) { $argsList.Add('-C'); $argsList.Add($env:ATC_CWD) }
if ($env:ATC_CONFIG) { $argsList.Add('-c'); $argsList.Add($env:ATC_CONFIG) }
if ($env:ATC_MODEL) { $argsList.Add('-m'); $argsList.Add($env:ATC_MODEL) }
if ($AtcArgs) { $argsList.AddRange([string[]]$AtcArgs) }

$javaArgs = [Collections.Generic.List[string]]::new()
# JVM defaults as in the `atc` wrapper (the same list is repeated in atc, start.sh,
# windows/atc.ps1 and the dist script in build.mill); ATC_JAVA_OPTS and then the command
# line's -Xmx/-Xms come later and win.
foreach ($option in @('-Xms256m', '-Xmx2g', '-Xss4m', '-XX:-UsePerfData')) { $javaArgs.Add($option) }
if ($env:ATC_JAVA_OPTS) {
  foreach ($option in ($env:ATC_JAVA_OPTS -split '\s+' | Where-Object { $_ })) { $javaArgs.Add($option) }
}
foreach ($option in $jvmOpts) { $javaArgs.Add($option) }
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
# Scala's LazyVals still use sun.misc.Unsafe; Java 23+ warns about it on every run (JEP 471).
if ($major -ge 23) { $javaArgs.Insert(0, '--sun-misc-unsafe-memory-access=allow') }
# JLine loads a native library; Java 24+ warns about that on every run (JEP 472).
if ($major -ge 24) { $javaArgs.Insert(1, '--enable-native-access=ALL-UNNAMED') }

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

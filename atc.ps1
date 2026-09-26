# atc.ps1: install, update and run ATC on Windows from the files published on GitHub releases.
#
#   irm https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atc.ps1 -OutFile atc.ps1
#   powershell -ExecutionPolicy Bypass -File .\atc.ps1 setup   # installs atc, downloads the latest release
#   atc -C C:\my-project                                       # runs ATC (any argument that is not a command)
#
# `atc help` lists the wrapper commands. The wrapper keeps the release's jars and its own
# atc.ps1 launcher, which starts Java, in %USERPROFILE%\.atc\jars and runs that launcher. The
# Unix `atc` wrapper is the model for it. Keep this file ASCII: Windows PowerShell reads a
# script without a byte order mark in the system code page.
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 may not offer TLS 1.2 by default, and its progress bar slows downloads.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$OwnerRepo = 'noti0na1/atc'
$ScriptSourceUrl = "https://raw.githubusercontent.com/$OwnerRepo/refs/heads/main/atc.ps1"
$ReleaseApiUrl = "https://api.github.com/repos/$OwnerRepo/releases/latest"
$OnWindows = [Environment]::OSVersion.Platform -eq 'Win32NT'
# ATC keeps its configuration in %USERPROFILE%\.atc (Java's user.home), which can differ from
# PowerShell's $HOME on a domain account.
$AtcHome = Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }) '.atc'
# Absolute, since .NET resolves a relative path against the process directory, not PowerShell's.
$CacheDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath(
  $(if ($env:ATC_CACHE_DIR) { $env:ATC_CACHE_DIR } else { Join-Path $AtcHome 'jars' }))
$InstallDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath(
  $(if ($env:ATC_INSTALL_DIR) { $env:ATC_INSTALL_DIR } else { Join-Path $AtcHome 'bin' }))
# The release files the wrapper installs together: the jars and the launcher of the same release.
$ReleaseFiles = @('atc.jar', 'atc-lib.jar', 'atc.ps1')
$Launcher = Join-Path $CacheDir 'atc.ps1'
# "<release id>|<tag>" of the installed release, written after its files are in place.
$ReleaseMarker = Join-Path $CacheDir 'release.txt'

function Show-Help {
  Write-Host @"
atc: install, update and run ATC

Usage:
  atc [atc-args...]        Run ATC with the downloaded release (same as 'atc run'). The first
                           run downloads the latest release when none is installed.
  atc run [atc-args...]    Run ATC; every argument is passed to it ('atc run --help').
  atc setup                Install this wrapper and atc.cmd into $InstallDir,
                           put that directory on the user PATH and download the latest release.
  atc update               Download the latest release when it is missing or out of date.
  atc self update          Replace this wrapper with the latest one from GitHub.
  atc self uninstall       Remove the wrapper, the downloaded release and the PATH entry
                           ($AtcHome\config.json and keys.properties are kept).
  atc help                 Show this help.

Locations:
  Wrapper:  $InstallDir  (override with ATC_INSTALL_DIR)
  Release:  $CacheDir  (override with ATC_CACHE_DIR)

Environment:
  ATC_JAVA_OPTS        extra JVM flags; -Xmx<size> and -Xms<size> arguments win over them
  ATC_CHECK_UPDATES=0  skip the release check on interactive startup
  GITHUB_TOKEN         raises the GitHub API rate limit for release checks
"@
}

# ---------------------------------------------------------------------------
# Release metadata
# ---------------------------------------------------------------------------

# The latest release's metadata. $TimeoutSec bounds the startup check; 0 waits.
function Get-LatestRelease([int]$TimeoutSec = 0) {
  $headers = @{}
  if ($env:GITHUB_TOKEN) { $headers.Authorization = "Bearer $env:GITHUB_TOKEN" }
  try {
    Invoke-RestMethod -Uri $ReleaseApiUrl -Headers $headers -UseBasicParsing -TimeoutSec $TimeoutSec
  } catch {
    $response = $_.Exception.Response
    $status = if ($response) { [int]$response.StatusCode } else { 0 }
    if ($status -eq 403) {
      throw "GitHub API returned 403 Forbidden for $ReleaseApiUrl. You may be rate limited; set GITHUB_TOKEN to raise the rate limit."
    }
    if ($status -eq 404) { throw "GitHub API returned 404 for ${ReleaseApiUrl}: $OwnerRepo has no published release yet." }
    throw "Failed to query the GitHub API at ${ReleaseApiUrl}: $($_.Exception.Message)"
  }
}

function Get-ReleaseKey($release) {
  if (-not $release.id -or -not $release.tag_name) { throw 'Failed to parse the latest release metadata.' }
  "$($release.id)|$($release.tag_name)"
}

# The download URL and SHA-256 digest of each release file. A file without an https URL or a
# digest fails the release: nothing is installed that cannot be verified.
function Get-ReleaseAssets($release) {
  $assets = @{}
  foreach ($name in $ReleaseFiles) {
    $asset = @($release.assets | Where-Object { $_.name -ceq $name })[0]
    if (-not $asset) { throw "Required asset $name was not found in the latest release." }
    $url = [string]$asset.browser_download_url
    if (-not $url.StartsWith('https://')) { throw "Refusing a non-https download URL for ${name}: $url" }
    if ([string]$asset.digest -match '^sha256:([0-9a-fA-F]{64})$') {
      $assets[$name] = @{ Url = $url; Hash = $Matches[1].ToLowerInvariant() }
    } else {
      throw "No SHA-256 digest in the release metadata for $name; refusing to install an unverified file."
    }
  }
  $assets
}

# ---------------------------------------------------------------------------
# The installed release
# ---------------------------------------------------------------------------

function Get-FileSha256([string]$Path) {
  (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-Marker {
  if (Test-Path -LiteralPath $ReleaseMarker -PathType Leaf) { [IO.File]::ReadAllText($ReleaseMarker).Trim() } else { '' }
}

# Whether a release is installed completely: its marker is written only after all its files.
function Test-Ready {
  if (-not (Read-Marker)) { return $false }
  foreach ($name in $ReleaseFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $CacheDir $name) -PathType Leaf)) { return $false }
  }
  $true
}

# Whether the installed files are the given release's and match its digests.
function Test-Installed([string]$Key, $Assets) {
  if ((Read-Marker) -cne $Key) { return $false }
  foreach ($name in $ReleaseFiles) {
    $path = Join-Path $CacheDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-FileSha256 $path) -ne $Assets[$name].Hash) {
      return $false
    }
  }
  $true
}

# Whether the installed files can be replaced. A running ATC holds its jars open, and Windows
# does not replace an open file.
function Test-Replaceable {
  foreach ($name in $ReleaseFiles) {
    $path = Join-Path $CacheDir $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
      try { [IO.File]::Open($path, 'Open', 'ReadWrite', 'None').Dispose() } catch { return $false }
    }
  }
  $true
}

function Save-Url([string]$Url, [string]$Path) {
  Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
}

# Downloads the release's files beside the cache, verifies each against its digest and only
# then moves them into place. The marker goes first and comes back last, so an install that
# stops halfway is downloaded again instead of run.
function Install-Release($release) {
  $key = Get-ReleaseKey $release
  $assets = Get-ReleaseAssets $release
  $tag = $release.tag_name
  if (Test-Installed $key $assets) {
    Write-Host "ATC $tag is already up to date in $CacheDir."
    return
  }
  if (-not (Test-Replaceable)) { throw "ATC is running from $CacheDir. Close its sessions and run 'atc update'." }
  New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
  $tmp = Join-Path $CacheDir ('download.' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmp | Out-Null
  try {
    Write-Host "Downloading ATC $tag to $CacheDir"
    foreach ($name in $ReleaseFiles) {
      Write-Host "- $name"
      $file = Join-Path $tmp $name
      try { Save-Url $assets[$name].Url $file } catch { throw "Failed to download $name from $($assets[$name].Url): $($_.Exception.Message)" }
      $actual = Get-FileSha256 $file
      if ($actual -ne $assets[$name].Hash) { throw "Checksum mismatch for ${name}: expected $($assets[$name].Hash), got $actual" }
    }
    Write-Host 'Verified the SHA-256 digests.'
    if (Test-Path -LiteralPath $ReleaseMarker) { Remove-Item -LiteralPath $ReleaseMarker -Force }
    foreach ($name in $ReleaseFiles) {
      Move-Item -LiteralPath (Join-Path $tmp $name) -Destination (Join-Path $CacheDir $name) -Force
    }
    [IO.File]::WriteAllText($ReleaseMarker, "$key`n")
  } finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
  }
  Write-Host "ATC $tag is ready."
}

# ---------------------------------------------------------------------------
# Interactive startup update check
# ---------------------------------------------------------------------------

function ConvertTo-ReleaseVersion([string]$Tag) {
  if ($Tag -match '^v?([0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9})$') { [version]$Matches[1] }
}

# Compares stable MAJOR.MINOR.PATCH tags numerically, never offering a downgrade.
function Test-NewerRelease([string]$Candidate, [string]$Installed) {
  $new = ConvertTo-ReleaseVersion $Candidate
  $old = ConvertTo-ReleaseVersion $Installed
  [bool]($new -and $old -and $new -gt $old)
}

function Test-Terminal {
  -not [Console]::IsInputRedirected -and -not [Console]::IsErrorRedirected
}

# Whether a start may ask about updates: a terminal and an interactive ATC. The options whose
# value is skipped mirror `FlagsWithValues` in app/src/atc/Cli.scala.
function Test-StartupCheck([string[]]$Arguments) {
  if ($env:ATC_CHECK_UPDATES -eq '0' -or -not (Test-Terminal)) { return $false }
  for ($i = 0; $i -lt $Arguments.Count; $i++) {
    if ($Arguments[$i] -cin '-p', '--prompt', '-h', '--help', '-v', '--version', '--init', '--init-global') { return $false }
    if ($Arguments[$i] -cin '-c', '--config', '-C', '--cwd', '-m', '--model', '--mode') { $i++ }
  }
  $true
}

# Offers a newer release. A failed lookup, a release still uploading its files, another ATC
# session holding the files, or a declined offer starts the installed one; a failed upgrade
# stops the start.
function Invoke-StartupUpdate {
  if ((Read-Marker) -match '^[0-9]+\|(.+)$') { $current = $Matches[1] } else { return }
  if (-not (Test-Replaceable)) { return }
  try {
    $release = Get-LatestRelease 5
    Get-ReleaseAssets $release | Out-Null
  } catch {
    return
  }
  if (-not (Test-NewerRelease $release.tag_name $current)) { return }
  $answer = Read-Host "ATC $($release.tag_name) is available (installed: $current). Upgrade now? [y/N]"
  if ($answer -match '^\s*(y|yes)\s*$') { Install-Release $release } else { Write-Host "Continuing with ATC $current." }
}

# ---------------------------------------------------------------------------
# The wrapper and the user PATH
# ---------------------------------------------------------------------------

function Install-Self {
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
  $target = Join-Path $InstallDir 'atc.ps1'
  # Written anew rather than copied, so a browser's mark of the web does not follow it and make
  # a RemoteSigned policy refuse it.
  if ($PSCommandPath -ne $target) { [IO.File]::WriteAllBytes($target, [IO.File]::ReadAllBytes($PSCommandPath)) }
  # Command Prompt runs atc.cmd, which starts this wrapper whatever the execution policy.
  $shim = "@echo off`r`nsetlocal DisableDelayedExpansion`r`n" +
    "`"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`" -NoProfile -ExecutionPolicy Bypass -File `"%~dp0atc.ps1`" %*`r`n" +
    "exit /b %errorlevel%`r`n"
  [IO.File]::WriteAllText((Join-Path $InstallDir 'atc.cmd'), $shim)
  Write-Host "Installed atc.ps1 and atc.cmd to $InstallDir"
}

function Test-SamePath([string]$Entry, [string]$Dir) {
  [Environment]::ExpandEnvironmentVariables($Entry).TrimEnd('\') -eq $Dir.TrimEnd('\')
}

# $Path with $Dir appended, or $Path itself when an entry already names $Dir.
function Add-PathEntry([string]$Path, [string]$Dir) {
  $entries = @($Path -split ';' | Where-Object { $_ })
  if (@($entries | Where-Object { Test-SamePath $_ $Dir }).Count -gt 0) { return $Path }
  ($entries + $Dir) -join ';'
}

function Remove-PathEntry([string]$Path, [string]$Dir) {
  @($Path -split ';' | Where-Object { $_ -and -not (Test-SamePath $_ $Dir) }) -join ';'
}

# The user PATH is read and written in the registry with its %VARIABLE% references intact,
# which [Environment]::SetEnvironmentVariable would expand and store as a plain string.
function Get-UserPath {
  $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
  try { [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) }
  finally { $key.Dispose() }
}

function Set-UserPath([string]$Value) {
  $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
  try { $key.SetValue('Path', $Value, [Microsoft.Win32.RegistryValueKind]::ExpandString) }
  finally { $key.Dispose() }
  # Windows rereads the environment when told it changed, so terminals opened later see the entry.
  if (-not ('AtcSetup.Native' -as [type])) {
    Add-Type -Namespace AtcSetup -Name Native -MemberDefinition @'
[DllImport("user32.dll", CharSet = CharSet.Unicode)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam, string lParam, uint flags, uint timeout, out UIntPtr result);
'@
  }
  $result = [UIntPtr]::Zero
  # HWND_BROADCAST, WM_SETTINGCHANGE, SMTO_ABORTIFHUNG
  [void][AtcSetup.Native]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$result)
}

function Add-UserPathEntry {
  if (-not $OnWindows) { return }
  $path = Get-UserPath
  $updated = Add-PathEntry $path $InstallDir
  if ($updated -ceq $path) {
    Write-Host "$InstallDir is already on your PATH."
  } else {
    Set-UserPath $updated
    Write-Host "Added $InstallDir to your PATH. Open a new terminal to use 'atc'."
  }
}

function Remove-UserPathEntry {
  if (-not $OnWindows) { return }
  $path = Get-UserPath
  $updated = Remove-PathEntry $path $InstallDir
  if ($updated -cne $path) {
    Set-UserPath $updated
    Write-Host "Removed $InstallDir from your PATH."
  }
}

# The execution policy a new PowerShell session gets, which the -ExecutionPolicy Bypass that
# setup may run under hides. Restricted is the default of Windows PowerShell on a desktop.
function Get-SessionPolicy {
  foreach ($scope in 'MachinePolicy', 'UserPolicy', 'CurrentUser', 'LocalMachine') {
    $policy = [string](Get-ExecutionPolicy -Scope $scope)
    if ($policy -ne 'Undefined') { return $policy }
  }
  'Restricted'
}

function Test-Java {
  $javaHome = $env:JAVA_HOME
  if (($javaHome -and (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf)) -or
      (Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue)) { return }
  Write-Warning 'ATC needs Java 17 or newer, and java.exe was not found in JAVA_HOME or on PATH. Install a JDK before running atc.'
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

function Invoke-Setup {
  Install-Self
  Add-UserPathEntry
  Test-Java
  Install-Release (Get-LatestRelease)
  if ($OnWindows -and (Get-SessionPolicy) -in 'Restricted', 'AllSigned') {
    Write-Host "PowerShell's execution policy does not run local scripts, so in PowerShell run 'atc.cmd', or"
    Write-Host "allow local scripts with: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
  }
}

# Replaces this script with the latest wrapper on GitHub, after checking that it parses.
function Invoke-SelfUpdate {
  $target = $PSCommandPath
  $staged = "$target.update"
  Write-Host "Checking $ScriptSourceUrl"
  try {
    try { Save-Url $ScriptSourceUrl $staged } catch { throw "Failed to download the latest wrapper from ${ScriptSourceUrl}: $($_.Exception.Message)" }
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($staged, [ref]$null, [ref]$errors)
    if ($errors) { throw 'The downloaded atc.ps1 is not a valid PowerShell script.' }
    if ((Get-FileSha256 $staged) -eq (Get-FileSha256 $target)) {
      Write-Host "atc is already up to date at $target."
    } else {
      Move-Item -LiteralPath $staged -Destination $target -Force
      Write-Host "Updated $target."
    }
  } finally {
    Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
  }
}

function Remove-Files([string]$Dir, [string[]]$Names) {
  foreach ($name in $Names) {
    $path = Join-Path $Dir $name
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
  }
  if ((Test-Path -LiteralPath $Dir -PathType Container) -and -not (Get-ChildItem -LiteralPath $Dir -Force)) {
    Remove-Item -LiteralPath $Dir -Force
  }
}

# Removes only the files the wrapper installs, so a directory shared with other files is kept.
function Invoke-Uninstall {
  Remove-Files $CacheDir ($ReleaseFiles + 'release.txt')
  Remove-UserPathEntry
  Remove-Files $InstallDir @('atc.cmd', 'atc.ps1')
  Write-Host "Removed atc and the downloaded release. $AtcHome\config.json and keys.properties are kept."
}

# Starts the installed release's launcher, which takes the -Xmx/-Xms arguments for Java.
function Invoke-Run([string[]]$Arguments) {
  if (-not (Test-Ready)) {
    Install-Release (Get-LatestRelease)
  } elseif (Test-StartupCheck $Arguments) {
    Invoke-StartupUpdate
  }
  & $Launcher @Arguments
  exit $LASTEXITCODE
}

function Invoke-Main([string[]]$Arguments) {
  $command = if ($Arguments.Count -gt 0) { $Arguments[0] } else { '' }
  $rest = [string[]]@($Arguments | Select-Object -Skip 1)
  switch -CaseSensitive ($command) {
    'setup' { Invoke-Setup }
    'update' { Install-Release (Get-LatestRelease) }
    'self' {
      $subcommand = if ($rest.Count -gt 0) { $rest[0] } else { '' }
      switch -CaseSensitive ($subcommand) {
        'update' { Invoke-SelfUpdate }
        'uninstall' { Invoke-Uninstall }
        default { Show-Help }
      }
    }
    'help' { Show-Help }
    'run' { Invoke-Run $rest }
    default { Invoke-Run $Arguments }
  }
}

# Dot-sourcing (the tests) defines the functions without running a command.
if ($MyInvocation.InvocationName -ne '.') { Invoke-Main ([string[]]$args) }

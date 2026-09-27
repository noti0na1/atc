# Tests for the Windows `atc.ps1` wrapper: release metadata, checksum verification, the
# installed release, startup updates, the user PATH entries and dispatch. No network or Java
# required, and the registry is not touched.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tests\atc_test.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $repoRoot 'atc.ps1'
$testTmp = Join-Path ([IO.Path]::GetTempPath()) ('atc-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testTmp | Out-Null

# Point every location the wrapper touches at the temp dir before sourcing it.
$env:USERPROFILE = Join-Path $testTmp 'home'
$env:ATC_CACHE_DIR = Join-Path $testTmp 'cache'
$env:ATC_INSTALL_DIR = Join-Path $testTmp 'bin'
$env:ATC_CHECK_UPDATES = $null
$env:GITHUB_TOKEN = $null
. $wrapper # the guarded main does not run

$script:passed = 0
$script:failed = 0

function Check([string]$Label, [bool]$Condition, [string]$Detail = '') {
  if ($Condition) {
    Write-Host "  PASS: $Label"
    $script:passed++
  } else {
    Write-Host "  FAIL: $Label $Detail"
    $script:failed++
  }
}

# The message of the error $Block throws, or '' when it succeeds.
function Get-Failure([scriptblock]$Block) {
  try { & $Block | Out-Null; '' } catch { $_.Exception.Message }
}

function Reset-Cache {
  Remove-Item -LiteralPath $CacheDir -Recurse -Force -ErrorAction SilentlyContinue
}

# A release whose files are served from memory: Save-Url copies the bytes a URL names and
# records the download.
$script:served = @{}
$script:downloads = [Collections.Generic.List[string]]::new()
function Save-Url([string]$Url, [string]$Path) {
  $script:downloads.Add($Url)
  if (-not $script:served.ContainsKey($Url)) { throw "404 for $Url" }
  [IO.File]::WriteAllBytes($Path, $script:served[$Url])
}

function Get-Sha256Hex([byte[]]$Bytes) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { -join ($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) } finally { $sha.Dispose() }
}

# Release metadata as GitHub returns it. $Digests overrides a file's digest, $Urls its URL.
function New-Release([int]$Id, [string]$Tag, [hashtable]$Digests = @{}, [hashtable]$Urls = @{}) {
  $assets = foreach ($name in $ReleaseFiles + 'atc.cmd') {
    $bytes = [Text.Encoding]::UTF8.GetBytes("$name of $Tag")
    $url = if ($Urls.ContainsKey($name)) { $Urls[$name] } else { "https://github.com/$OwnerRepo/releases/download/$Tag/$name" }
    $script:served[$url] = $bytes
    $digest = if ($Digests.ContainsKey($name)) { $Digests[$name] } else { 'sha256:' + (Get-Sha256Hex $bytes).ToUpperInvariant() }
    [pscustomobject]@{ name = $name; browser_download_url = $url; digest = $digest }
  }
  [pscustomobject]@{ id = $Id; tag_name = $Tag; assets = @($assets) }
}

function Get-CachedText([string]$Name) { [IO.File]::ReadAllText((Join-Path $CacheDir $Name)) }

try {
  Write-Host 'Release metadata'
  $release = New-Release 7 '0.2.0'
  $assets = Get-ReleaseAssets $release
  Check 'the jars and the launcher are found' ($assets.Count -eq 3 -and $assets['atc.ps1'].Url -like 'https://*/atc.ps1')
  Check 'digests are lowercase hex' ($assets['atc.jar'].Hash -cmatch '^[0-9a-f]{64}$')
  Check 'the key is the id and the tag' ((Get-ReleaseKey $release) -ceq '7|0.2.0')
  $noLauncher = New-Release 7 '0.2.0'
  $noLauncher.assets = @($noLauncher.assets | Where-Object { $_.name -ne 'atc.ps1' })
  Check 'a missing file fails' ((Get-Failure { Get-ReleaseAssets $noLauncher }) -like '*atc.ps1 was not found*')
  $noDigest = New-Release 7 '0.2.0' @{ 'atc-lib.jar' = '' }
  Check 'a file without a digest fails' ((Get-Failure { Get-ReleaseAssets $noDigest }) -like '*digest*atc-lib.jar*')
  $md5 = New-Release 7 '0.2.0' @{ 'atc.jar' = 'md5:0123456789abcdef0123456789abcdef' }
  Check 'a digest that is not SHA-256 fails' ((Get-Failure { Get-ReleaseAssets $md5 }) -like '*digest*atc.jar*')
  $http = New-Release 7 '0.2.0' @{} @{ 'atc.jar' = 'http://example.com/atc.jar' }
  Check 'a non-https URL fails' ((Get-Failure { Get-ReleaseAssets $http }) -like '*non-https*')

  Write-Host 'Install'
  Reset-Cache
  $script:downloads.Clear()
  Install-Release $release
  Check 'the files and the marker are installed' ((Test-Ready) -and (Read-Marker) -ceq '7|0.2.0')
  Check 'the jar is the release''s' ((Get-CachedText 'atc.jar') -ceq 'atc.jar of 0.2.0')
  Check 'three files are downloaded' ($script:downloads.Count -eq 3)
  Check 'no download directory is left' (@(Get-ChildItem -LiteralPath $CacheDir -Directory).Count -eq 0)
  $script:downloads.Clear()
  Install-Release $release
  Check 'an up-to-date release is not downloaded again' ($script:downloads.Count -eq 0)
  [IO.File]::WriteAllText((Join-Path $CacheDir 'atc-lib.jar'), 'corrupt')
  Install-Release $release
  Check 'a corrupted file is downloaded again' ($script:downloads.Count -eq 3 -and (Get-CachedText 'atc-lib.jar') -ceq 'atc-lib.jar of 0.2.0')

  $bad = New-Release 8 '0.2.1' @{ 'atc-lib.jar' = 'sha256:' + ('0' * 64) }
  Check 'a checksum mismatch fails' ((Get-Failure { Install-Release $bad }) -like '*Checksum mismatch for atc-lib.jar*')
  Check 'and keeps the installed release' ((Read-Marker) -ceq '7|0.2.0' -and (Get-CachedText 'atc.jar') -ceq 'atc.jar of 0.2.0')
  Check 'and leaves no download directory' (@(Get-ChildItem -LiteralPath $CacheDir -Directory).Count -eq 0)
  $missing = New-Release 8 '0.2.1'
  $script:served.Remove($missing.assets[1].browser_download_url)
  Check 'a failed download fails' ((Get-Failure { Install-Release $missing }) -like '*Failed to download atc-lib.jar*')
  Check 'and keeps the installed release' ((Read-Marker) -ceq '7|0.2.0')

  $open = [IO.File]::Open((Join-Path $CacheDir 'atc.jar'), 'Open', 'Read', 'Read')
  try {
    Check 'an open jar is not replaceable' (-not (Test-Replaceable))
    Check 'so an install fails before downloading' ((Get-Failure { Install-Release (New-Release 9 '0.3.0') }) -like '*ATC is running*')
  } finally {
    $open.Dispose()
  }
  Check 'closed jars are replaceable' (Test-Replaceable)
  Remove-Item -LiteralPath $ReleaseMarker
  Check 'files without a marker are not ready' (-not (Test-Ready))

  Write-Host 'Release versions'
  Check '0.2.1 is newer than 0.2.0' (Test-NewerRelease '0.2.1' '0.2.0')
  Check 'v0.10.0 is newer than 0.9.9' (Test-NewerRelease 'v0.10.0' '0.9.9')
  Check '0.2.0 is not newer than v0.2.0' (-not (Test-NewerRelease '0.2.0' 'v0.2.0'))
  Check 'a downgrade is not newer' (-not (Test-NewerRelease '0.1.9' '0.2.0'))
  Check 'a pre-release tag is not compared' (-not (Test-NewerRelease '0.3.0-rc1' '0.2.0'))

  Write-Host 'Startup check'
  function Test-Terminal { $true }
  Check 'an interactive start checks' (Test-StartupCheck @('-C', 'project'))
  Check 'a prompt run does not' (-not (Test-StartupCheck @('-p', 'hello')))
  Check 'help does not' (-not (Test-StartupCheck @('--help')))
  Check 'a value that looks like a flag is skipped' (Test-StartupCheck @('-C', '-p'))
  $env:ATC_CHECK_UPDATES = '0'
  Check 'ATC_CHECK_UPDATES=0 does not' (-not (Test-StartupCheck @()))
  $env:ATC_CHECK_UPDATES = $null
  function Test-Terminal { $false }
  Check 'a redirected start does not' (-not (Test-StartupCheck @()))

  Reset-Cache
  Install-Release $release
  $script:latest = New-Release 8 '0.2.1'
  function Get-LatestRelease([int]$TimeoutSec = 0) { $script:latest }
  $script:asked = 0
  $script:answer = 'y'
  function Read-Host([string]$Prompt) { $script:asked++; $script:answer }
  Invoke-StartupUpdate
  Check 'accepting a newer release installs it' ($script:asked -eq 1 -and (Read-Marker) -ceq '8|0.2.1')
  Invoke-StartupUpdate
  Check 'the same release is not offered again' ($script:asked -eq 1)
  $script:latest = New-Release 9 '0.3.0'
  $script:answer = ''
  Invoke-StartupUpdate
  Check 'declining keeps the installed release' ($script:asked -eq 2 -and (Read-Marker) -ceq '8|0.2.1')
  $script:latest = New-Release 10 '0.4.0'
  $script:latest.assets = @($script:latest.assets | Where-Object { $_.name -ne 'atc.jar' })
  Invoke-StartupUpdate
  Check 'a release still uploading its files is not offered' ($script:asked -eq 2)
  function Get-LatestRelease([int]$TimeoutSec = 0) { throw 'offline' }
  Invoke-StartupUpdate
  Check 'a failed lookup is ignored' ($script:asked -eq 2 -and (Read-Marker) -ceq '8|0.2.1')

  Write-Host 'PATH entries'
  $dir = 'C:\Users\me\.atc\bin'
  Check 'the directory is appended' ((Add-PathEntry 'C:\Tools;%USERPROFILE%\bin' $dir) -ceq "C:\Tools;%USERPROFILE%\bin;$dir")
  Check 'an empty PATH gets the directory' ((Add-PathEntry '' $dir) -ceq $dir)
  Check 'an entry naming it in another case keeps PATH' ((Add-PathEntry 'c:\users\ME\.atc\bin\;C:\Tools' $dir) -ceq 'c:\users\ME\.atc\bin\;C:\Tools')
  $env:ATC_TEST_PROFILE = 'C:\Users\me'
  Check 'an entry naming it through a variable keeps PATH' ((Add-PathEntry '%ATC_TEST_PROFILE%\.atc\bin' $dir) -ceq '%ATC_TEST_PROFILE%\.atc\bin')
  Check 'removal keeps the other entries unexpanded' ((Remove-PathEntry "C:\Tools;$dir\;%ATC_TEST_PROFILE%\bin" $dir) -ceq 'C:\Tools;%ATC_TEST_PROFILE%\bin')

  Write-Host 'Setup and uninstall'
  Install-Self
  $shim = [IO.File]::ReadAllText((Join-Path $InstallDir 'atc.cmd'))
  Check 'the wrapper is installed' ((Get-FileSha256 (Join-Path $InstallDir 'atc.ps1')) -eq (Get-FileSha256 $wrapper))
  Check 'atc.cmd starts it with CRLF lines' ($shim.Contains('-ExecutionPolicy Bypass -File "%~dp0atc.ps1" %*' + "`r`n") -and $shim -notmatch '[^\r]\n')
  [IO.File]::WriteAllText((Join-Path $CacheDir 'notes.txt'), 'mine')
  New-Item -ItemType Directory -Force -Path $AtcHome | Out-Null
  [IO.File]::WriteAllText((Join-Path $AtcHome 'config.json'), '{}')
  function Remove-UserPathEntry { $script:pathRemoved = $true }
  Invoke-Uninstall
  Check 'uninstall removes the release but not other files' (-not (Test-Path -LiteralPath $ReleaseMarker) -and
    -not (Test-Path -LiteralPath $Launcher) -and (Test-Path -LiteralPath (Join-Path $CacheDir 'notes.txt')))
  Check 'uninstall removes the wrapper and its directory' (-not (Test-Path -LiteralPath $InstallDir))
  Check 'uninstall removes the PATH entry and keeps the config' ($script:pathRemoved -and (Test-Path -LiteralPath (Join-Path $AtcHome 'config.json')))

  Write-Host 'Dispatch'
  # A launcher standing in for the release's: it records its arguments and exits with 7.
  Reset-Cache
  New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
  foreach ($name in 'atc.jar', 'atc-lib.jar') { [IO.File]::WriteAllText((Join-Path $CacheDir $name), $name) }
  $argsFile = Join-Path $testTmp 'args.txt'
  [IO.File]::WriteAllText($Launcher, "[IO.File]::WriteAllLines('$argsFile', [string[]]`$args)`nexit 7`n")
  [IO.File]::WriteAllText($ReleaseMarker, "7|0.2.0`n")
  $env:ATC_CHECK_UPDATES = '0'
  # In this process, as PowerShell runs atc.ps1 from PATH. Windows PowerShell drops empty
  # arguments and embedded quotes on a native command line, so only this call carries them.
  & $wrapper -C 'dir with space' -p 'say "hi" & bye' -Xmx4g '' '--'
  $code = $LASTEXITCODE
  $received = [IO.File]::ReadAllLines($argsFile)
  Check 'atc runs the launcher and returns its exit code' ($code -eq 7) "(exit code $code)"
  Check 'the arguments reach the launcher unchanged' (($received -join '|') -ceq '-C|dir with space|-p|say "hi" & bye|-Xmx4g||--') ($received -join '|')
  # In a new process, as atc.cmd runs it.
  $powershell = (Get-Process -Id $PID).Path
  & $powershell -NoProfile -ExecutionPolicy Bypass -File $wrapper run --version
  Check 'atc run drops the command, and -File returns the exit code' ($LASTEXITCODE -eq 7 -and ([IO.File]::ReadAllLines($argsFile) -join '|') -ceq '--version')
  $help = & $wrapper help 6>&1 | Out-String
  Check 'atc help lists the commands' ($help -like '*atc self uninstall*')
} finally {
  Remove-Item -LiteralPath $testTmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Passed: $script:passed, failed: $script:failed"
# Always exit: the dispatch tests leave $LASTEXITCODE at the fake launcher's 7, and a caller
# such as a CI step that runs this script in its own session reports $LASTEXITCODE.
if ($script:failed -gt 0) { exit 1 } else { exit 0 }

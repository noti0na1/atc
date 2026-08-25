@echo off
setlocal DisableDelayedExpansion
set "ATC_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%ATC_POWERSHELL%" (
  echo atc: Windows PowerShell was not found at "%ATC_POWERSHELL%". 1>&2
  exit /b 1
)
"%ATC_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
exit /b %errorlevel%

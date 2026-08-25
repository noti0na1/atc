@echo off
setlocal DisableDelayedExpansion
set "ATC_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%ATC_POWERSHELL%" (
  echo atc: Windows PowerShell was not found at "%ATC_POWERSHELL%". 1>&2
  exit /b 1
)
rem Keep application arguments off powershell.exe's reconstructed command line.
rem The x prefix keeps an explicitly empty value present in the environment.
set "ATC_INTERNAL_START_ARG_COUNT=0"
:atc_capture_args
if "%~1"=="" if [%1]==[] goto atc_args_captured
set "ATC_INTERNAL_START_ARG_%ATC_INTERNAL_START_ARG_COUNT%=x%~1"
set /a ATC_INTERNAL_START_ARG_COUNT+=1 >nul
shift
goto atc_capture_args
:atc_args_captured
"%ATC_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
exit /b %errorlevel%

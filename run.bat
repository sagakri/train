@echo off
rem #ЗапускWindows: двойной щелчок запускает сборку и Java-приложение.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
if errorlevel 1 pause

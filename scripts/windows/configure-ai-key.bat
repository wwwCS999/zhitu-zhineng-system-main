@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0configure-ai-key.ps1" -RestartBackend
pause

@echo off
chcp 65001 >nul
cd /d "%~dp0\..\..\frontend"
echo [职途智配] 首次运行请先执行 npm install
npm run dev
pause

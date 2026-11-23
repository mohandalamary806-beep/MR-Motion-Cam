@echo off
echo ===========================================
echo   MR Motion Cam - connection check
echo   Close OpenTrack (or press Stop) first!
echo ===========================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check_connection.ps1"
pause

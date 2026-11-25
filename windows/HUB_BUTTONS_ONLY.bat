@echo off
title MR Motion Cam - PC Hub (buttons only)
cd /d "%~dp0"
echo OpenTrack keeps the camera. The Hub handles the phone buttons only.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0hub.ps1"
echo.
pause

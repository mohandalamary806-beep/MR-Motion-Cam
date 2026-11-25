@echo off
title MR Motion Cam - PC Hub (with camera)
cd /d "%~dp0"
echo CLOSE OpenTrack first (the Hub uses the same UDP port 4242).
echo.
rem Tip: add  -Game "CPM"  (part of the game window title) so the mouse only moves while the game is in front.
rem Tip: run once with  -ShowTitle  and click the game window: it prints the exact window title to use with -Game.
rem Tip: change the speed with  -PxPerDeg 8  (bigger = the camera turns more). Flip a direction with -InvertX / -InvertY.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0hub.ps1" -Camera
echo.
pause

@echo off
chcp 65001 >nul
echo ===========================================
echo   MR Motion Cam - Windows network setup
echo   (run as administrator)
echo ===========================================
echo.
echo Your PC IPv4 address (use the Wi-Fi adapter address):
echo.
ipconfig | findstr /i /c:"IPv4 Address" /c:"IPv4" 
echo.
echo Adding Windows Firewall rules...
netsh advfirewall firewall add rule name="MR Motion Cam UDP 4242" dir=in action=allow protocol=UDP localport=4242
netsh advfirewall firewall add rule name="MR Motion Cam UDP 4243" dir=in action=allow protocol=UDP localport=4243
netsh advfirewall firewall add rule name="MR Motion Cam TCP 8088" dir=in action=allow protocol=TCP localport=8088
if %errorlevel% equ 0 (
  echo.
  echo Rules added: UDP 4242 ^(head tracking^), UDP 4243 ^(phone buttons^), TCP 8088 ^(live view^).
  echo Enter your PC IPv4 address in the Android app.
) else (
  echo.
  echo Could not add the rules. Right-click this file and choose Run as administrator.
)
echo.
pause

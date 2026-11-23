@echo off
chcp 65001 >nul
echo ===========================================
echo   MR Motion Cam - Windows network setup
echo ===========================================
echo.
echo Your PC IPv4 address (use the Wi-Fi adapter address):
echo.
ipconfig | findstr /i /c:"IPv4 Address" /c:"IPv4" 
echo.
echo Adding Windows Firewall rule for UDP port 4242...
netsh advfirewall firewall add rule name="MR Motion Cam UDP 4242" dir=in action=allow protocol=UDP localport=4242
if %errorlevel% equ 0 (
  echo.
  echo Rule added. Enter your PC IPv4 address in the Android app.
) else (
  echo.
  echo Could not add the rule. Right-click this file and choose Run as administrator.
)
echo.
pause

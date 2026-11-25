# MR Motion Cam - PC Hub
#   * receives button presses from the phone app (UDP 4243) and presses the keys in profile_cpm2.ini
#   * with -Camera: also receives the head angles (UDP 4242, same port OpenTrack uses - close OpenTrack first)
#     and moves the mouse for you, without fighting the mouse-steering of the game
#   * serves the game screen to the phone's Live View (http://PC-IP:8088/)
#   * -ShowTitle prints the title of the window in front, so you can find the right text for -Game
param(
    [switch]$Camera,
    [string]$ProfileFile = "profile_cpm2.ini",
    [int]$ControlPort = 4243,
    [int]$PosePort = 4242,
    [int]$StreamPort = 8088,
    [string]$Pin = "-",
    [string]$Game = "",
    [double]$PxPerDeg = 8,
    [switch]$InvertX,
    [switch]$InvertY,
    [int]$StreamWidth = 960,
    [int]$StreamQuality = 55,
    [int]$StreamFps = 15,
    [switch]$NoStream,
    [switch]$ShowTitle,
    [switch]$DryRun,
    [string]$DryRunFrame = "",
    [int]$RunSeconds = 0
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$onWindows = ($env:OS -eq 'Windows_NT')   # not $IsWindows: that is a read-only automatic variable in PowerShell 7

if (-not $DryRun -and -not $onWindows) {
    Write-Host "The Hub injects Windows keyboard/mouse input. Run it on Windows (or use -DryRun to test)." -ForegroundColor Red
    exit 1
}

# ---- compile the C# sources (usings are merged so several files can go into one Add-Type call)
$files = @((Join-Path $here 'hub\HubCore.cs'))
if (-not $DryRun) { $files += (Join-Path $here 'hub\HubWindows.cs') }
$usings = @{}
$body = New-Object System.Text.StringBuilder
foreach ($f in $files) {
    foreach ($line in (Get-Content -LiteralPath $f -Encoding UTF8)) {
        if ($line -match '^\s*using\s+[A-Za-z0-9_\.]+;\s*$') { $usings[$line.Trim()] = $true }
        else { [void]$body.AppendLine($line) }
    }
}
$source = (($usings.Keys | Sort-Object) -join "`n") + "`n" + $body.ToString()
try {
    if ($DryRun) { Add-Type -TypeDefinition $source -Language CSharp }
    else { Add-Type -TypeDefinition $source -Language CSharp -ReferencedAssemblies 'System.Drawing' }
} catch {
    Write-Host "Could not compile the Hub sources:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}

# ---- profile
$profilePath = $ProfileFile
if (-not [System.IO.Path]::IsPathRooted($profilePath)) { $profilePath = Join-Path $here $ProfileFile }
if (-not (Test-Path -LiteralPath $profilePath)) { Write-Host "Profile not found: $profilePath" -ForegroundColor Red; exit 1 }
$errors = New-Object 'System.Collections.Generic.List[string]'
$actions = [MrHub.ProfileParser]::Parse((Get-Content -LiteralPath $profilePath -Raw -Encoding UTF8), $errors)
foreach ($e in $errors) { Write-Host ("profile: " + $e) -ForegroundColor Yellow }

# ---- wire everything together
if ($DryRun) { $sink = New-Object MrHub.DryRunInput }
else { [void][MrHub.WinInput]::SetProcessDPIAware(); $sink = New-Object MrHub.WinInput }
$dispatcher = New-Object MrHub.Dispatcher -ArgumentList $sink, $actions
$mixer = New-Object MrHub.CameraMixer -ArgumentList $sink
$mixer.PxPerDegX = $PxPerDeg
$mixer.PxPerDegY = $PxPerDeg
$mixer.InvertX = [bool]$InvertX
$mixer.InvertY = [bool]$InvertY
$mixer.GameMatch = $Game
$hub = New-Object MrHub.Hub -ArgumentList $dispatcher, $mixer
$hub.Pin = $(if ($Pin) { $Pin } else { "-" })
$hub.ProfileName = [System.IO.Path]::GetFileNameWithoutExtension($profilePath)
$hub.ControlPort = $ControlPort
$hub.PosePort = $PosePort
$hub.StreamPort = $StreamPort
$hub.StreamFps = $StreamFps
$hub.CameraEnabled = [bool]$Camera

$frames = $null
if (-not $NoStream) {
    if ($DryRun) { if ($DryRunFrame) { $frames = New-Object MrHub.FileFrameSource -ArgumentList $DryRunFrame } }
    else { $frames = New-Object MrHub.ScreenFrameSource -ArgumentList $StreamWidth, $StreamQuality }
}

try {
    $hub.Start($frames)
} catch {
    Write-Host ""
    Write-Host "Could not start the Hub: $($_.Exception.Message)" -ForegroundColor Red
    if ($Camera) { Write-Host "UDP port $PosePort is probably used by OpenTrack. Close OpenTrack (not just Stop) and try again." -ForegroundColor Yellow }
    Write-Host "Another copy of the Hub may also be running." -ForegroundColor Yellow
    exit 1
}

# ---- banner
Write-Host ""
Write-Host "=== MR Motion Cam - PC Hub ===" -ForegroundColor Cyan
Write-Host ("Profile : {0} ({1} actions)" -f $hub.ProfileName, $dispatcher.ActionCount)
if ($Camera) {
    Write-Host ("Camera  : ON  (head -> mouse, {0} px per degree, UDP {1}) - OpenTrack must be CLOSED" -f $PxPerDeg, $PosePort) -ForegroundColor Green
    if ($Game) { Write-Host ("          only while the foreground window title contains '{0}'" -f $Game) }
    else { Write-Host "          no -Game filter: the mouse moves whatever window is in front (use the Camera switch in the app to pause)" -ForegroundColor Yellow }
} else {
    Write-Host "Camera  : OFF (OpenTrack keeps the camera; the Hub only handles buttons)"
}
if ($frames -ne $null) { Write-Host ("Live view: http://<PC-IP>:{0}/   (open it in the phone browser to test)" -f $StreamPort) }
Write-Host ""
Write-Host "Type ONE of these addresses in the phone app (your real Wi-Fi / Ethernet adapter):"
if (Get-Command Get-NetIPAddress -ErrorAction SilentlyContinue) {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object {
            $note = ''
            if ($_.InterfaceAlias -match 'vEthernet|VirtualBox|VMware|WSL|Hyper-V|Loopback|Bluetooth|VPN|TAP|Tailscale|ZeroTier|Docker|Npcap|Pseudo') { $note = '   <-- virtual adapter, probably NOT this one' }
            Write-Host ("   {0,-28} {1}{2}" -f $_.InterfaceAlias, $_.IPAddress, $note)
        }
} else {
    [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.ToString() -notlike '127.*' } |
        ForEach-Object { Write-Host ("   " + $_.ToString()) }
}
Write-Host ""
Write-Host "Press Ctrl+C to stop. Keys held by the phone are released automatically." -ForegroundColor Cyan
Write-Host ""

# ---- main loop
$deadline = $null
if ($RunSeconds -gt 0) { $deadline = (Get-Date).AddSeconds($RunSeconds) }
$phoneUp = $false
$lastTitle = $null
try {
    while ($true) {
        Start-Sleep -Milliseconds 150
        foreach ($line in $hub.DrainLog()) { Write-Host $line }
        if ($ShowTitle) {
            $title = $sink.ForegroundTitle()
            if ($title -ne $lastTitle) { $lastTitle = $title; Write-Host ('window in front: "' + $title + '"   <- use a part of this with -Game') -ForegroundColor DarkCyan }
        }
        $now = $hub.PhoneRecentlySeen()
        if ($now -ne $phoneUp) {
            $phoneUp = $now
            if ($now) { Write-Host "phone CONNECTED" -ForegroundColor Green } else { Write-Host "phone: nothing heard for 5 s" -ForegroundColor Yellow }
        }
        if ($deadline -ne $null -and (Get-Date) -gt $deadline) { break }
    }
} finally {
    $hub.Stop()
    foreach ($line in $hub.DrainLog()) { Write-Host $line }
    Write-Host "Hub stopped."
}
if ($DryRun) {
    foreach ($ev in $sink.Snapshot()) { Write-Host ("EVENT " + $ev) }
    Write-Host ("STATS poses=" + $hub.PosePackets + " moves=" + $mixer.MovesSent)
}

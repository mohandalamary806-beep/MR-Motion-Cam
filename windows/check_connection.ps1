# MR Motion Cam - connection check (PC side)
# Run with OpenTrack STOPPED (this script uses UDP port 4242 itself).
param(
    [int]$Port = 4242,
    [int]$Seconds = 30
)

$virtualPattern = 'vEthernet|VirtualBox|VMware|WSL|Hyper-V|Loopback|Bluetooth|VPN|TAP|Tailscale|ZeroTier|Docker|Npcap|Pseudo'

Write-Host ""
Write-Host "=== 1) PC IPv4 addresses ===" -ForegroundColor Cyan
Write-Host "Type the address of your real Wi-Fi/Ethernet adapter in the phone app."
Write-Host "It must start with the same 3 numbers as the phone's IP shown in the app."
if (Get-Command Get-NetIPAddress -ErrorAction SilentlyContinue) {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object {
            $note = ''
            if ($_.InterfaceAlias -match $virtualPattern) { $note = '   <-- virtual adapter, probably NOT this one' }
            Write-Host ("  {0,-28} {1}/{2}{3}" -f $_.InterfaceAlias, $_.IPAddress, $_.PrefixLength, $note)
        }
} else {
    Write-Host "  (Get-NetIPAddress not available - run 'ipconfig' instead)"
}

Write-Host ""
Write-Host "=== 2) Windows network profile ===" -ForegroundColor Cyan
if (Get-Command Get-NetConnectionProfile -ErrorAction SilentlyContinue) {
    $profiles = Get-NetConnectionProfile -ErrorAction SilentlyContinue
    foreach ($p in $profiles) {
        $color = 'Gray'
        if ($p.NetworkCategory -eq 'Public') { $color = 'Yellow' }
        Write-Host ("  {0,-28} {1}" -f $p.Name, $p.NetworkCategory) -ForegroundColor $color
    }
    if ($profiles | Where-Object { $_.NetworkCategory -eq 'Public' }) {
        Write-Host "  'Public' blocks incoming traffic more strictly. On your HOME network you can switch it to Private:" -ForegroundColor Yellow
        Write-Host "  Settings > Network & Internet > Wi-Fi > (your network) > Network profile type > Private"
    }
}

Write-Host ""
Write-Host "=== 3) Firewall rules that can block OpenTrack ===" -ForegroundColor Cyan
if (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue) {
    $blockRules = @(Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True -ErrorAction SilentlyContinue |
        Where-Object { (($_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue).Program) -like '*opentrack*' })
    if ($blockRules.Count -gt 0) {
        foreach ($r in $blockRules) {
            Write-Host ("  BLOCK rule found: {0}" -f $r.DisplayName) -ForegroundColor Red
        }
        Write-Host "  A Block rule beats any Allow rule. Delete it: Win+R > wf.msc > Inbound Rules > opentrack > Delete" -ForegroundColor Red
    } else {
        Write-Host "  no Block rule for opentrack found"
    }
    $mine = netsh advfirewall firewall show rule name="MR Motion Cam UDP 4242" 2>$null | Out-String
    if ($mine -match 'Rule Name') {
        Write-Host "  'MR Motion Cam UDP 4242' allow rule exists"
    } else {
        Write-Host "  allow rule for UDP 4242 NOT found -> run OPEN_UDP_4242_FIREWALL.bat as administrator" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== 4) Listening for the phone on UDP $Port for $Seconds seconds ===" -ForegroundColor Cyan
try {
    $udp = New-Object System.Net.Sockets.UdpClient($Port)
} catch {
    Write-Host "  Cannot open UDP port $Port. Is OpenTrack (or another program) running? Stop it and run this again." -ForegroundColor Red
    exit 1
}
$udp.Client.ReceiveTimeout = 500
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
$count = 0
$badSize = 0
$lastSource = ''
$lastPrint = -1000
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Write-Host "  Now press 'Start tracking' in the phone app and move the phone..."

while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
    try {
        $bytes = $udp.Receive([ref]$ep)
    } catch [System.Net.Sockets.SocketException] {
        continue
    }
    $count++
    $lastSource = $ep.Address.ToString()
    if ($bytes.Length -ne 48) { $badSize++ }
    if ($sw.ElapsedMilliseconds - $lastPrint -ge 500) {
        $lastPrint = $sw.ElapsedMilliseconds
        if ($bytes.Length -eq 48) {
            $yaw   = [BitConverter]::ToDouble($bytes, 24)
            $pitch = [BitConverter]::ToDouble($bytes, 32)
            $roll  = [BitConverter]::ToDouble($bytes, 40)
            Write-Host ("  from {0}  yaw={1,7:N1}  pitch={2,7:N1}  roll={3,7:N1}   (total packets: {4})" -f $lastSource, $yaw, $pitch, $roll, $count)
        } else {
            Write-Host ("  from {0}  packet size {1} bytes (OpenTrack needs exactly 48)" -f $lastSource, $bytes.Length) -ForegroundColor Yellow
        }
    }
}
$udp.Close()

Write-Host ""
Write-Host "=== Result ===" -ForegroundColor Cyan
if ($count -eq 0) {
    Write-Host "  0 packets arrived. The phone is NOT reaching this PC. Check, in this order:" -ForegroundColor Red
    Write-Host "   1. Phone and PC on the same Wi-Fi/router (the app shows a warning if the subnets differ)."
    Write-Host "   2. The IP in the app is the PC's real Wi-Fi/Ethernet address from step 1 (not 127.0.0.1, not a virtual adapter)."
    Write-Host "   3. Run OPEN_UDP_4242_FIREWALL.bat as administrator, and make sure no Block rule exists (step 3)."
    Write-Host "   4. Router setting 'AP isolation' / 'Client isolation' / Guest network blocks phone-to-PC traffic. Turn it off."
    Write-Host "   5. Turn off any VPN on the PC and the phone while testing."
} elseif ($badSize -gt 0) {
    Write-Host "  Packets arrive ($count) but $badSize had the wrong size. OpenTrack ignores anything that is not 48 bytes." -ForegroundColor Yellow
} else {
    Write-Host "  OK: $count valid packets from $lastSource. The phone -> PC path works." -ForegroundColor Green
    Write-Host "  If OpenTrack still shows zeros: Input = 'UDP over network', port = $Port, press Start (OpenTrack must be running AFTER this test ended)."
}
Write-Host ""

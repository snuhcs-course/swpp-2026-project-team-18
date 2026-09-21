param(
  [string]$Target = "10.148.189.137:37551",
  [string]$ServerHost = "100.124.112.94",
  [int]$ServerPort = 8000,
  [string]$OutFile = "$env:TEMP\netcheck.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList
$url = "http://${ServerHost}:${ServerPort}/api/health"

function Add-Section($title, $cmd) {
  [void]$lines.Add("=== $title ===")
  $out = (& $adb -s $Target shell $cmd 2>&1 | Out-String).Trim()
  if (-not $out) { $out = "(no output)" }
  [void]$lines.Add($out)
}

# 1. phone's own tailscale address
Add-Section "phone ip addr (tun/tailscale)" "ip -4 addr show | grep -E 'inet |tun'"

# 2. can phone reach the PC over tailscale
Add-Section "ping PC tailscale ip" "ping -c 3 -W 3 $ServerHost"

# 3. HTTP health check from the phone itself
Add-Section "curl health ($url)" "curl -s -m 10 -w '\nHTTP_CODE=%{http_code}\n' $url"

# 4. fallback if curl is absent
Add-Section "toybox wget health" "toybox wget -O - -T 10 $url"

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"

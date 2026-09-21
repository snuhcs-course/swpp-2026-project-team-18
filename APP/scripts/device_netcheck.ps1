# 폰에서 서버까지 닿는지 확인한다.
#
# 기본은 **팀 공용 서버**다. 앱이 그걸 보기 때문이다
# (APP/gradle.properties 의 jitApiBaseUrl).
#
#   .\scripts\device_netcheck.ps1 -Target 100.125.75.81:34955
#
# 로컬 백엔드로 개발하는 중이면 그 주소를 넘긴다.
#
#   .\scripts\device_netcheck.ps1 -Target <폰> -ServerUrl http://192.168.0.12:8000
#
# 무선 디버깅 포트(-Target)는 화면을 닫고 열 때마다 바뀐다.

param(
  [Parameter(Mandatory = $true)][string]$Target,
  [string]$ServerUrl = "https://justintime-api.onrender.com",
  [string]$OutFile = "$env:TEMP\netcheck.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList
$url = $ServerUrl.TrimEnd('/') + "/api/health"
$serverHost = ([System.Uri]$ServerUrl).Host

function Add-Section($title, $cmd) {
  [void]$lines.Add("=== $title ===")
  $out = (& $adb -s $Target shell $cmd 2>&1 | Out-String).Trim()
  if (-not $out) { $out = "(no output)" }
  [void]$lines.Add($out)
}

[void]$lines.Add("target = $Target")
[void]$lines.Add("server = $url")
[void]$lines.Add("")

# 1. 폰의 네트워크 인터페이스. Wi-Fi·모바일·VPN(tun) 주소를 한눈에 본다.
Add-Section "phone interfaces" "ip -4 addr show | grep -E 'inet |tun'"

# 2. 이름이 풀리는지. DNS 가 막히면 여기서 드러난다.
Add-Section "dns ($serverHost)" "ping -c 1 -W 3 $serverHost"

# 3. 폰에는 curl·wget 이 없다. 앱 로그가 유일한 HTTP 확인 수단이다.
#    device_applog.ps1 로 OkHttp 로그를 보는 편이 확실하다.
[void]$lines.Add("=== HTTP ===")
[void]$lines.Add(
  "폰 셸에는 curl/wget 이 없다. HTTP 확인은 앱을 띄우고" +
  " device_applog.ps1 로 OkHttp 로그를 보는 것이 확실하다."
)
[void]$lines.Add("  .\scripts\device_applog.ps1 -Target $Target")

# 4. 개발 PC 에서도 같은 주소가 되는지. 폰만 안 되는 것인지 가른다.
[void]$lines.Add("")
[void]$lines.Add("=== PC 에서 같은 주소 ===")
try {
  $r = Invoke-WebRequest -Uri $url -TimeoutSec 90 -UseBasicParsing
  [void]$lines.Add("PC -> $url : $($r.StatusCode) $($r.Content)")
} catch {
  [void]$lines.Add("PC -> $url : 실패 - $($_.Exception.Message)")
}

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"

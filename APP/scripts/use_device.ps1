# 실기기로 붙일 준비를 한다.
#
# 하는 일
#   1) 개발 PC 의 LAN IP 를 찾아 보여준다
#   2) local.properties 의 devServerHost 를 그 IP 로 설정한다 (앱의 BASE_URL)
#   3) Windows 방화벽에 8000 인바운드 규칙이 있는지 확인한다
#   4) 연결된 기기와 Android 버전을 확인한다
#
# 사용법
#   .\scripts\use_device.ps1                    # IP 자동 선택 + 점검
#   .\scripts\use_device.ps1 -Host 192.168.0.12 # IP 직접 지정
#   .\scripts\use_device.ps1 -Emulator          # 에뮬레이터로 되돌린다
#
# 방화벽 규칙 추가는 관리자 권한이 필요하다. 명령만 안내하고 직접 실행하지 않는다.

param(
    [string]$ServerHost,
    [switch]$Emulator
)

$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$localProps = Join-Path $PSScriptRoot "..\local.properties"

function Line { Write-Host ("-" * 66) }

# --------------------------------------------------------------- 1) IP 찾기
Line
Write-Host "1) 개발 PC 의 IPv4 주소"
Line

$candidates = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object IPAddress, InterfaceAlias

foreach ($c in $candidates) {
    Write-Host ("   {0,-18} {1}" -f $c.IPAddress, $c.InterfaceAlias)
}

if ($Emulator) {
    $target = "10.0.2.2"
    Write-Host ""
    Write-Host "   -Emulator 지정 -> 10.0.2.2 로 되돌린다"
} elseif ($ServerHost) {
    $target = $ServerHost
    Write-Host ""
    Write-Host "   직접 지정 -> $target"
} else {
    # Wi-Fi 를 우선한다. 없으면 첫 번째.
    $wifi = $candidates | Where-Object { $_.InterfaceAlias -like "*Wi-Fi*" } | Select-Object -First 1
    $pick = if ($wifi) { $wifi } else { $candidates | Select-Object -First 1 }
    $target = $pick.IPAddress
    Write-Host ""
    Write-Host "   자동 선택 -> $target ($($pick.InterfaceAlias))"
    Write-Host "   폰이 이 PC 와 같은 Wi-Fi 에 있어야 한다."
    Write-Host "   다른 주소를 쓰려면  -ServerHost <IP>"
}

# ------------------------------------------------- 2) local.properties 설정
Line
Write-Host "2) local.properties 의 devServerHost"
Line

$existing = if (Test-Path $localProps) {
    Get-Content $localProps -Encoding UTF8 | Where-Object { $_ -notmatch "^\s*devServerHost\s*=" }
} else { @() }

$new = @($existing) + "devServerHost=$target"
# local.properties 는 ASCII 로만 쓴다. Gradle 이 ISO-8859-1 로 읽으므로
# 한글 주석을 넣으면 깨진다.
Set-Content -Path $localProps -Value $new -Encoding ASCII
Write-Host "   devServerHost=$target  (저장 완료)"
Write-Host "   BASE_URL 은 http://${target}:8000/ 이 된다"
Write-Host "   local.properties 는 gitignore 대상이라 커밋되지 않는다"

# ------------------------------------------------------------- 3) 방화벽
Line
Write-Host "3) Windows 방화벽 - 8000 인바운드"
Line

cmd /c "netsh advfirewall firewall show rule name=all dir=in" > "$env:TEMP\fw_check.txt" 2>&1
$hasRule = (Select-String -Path "$env:TEMP\fw_check.txt" -Pattern "8000" -SimpleMatch -Quiet)

if ($hasRule) {
    Write-Host "   8000 관련 규칙이 있다. 그래도 연결이 안 되면 규칙이 허용인지 확인한다."
} else {
    Write-Host "   허용 규칙이 없다. 폰에서 서버에 닿지 않으면 이것이 원인이다."
    Write-Host ""
    Write-Host "   관리자 PowerShell 에서 한 번 실행한다:"
    Write-Host ""
    Write-Host '     New-NetFirewallRule -DisplayName "JustInTime dev server 8000" `' -ForegroundColor Yellow
    Write-Host '       -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 `' -ForegroundColor Yellow
    Write-Host '       -Profile Private' -ForegroundColor Yellow
    Write-Host ""
    Write-Host "   Profile Private 로 좁혔다. 공용 Wi-Fi 에서까지 열어 두지 않는다."
}

# --------------------------------------------------------------- 4) 기기
Line
Write-Host "4) 연결된 기기"
Line

$devices = & $adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
if (-not $devices) {
    Write-Host "   없음."
    Write-Host ""
    Write-Host "   USB 로 붙이려면"
    Write-Host "     폰: 설정 > 휴대전화 정보 > 소프트웨어 정보 > 빌드번호 7번 탭"
    Write-Host "         설정 > 개발자 옵션 > USB 디버깅 ON"
    Write-Host "     PC 연결 후 폰에 뜨는 'USB 디버깅을 허용하시겠습니까' 를 허용"
    Write-Host ""
    Write-Host "   무선으로 붙이려면 (Android 11+, 케이블 불필요)"
    Write-Host "     폰: 개발자 옵션 > 무선 디버깅 ON > '페어링 코드로 기기 페어링'"
    Write-Host "     PC:"
    Write-Host "       adb pair <폰에 뜬 IP:포트>        # 페어링 코드 입력"
    Write-Host "       adb connect <폰 IP:무선디버깅포트>  # 페어링 화면 밖의 포트"
} else {
    foreach ($d in $devices) {
        $serial = ($d -split "\s+")[0]
        $state = ($d -split "\s+")[1]
        $rel = (& $adb -s $serial shell getprop ro.build.version.release 2>&1 | Out-String).Trim()
        $sdk = (& $adb -s $serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
        $model = (& $adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
        Write-Host "   $serial  [$state]  $model  Android $rel (API $sdk)"
        if ($state -eq "unauthorized") {
            Write-Host "      -> 폰 화면의 'USB 디버깅 허용' 을 눌러야 한다"
        } elseif ($sdk -match "^\d+$" -and [int]$sdk -lt 34) {
            Write-Host "      -> minSdk 34 미달. 이 기기에는 설치되지 않는다" -ForegroundColor Red
        }
    }
}

Line
Write-Host "다음 단계"
Line
Write-Host "   1. 서버:  cd backend"
Write-Host "             .\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000"
Write-Host "   2. 앱:    cd APP"
Write-Host "             .\gradlew.bat installDebug"
Write-Host ""
Write-Host "   폰 브라우저로 http://${target}:8000/api/health 가 열리면 경로는 뚫린 것이다."
Write-Host "   안 열리면 방화벽 또는 다른 Wi-Fi 문제다."
Line

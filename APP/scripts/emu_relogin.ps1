# 앱을 재시작해 로그인까지 진행한다. 검증 중 여러 번 필요하다.
#
# 왜 필요한가 — 알람 등록은 홈 화면이 새로 읽을 때만 일어난다(HomeViewModel.refresh).
# MainActivity 는 exported=false 라서 adb 로 직접 띄울 수 없고, LoginActivity 는
# 토큰이 있어도 자동 진입하지 않는다(P1 남은 항목). 그래서 매번 로그인을 지난다.
#
#   .\scripts\emu_relogin.ps1 -Email x@y.z -Password pw

param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password
)

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.swpp.wakeup"

# 좌표는 1080x2400 기준이다.
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb logcat -c 2>&1 | Out-Null
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Start-Sleep -Seconds 5

& $adb shell input tap 540 1070 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input text $Email 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input tap 540 1270 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input text $Password 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input keyevent 111 2>&1 | Out-Null
Start-Sleep -Milliseconds 600
& $adb shell input tap 540 1453 2>&1 | Out-Null
Start-Sleep -Seconds 7

$line = & $adb logcat -d -s AlarmScheduler:V 2>&1 | Select-Object -Last 1
Write-Host "  $line"

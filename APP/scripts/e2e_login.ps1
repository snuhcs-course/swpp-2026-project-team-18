# 앱을 재설치하고 로그인까지 진행한다.
#
# 긴 adb 체인을 인라인으로 넘기면 PowerShell 출력이 잘려 어디서 실패했는지
# 알 수 없다. 파일로 두고 단계마다 표시한다.
#
#   .\scripts\e2e_login.ps1 -Email demo@demo.com -Password demo1234
#   .\scripts\e2e_login.ps1 -SkipInstall

param(
    [string]$Email = "demo@demo.com",
    [string]$Password = "demo1234",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.swpp.wakeup"
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"

function Step($msg) { Write-Host "[e2e] $msg" }

if (-not $SkipInstall) {
    Step "install"
    $out = & $adb install -r $apk 2>&1 | Out-String
    if ($out -notmatch "Success") { throw "설치 실패:`n$out" }
}

Step "force-stop + logcat clear"
& $adb shell am force-stop $pkg | Out-Null
& $adb logcat -c

Step "launch"
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 4

# 좌표는 1080x2400 에뮬레이터 기준이다.
Step "type email"
& $adb shell input tap 533 1069 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input text $Email | Out-Null
Start-Sleep -Milliseconds 400

Step "type password"
& $adb shell input tap 533 1267 | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell input text $Password | Out-Null
Start-Sleep -Milliseconds 400

Step "hide IME"
& $adb shell input keyevent 111 | Out-Null
Start-Sleep -Milliseconds 500

Step "tap login"
& $adb shell input tap 540 1453 | Out-Null
Start-Sleep -Seconds 5

Step "current activity"
$focus = & $adb shell dumpsys activity activities 2>&1 | Select-String -Pattern "mResumedActivity" | Select-Object -First 1
Write-Host "  $($focus.Line.Trim())"

Step "done"

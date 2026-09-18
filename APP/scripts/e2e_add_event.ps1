# 일정 추가 화면 E2E. 재설치 → 로그인 → 일정 추가 → 직접 입력 → 종류 드롭다운
# → 장소 검색 → 경로 선택 → 저장까지 진행하며 단계마다 스크린샷을 남긴다.
#
#   .\scripts\e2e_add_event.ps1
#   .\scripts\e2e_add_event.ps1 -SkipInstall
#
# 스크린샷은 %TEMP%\jit_<Prefix><n>_<name>_small.png 로 쌓인다.

param(
    [string]$Email = "demo@demo.com",
    [string]$Password = "demo1234",
    [string]$Prefix = "ae",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.swpp.wakeup"
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
$shot = Join-Path $PSScriptRoot "shot.ps1"

$script:step = 0
function Shot($name) {
    $script:step++
    $n = "{0}{1:d2}_{2}" -f $Prefix, $script:step, $name
    & $shot -Name $n | Out-Null
    Write-Host ("  [shot] " + $n)
}
function Tap($x, $y, $wait = 1.2) {
    & $adb shell input tap $x $y | Out-Null
    Start-Sleep -Seconds $wait
}
# 이름을 TypeText 로 둔다. `Type` 은 PowerShell 에서 Get-Content 의 별칭이라
# 함수로 정의해도 별칭이 이겨서 파일을 읽으려 든다.
function TypeText($s) {
    & $adb shell input text ($s -replace " ", "%s") | Out-Null
    Start-Sleep -Milliseconds 400
}
function Key($k) {
    & $adb shell input keyevent $k | Out-Null
    Start-Sleep -Milliseconds 350
}
function Step($m) { Write-Host "[e2e] $m" }

if (-not $SkipInstall) {
    Step "install"
    $out = & $adb install -r $apk 2>&1 | Out-String
    if ($out -notmatch "Success") { throw "설치 실패:`n$out" }
}

Step "launch + login"
& $adb shell am force-stop $pkg | Out-Null
& $adb logcat -c
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 4

Tap 533 1069 0.5
TypeText $Email
Tap 533 1267 0.5
TypeText $Password
Key 111                      # IME 닫기
Tap 540 1453 6               # 로그인
Shot "home"

Step "일정 추가 열기"
# 홈 하단의 "+ 일정 추가". 일정 수에 따라 y 가 달라지므로 화면 아래로 스크롤한 뒤
# 고정 위치를 쓰지 않고 UI 덤프에서 좌표를 찾는다.
& $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
$xml = & $adb shell cat /sdcard/ui.xml
$m = [regex]::Match($xml, 'text="\+ 일정 추가"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) {
    $m = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="\+ 일정 추가"')
}
if ($m.Success) {
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Step "  버튼 좌표 ($cx, $cy)"
    Tap $cx $cy 3
} else {
    Step "  버튼을 못 찾음. 기본 좌표로 시도"
    Tap 540 1847 3
}
Shot "add"

Step "제목 입력"
Tap 540 507 0.5
TypeText "Route Test"
Key 111
Shot "title"

Step "done"
Write-Host "  이후 단계(직접 입력·드롭다운·장소·경로)는 좌표가 화면 상태에 따라 달라져"
Write-Host "  대화형으로 진행한다. 스크린샷을 보고 이어서 tap.ps1 을 쓴다."

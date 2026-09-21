# 실기기에 연결 -> 설치 -> 권한 부여까지 한 번에 처리한다.
#
# 사용법:
#   .\scripts\device_install.ps1 -Port 41234
#   .\scripts\device_install.ps1 -Port 41234 -DeviceIp 10.148.189.137
#
# 사전 조건:
#   1. 폰 개발자 옵션 > 무선 디버깅 ON
#   2. adb pair 로 페어링 완료 (한 번만 하면 됨)
#   3. Port 는 무선 디버깅 화면 "IP 주소 및 포트" 줄의 포트 (페어링 포트와 다름)

param(
  [Parameter(Mandatory = $true)][int]$Port,
  [string]$DeviceIp = "10.148.189.137",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = "${DeviceIp}:${Port}"

function Step($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }

Step "1/5 연결: $target"
$out = & $adb connect $target 2>&1 | Out-String
Write-Host $out.Trim()
if ($out -notmatch "connected to") {
  Write-Host "연결 실패. 무선 디버깅 화면의 포트가 바뀌었는지 확인할 것 (화면을 열 때마다 바뀔 수 있음)." -ForegroundColor Red
  exit 1
}

Step "2/5 기기 확인"
& $adb -s $target wait-for-device
$sdk = (& $adb -s $target shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
$model = (& $adb -s $target shell getprop ro.product.model 2>&1 | Out-String).Trim()
Write-Host "model=$model  sdk=$sdk"
if ([int]$sdk -lt 34) {
  Write-Host "minSdk 미달(34 필요). 설치가 실패한다." -ForegroundColor Red
  exit 1
}

if (-not $SkipBuild) {
  Step "3/5 빌드"
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  & .\gradlew.bat assembleDebug
  if ($LASTEXITCODE -ne 0) { Write-Host "빌드 실패" -ForegroundColor Red; exit 1 }
} else {
  Step "3/5 빌드 생략"
}

Step "4/5 설치"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
& $adb -s $target install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "설치 실패" -ForegroundColor Red; exit 1 }

Step "5/5 권한 부여"
$pkg = "com.swpp.wakeup"
foreach ($perm in @(
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.ACCESS_COARSE_LOCATION"
)) {
  & $adb -s $target shell pm grant $pkg $perm 2>&1 | Out-String | ForEach-Object { if ($_.Trim()) { Write-Host "  $perm : $($_.Trim())" } }
}
Write-Host "  권한 부여 완료" -ForegroundColor Green

Write-Host "`n완료. 앱을 실행하고 demo@demo.com / demo1234 로 로그인할 것." -ForegroundColor Green
Write-Host "logcat 보기:  $adb -s $target logcat -s JustInTime:V AlarmManager:V" -ForegroundColor DarkGray

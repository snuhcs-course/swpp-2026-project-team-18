# 에뮬레이터 화면을 캡처해 축소 저장한다.
#
# PowerShell 의 > 리다이렉트는 바이너리를 깨뜨리므로 adb exec-out 대신
# screencap 으로 기기에 저장한 뒤 pull 한다.
# 첨부 가능한 크기(한 변 2000px)를 넘지 않게 축소한다.
#
# 사용법:
#   .\scripts\shot.ps1 -Name login
#   .\scripts\shot.ps1 -Name login -Scale 0.6
#
# 표시 좌표 -> 실좌표 환산은 Scale 로 나눈다. 기본 0.45 이므로
# 표시 (243, 405) 는 실제 (540, 900) 이다.

param(
    [Parameter(Mandatory = $true)][string]$Name,
    [double]$Scale = 0.45,
    # 기기가 부팅 중이면 이만큼 기다린다.
    [int]$WaitSeconds = 60
)

# "Stop" 을 쓰지 않는다. adb 는 정상 진행 상황도 stderr 로 쓰기 때문에
# (`pull` 의 전송 속도 등) Stop 이면 성공한 명령에서도 스크립트가 죽는다.
# 대신 아래에서 결과 파일을 직접 확인한다.
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$remote = "/sdcard/jit_shot.png"
$raw = Join-Path $env:TEMP "jit_$Name.png"
$small = Join-Path $env:TEMP "jit_${Name}_small.png"

# 기기가 준비되기를 기다린다. 로케일 변경이나 재부팅 직후에는 잠깐 offline 이
# 되는데, 그대로 진행하면 빈 파일을 System.Drawing 에 넘겨 .NET 예외가
# 수십 줄 쏟아진다. 원인은 캡처가 아니라 기기 상태이므로 여기서 걸러 낸다.
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    $state = (& $adb get-state 2>&1 | Out-String).Trim()
    $booted = (& $adb shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
    if ($state -eq "device" -and $booted -match "1") { break }
    Start-Sleep -Seconds 2
}
$state = (& $adb get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    Write-Error "기기가 준비되지 않았다 (state=$state). 에뮬레이터를 확인한다."
    exit 1
}

& $adb shell screencap -p $remote 2>&1 | Out-Null
Remove-Item $raw -ErrorAction SilentlyContinue
& $adb pull $remote $raw 2>&1 | Out-Null
& $adb shell rm -f $remote 2>&1 | Out-Null

if (-not (Test-Path $raw) -or (Get-Item $raw).Length -lt 1024) {
    Write-Error "캡처 파일이 비었다. screencap 이 실패했다."
    exit 1
}

Add-Type -AssemblyName System.Drawing
$img = $null; $bmp = $null; $g = $null
try {
    $img = [System.Drawing.Image]::FromFile($raw)
    $w = [int]($img.Width * $Scale)
    $h = [int]($img.Height * $Scale)
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $w, $h)
    $bmp.Save($small, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    if ($g) { $g.Dispose() }
    if ($bmp) { $bmp.Dispose() }
    if ($img) { $img.Dispose() }
}

Write-Output $small

# adb 입력을 짧게 감싼다. 인라인 adb 체인은 PowerShell 에서 출력이 잘린다.
#
#   .\scripts\tap.ps1 -Tap "540,373"
#   .\scripts\tap.ps1 -Tap "469,511" -Text "Sillim Station"
#   .\scripts\tap.ps1 -Key 111
#   .\scripts\tap.ps1 -Tap "540,373" -Shot step7

param(
    [string]$Tap,
    [string]$Text,
    [int]$Key = 0,
    [string]$Shot,
    [double]$Wait = 1.5
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

if ($Tap) {
    $xy = $Tap.Split(",")
    & $adb shell input tap $xy[0].Trim() $xy[1].Trim() | Out-Null
    Start-Sleep -Milliseconds 400
}

if ($Text) {
    # input text 는 공백을 %s 로 받는다. 비ASCII 는 넣을 수 없다.
    $escaped = $Text -replace " ", "%s"
    & $adb shell input text $escaped | Out-Null
    Start-Sleep -Milliseconds 400
}

if ($Key -ne 0) {
    & $adb shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds 400
}

Start-Sleep -Seconds $Wait

if ($Shot) {
    & (Join-Path $PSScriptRoot "shot.ps1") -Name $Shot
}

param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Pkg = "com.swpp.wakeup",
  [int]$WaitSec = 20,
  [string]$OutFile = "$env:TEMP\launch.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList

[void]$lines.Add("=== launcher activity ===")
$res = (& $adb -s $Target shell "cmd package resolve-activity --brief $Pkg" 2>&1 | Out-String).Trim()
[void]$lines.Add($res)

[void]$lines.Add("=== force-stop + clear logcat ===")
& $adb -s $Target shell am force-stop $Pkg 2>&1 | Out-Null
& $adb -s $Target logcat -c 2>&1 | Out-Null
[void]$lines.Add("done")

[void]$lines.Add("=== launch ===")
$launch = (& $adb -s $Target shell "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-String).Trim()
[void]$lines.Add($launch)

& $adb -s $Target shell "sleep $WaitSec" 2>&1 | Out-Null

[void]$lines.Add("=== logcat (app pid) ===")
$log = (& $adb -s $Target logcat -d -v brief 2>&1 | Out-String) -split "`n"
foreach ($l in $log) {
  if ($l -match "wakeup|JustInTime|OkHttp|Retrofit|Alarm|Geofence|Location|HTTP|Exception|FATAL|ANR") {
    [void]$lines.Add($l.TrimEnd())
  }
}

[void]$lines.Add("=== top activity ===")
$top = (& $adb -s $Target shell "dumpsys activity activities | grep -E 'ResumedActivity|mFocusedApp'" 2>&1 | Out-String).Trim()
[void]$lines.Add($top)

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"

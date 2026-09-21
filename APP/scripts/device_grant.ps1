param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Pkg = "com.swpp.wakeup",
  [string]$OutFile = "$env:TEMP\grant.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList

foreach ($perm in @(
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.ACCESS_COARSE_LOCATION"
)) {
  $r = (& $adb -s $Target shell pm grant $Pkg $perm 2>&1 | Out-String).Trim()
  $msg = if ($r) { $r } else { "OK" }
  [void]$lines.Add("grant $perm => $msg")
}

[void]$lines.Add("--- installed ---")
[void]$lines.Add((& $adb -s $Target shell pm list packages $Pkg 2>&1 | Out-String).Trim())

[void]$lines.Add("--- granted state ---")
$dump = (& $adb -s $Target shell dumpsys package $Pkg 2>&1 | Out-String) -split "`n"
foreach ($l in $dump) {
  if ($l -match "POST_NOTIFICATIONS|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM") {
    [void]$lines.Add($l.Trim())
  }
}

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"

param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Pkg = "com.swpp.wakeup",
  [string]$OutFile = "$env:TEMP\alarmcheck.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList

function Sec($t) { [void]$lines.Add(""); [void]$lines.Add("=== $t ===") }

Sec "doze whitelist (battery optimization exempt?)"
$wl = (& $adb -s $Target shell "dumpsys deviceidle whitelist" 2>&1 | Out-String) -split "`n"
$hit = $wl | Where-Object { $_ -match [regex]::Escape($Pkg) }
[void]$lines.Add($(if ($hit) { "EXEMPT: $($hit -join ' ')" } else { "NOT exempt (Doze applies)" }))

Sec "app standby bucket"
$b = (& $adb -s $Target shell "am get-standby-bucket $Pkg" 2>&1 | Out-String).Trim()
[void]$lines.Add("bucket=$b  (10=active 20=working_set 30=frequent 40=rare 45=restricted)")

Sec "battery optimization / app ops"
$ops = (& $adb -s $Target shell "cmd appops get $Pkg RUN_IN_BACKGROUND" 2>&1 | Out-String).Trim()
[void]$lines.Add("RUN_IN_BACKGROUND: $ops")
$ops2 = (& $adb -s $Target shell "cmd appops get $Pkg RUN_ANY_IN_BACKGROUND" 2>&1 | Out-String).Trim()
[void]$lines.Add("RUN_ANY_IN_BACKGROUND: $ops2")

Sec "scheduled alarms for this package"
$al = (& $adb -s $Target shell "dumpsys alarm" 2>&1 | Out-String) -split "`n"
$c = 0
foreach ($l in $al) {
  if ($l -match [regex]::Escape($Pkg)) { [void]$lines.Add($l.Trim()); $c++ }
  if ($c -gt 60) { [void]$lines.Add("...(truncated)"); break }
}
if ($c -eq 0) { [void]$lines.Add("(no alarm registered yet)") }

Sec "exact alarm permission state"
$ea = (& $adb -s $Target shell "dumpsys alarm | grep -A3 'Allow while idle'" 2>&1 | Out-String).Trim()
[void]$lines.Add($ea)

Sec "foreground services"
$fs = (& $adb -s $Target shell "dumpsys activity services $Pkg | grep -E 'ServiceRecord|isForeground|foregroundServiceType'" 2>&1 | Out-String).Trim()
[void]$lines.Add($(if ($fs) { $fs } else { "(none)" }))

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"

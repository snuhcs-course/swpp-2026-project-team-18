param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Pkg = "com.swpp.wakeup",
  [string]$OutFile = "$env:TEMP\applog.txt",
  [switch]$Clear,
  [int]$WaitSec = 0
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList

if ($Clear) { & $adb -s $Target logcat -c 2>&1 | Out-Null }
if ($WaitSec -gt 0) { & $adb -s $Target shell "sleep $WaitSec" 2>&1 | Out-Null }

$pid_ = (& $adb -s $Target shell "pidof $Pkg" 2>&1 | Out-String).Trim()
[void]$lines.Add("pid=$pid_")

if ($pid_) {
  $raw = (& $adb -s $Target logcat -d -v time --pid=$pid_ 2>&1 | Out-String) -split "`n"
  [void]$lines.Add("--- app log ($($raw.Count) lines) ---")
  foreach ($l in $raw) { [void]$lines.Add($l.TrimEnd()) }
} else {
  [void]$lines.Add("(process not running)")
}

# crashes regardless of pid
[void]$lines.Add("--- crash buffer ---")
$cr = (& $adb -s $Target logcat -d -b crash -v time 2>&1 | Out-String) -split "`n"
foreach ($l in $cr) { if ($l -match "wakeup") { [void]$lines.Add($l.TrimEnd()) } }

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile pid=$pid_"

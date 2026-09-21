param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Pkg = "com.swpp.wakeup",
  [switch]$Relaunch,
  [string]$OutFile = "$env:TEMP\wake.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$lines = New-Object System.Collections.ArrayList

# screen state before
$st = (& $adb -s $Target shell "dumpsys display | grep -E 'mScreenState|mGlobalDisplayState'" 2>&1 | Out-String).Trim()
[void]$lines.Add("before: $st")

# wake (224 = KEYCODE_WAKEUP, idempotent - does not sleep if already awake)
& $adb -s $Target shell "input keyevent 224" 2>&1 | Out-Null
& $adb -s $Target shell "sleep 1" 2>&1 | Out-Null

$st2 = (& $adb -s $Target shell "dumpsys display | grep -E 'mScreenState|mGlobalDisplayState'" 2>&1 | Out-String).Trim()
[void]$lines.Add("after wake: $st2")

$lock = (& $adb -s $Target shell "dumpsys window | grep -E 'mDreamingLockscreen|isStatusBarKeyguard|showing'" 2>&1 | Out-String).Trim()
[void]$lines.Add("lockscreen: $lock")

if ($Relaunch) {
  & $adb -s $Target shell "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
  & $adb -s $Target shell "sleep 3" 2>&1 | Out-Null
  [void]$lines.Add("relaunched")
}

$top = (& $adb -s $Target shell "dumpsys activity activities | grep -E 'ResumedActivity'" 2>&1 | Out-String).Trim()
[void]$lines.Add("top: $top")

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"
